package com.fip.ingestion.application.service;

import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.eventengine.EventEngineClient;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.storage.StagingStorageService;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.RecordStatus;
import com.fip.ingestion.domain.model.ReviewStatus;
import com.fip.ingestion.domain.model.SourceRecord;
import com.fip.ingestion.domain.model.SourceType;
import com.fip.ingestion.mapping.DeclarativeMappingEngine;
import com.fip.ingestion.parsing.CsvStreamAdapter;
import com.fip.shared.events.CanonicalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class CsvIngestionService {

    private static final Logger log = LoggerFactory.getLogger(CsvIngestionService.class);

    private final IngestionJobService jobService;
    private final MappingProfileService profileService;
    private final StagingStorageService stagingStorageService;
    private final CsvStreamAdapter csvStreamAdapter;
    private final DeclarativeMappingEngine mappingEngine;
    private final EventEngineClient eventEngineClient;

    public static class DuplicateFileException extends RuntimeException {
        public DuplicateFileException(String message) {
            super(message);
        }
    }

    public CsvIngestionService(
            IngestionJobService jobService,
            MappingProfileService profileService,
            StagingStorageService stagingStorageService,
            CsvStreamAdapter csvStreamAdapter,
            DeclarativeMappingEngine mappingEngine,
            EventEngineClient eventEngineClient) {
        this.jobService = jobService;
        this.profileService = profileService;
        this.stagingStorageService = stagingStorageService;
        this.csvStreamAdapter = csvStreamAdapter;
        this.mappingEngine = mappingEngine;
        this.eventEngineClient = eventEngineClient;
    }

    public JsonIngestionService.IngestionResponse submitCsv(
            MultipartFile file,
            UUID mappingProfileId,
            String sourceSystem,
            boolean force) throws Exception {

        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }

        UUID jobId = UUID.randomUUID();

        // 1. Stage File to Bounded Local Storage & compute SHA-256
        StagingStorageService.StagingResult staged = stagingStorageService.stageFile(
                tenantId, jobId, file.getInputStream());

        // 2. Advisory Deduplication Check
        if (!force) {
            Optional<IngestionJobJpaEntity> duplicateJob = jobService.findRecentCompletedJobByFileHash(tenantId, staged.fileHash());
            if (duplicateJob.isPresent()) {
                stagingStorageService.deleteStagedFile(staged.stagingFilePath());
                throw new DuplicateFileException(String.format(
                        "An identical file with SHA-256 hash [%s] was already completed in Job [%s]. " +
                        "To reprocess, resubmit with query parameter force=true or select a different mapping version.",
                        staged.fileHash(), duplicateJob.get().getJobId()));
            }
        }

        // 3. Resolve Mapping Profile
        MappingProfile profile = null;
        if (mappingProfileId != null) {
            profile = profileService.getProfile(mappingProfileId).orElse(null);
        }
        if (profile == null) {
            profile = new MappingProfile(
                    null, tenantId, "DEFAULT_CSV", SourceType.CSV, "PAYMENT",
                    ",", true, "dd/MM/yyyy", "Asia/Kolkata", "INR", Collections.emptyMap(), 1, true);
        }

        // 4. Create Ingestion Job in Database
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId,
                SourceType.CSV,
                sourceSystem != null ? sourceSystem : "CSV_UPLOAD",
                file.getOriginalFilename(),
                staged.fileHash(),
                staged.stagingFilePath(),
                profile.profileId(),
                profile.version(),
                0 // Total rows unknown until parsed
        );

        // 5. Trigger Asynchronous Background Streaming Processing
        String finalTenantId = tenantId;
        MappingProfile finalProfile = profile;
        CompletableFuture.runAsync(() -> {
            try {
                TenantContext.setCurrentTenant(finalTenantId);
                processStagedCsv(job.getJobId(), staged.stagingFilePath(), finalProfile, finalTenantId, job.getSourceSystem(), 0);
            } catch (Exception e) {
                log.error("Async CSV processing failed for job [{}]", job.getJobId(), e);
                jobService.failJob(job.getJobId(), e.getMessage());
            } finally {
                TenantContext.clear();
            }
        });

        Map<String, String> links = Map.of(
                "self", "/api/v1/ingestion/jobs/" + job.getJobId(),
                "records", "/api/v1/ingestion/jobs/" + job.getJobId() + "/records"
        );

        return new JsonIngestionService.IngestionResponse(
                job.getJobId(),
                JobStatus.RECEIVED,
                SourceType.CSV,
                job.getSourceSystem(),
                0,
                0,
                0,
                0,
                0,
                links
        );
    }

    public void processStagedCsv(
            UUID jobId,
            String stagingFilePath,
            MappingProfile profile,
            String tenantId,
            String sourceSystem,
            int skipToRow) throws Exception {

        log.info("Starting streaming processing of CSV job [{}] from row [{}] using profile [{}]",
                jobId, skipToRow, profile.name());

        List<SourceRecord> chunk = new ArrayList<>();
        int[] stats = new int[5]; // 0: processed, 1: accepted, 2: unverified, 3: rejected, 4: duplicate
        final int CHUNK_SIZE = 100;

        try (InputStream is = stagingStorageService.openStagedFile(stagingFilePath)) {
            int totalProcessed = csvStreamAdapter.parseStream(
                    is,
                    profile.delimiter(),
                    profile.hasHeader(),
                    skipToRow,
                    record -> {
                        chunk.add(record);
                        if (chunk.size() >= CHUNK_SIZE) {
                            processChunk(jobId, chunk, profile, tenantId, sourceSystem, stats);
                            chunk.clear();
                        }
                    }
            );

            // Process any trailing records
            if (!chunk.isEmpty()) {
                processChunk(jobId, chunk, profile, tenantId, sourceSystem, stats);
                chunk.clear();
            }

            jobService.completeJob(jobId, null);

        } finally {
            // Securely wipe staged file upon terminal processing
            stagingStorageService.deleteStagedFile(stagingFilePath);
        }
    }

    private void processChunk(
            UUID jobId,
            List<SourceRecord> chunk,
            MappingProfile profile,
            String tenantId,
            String sourceSystem,
            int[] stats) {

        List<CanonicalEvent> forwardList = new ArrayList<>();
        int chunkAccepted = 0;
        int chunkUnverified = 0;
        int chunkRejected = 0;
        int chunkDuplicate = 0;
        int lastRowIndex = 0;

        for (SourceRecord srcRecord : chunk) {
            lastRowIndex = srcRecord.recordIndex();
            try {
                DeclarativeMappingEngine.NormalizationResult result = mappingEngine.normalize(
                        srcRecord, profile, tenantId, sourceSystem, eventEngineClient::checkPaymentExists);

                if (result.isExternalRefundWithoutLocalPayment()) {
                    jobService.recordItem(
                            jobId, tenantId, srcRecord.recordIndex(), srcRecord.sourceRecordId(), result.entityType(), null,
                            RecordStatus.UNVERIFIED_EXTERNAL, ReviewStatus.PENDING_RECONCILIATION,
                            null, null, srcRecord.rawSnippet()
                    );
                    chunkUnverified++;
                } else {
                    forwardList.add(result.canonicalEvent());
                }

            } catch (Exception ex) {
                jobService.recordItem(
                        jobId, tenantId, srcRecord.recordIndex(), srcRecord.sourceRecordId(), profile.targetEntityType(), null,
                        RecordStatus.REJECTED, ReviewStatus.NONE,
                        "MAPPING_FAILURE", ex.getMessage(), srcRecord.rawSnippet()
                );
                chunkRejected++;
            }
        }

        if (!forwardList.isEmpty()) {
            EventEngineClient.BatchForwardResult forwardResult = eventEngineClient.forwardBatch(forwardList);
            if (forwardResult != null) {
                if (forwardResult.persistedEvents() != null) {
                    for (CanonicalEvent persisted : forwardResult.persistedEvents()) {
                        jobService.recordItem(
                                jobId, tenantId, 0, persisted.entityId(), persisted.entityType(),
                                persisted.eventId(), RecordStatus.ACCEPTED, ReviewStatus.NONE,
                                null, null, null
                        );
                        chunkAccepted++;
                    }
                }
                if (forwardResult.failures() != null) {
                    for (EventEngineClient.FailedEvent failed : forwardResult.failures()) {
                        jobService.recordItem(
                                jobId, tenantId, 0, failed.event().entityId(), failed.event().entityType(),
                                null, RecordStatus.REJECTED, ReviewStatus.NONE,
                                failed.errorCode(), failed.errorMessage(), null
                        );
                        chunkRejected++;
                    }
                }
            }
        }

        int chunkTotal = chunk.size();
        stats[0] += chunkTotal;
        stats[1] += chunkAccepted;
        stats[2] += chunkUnverified;
        stats[3] += chunkRejected;
        stats[4] += chunkDuplicate;

        jobService.incrementCounters(jobId, chunkTotal, chunkAccepted, chunkUnverified, chunkRejected, chunkDuplicate, lastRowIndex);
    }
}
