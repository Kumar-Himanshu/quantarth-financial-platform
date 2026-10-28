package com.fip.ingestion.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.fip.ingestion.parsing.JsonStreamAdapter;
import com.fip.shared.events.CanonicalEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@Service
public class JsonIngestionService {

    private static final Logger log = LoggerFactory.getLogger(JsonIngestionService.class);

    private final IngestionJobService jobService;
    private final MappingProfileService profileService;
    private final DeclarativeMappingEngine mappingEngine;
    private final EventEngineClient eventEngineClient;
    private final ObjectMapper objectMapper;
    private final StagingStorageService stagingStorageService;
    private final JsonStreamAdapter jsonStreamAdapter;

    @Value("${ingestion.limits.sync-batch-limit:50}")
    private int syncBatchLimit;

    public record JsonIngestionRequest(
            String sourceSystem,
            UUID mappingProfileId,
            List<Map<String, Object>> records
    ) {}

    public record IngestionResponse(
            UUID jobId,
            JobStatus status,
            SourceType sourceType,
            String sourceSystem,
            int totalRecords,
            int acceptedRecords,
            int unverifiedRecords,
            int rejectedRecords,
            int duplicateRecords,
            Map<String, String> links
    ) {}

    public JsonIngestionService(
            IngestionJobService jobService,
            MappingProfileService profileService,
            DeclarativeMappingEngine mappingEngine,
            EventEngineClient eventEngineClient,
            ObjectMapper objectMapper,
            StagingStorageService stagingStorageService,
            JsonStreamAdapter jsonStreamAdapter) {
        this.jobService = jobService;
        this.profileService = profileService;
        this.mappingEngine = mappingEngine;
        this.eventEngineClient = eventEngineClient;
        this.objectMapper = objectMapper;
        this.stagingStorageService = stagingStorageService;
        this.jsonStreamAdapter = jsonStreamAdapter;
    }

    public IngestionResponse ingestJson(JsonIngestionRequest request, boolean syncRequested) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }

        List<Map<String, Object>> rawRecords = request.records() != null ? request.records() : Collections.emptyList();
        String sourceSystem = request.sourceSystem() != null ? request.sourceSystem() : "JSON_API";
        int total = rawRecords.size();

        MappingProfile profile = null;
        if (request.mappingProfileId() != null) {
            profile = profileService.getProfile(request.mappingProfileId()).orElse(null);
        }
        if (profile == null) {
            profile = new MappingProfile(
                    null, tenantId, "DEFAULT_JSON", SourceType.JSON, "PAYMENT",
                    ",", true, "yyyy-MM-dd HH:mm:ss", "UTC", "INR", Collections.emptyMap(), 1, true);
        }

        UUID jobId = UUID.randomUUID();

        // -------------------------------------------------------------------------
        // Case 1: Asynchronous Execution (durably staged to survive restarts)
        // -------------------------------------------------------------------------
        if (!syncRequested) {
            StagingStorageService.StagingResult staged;
            try {
                byte[] jsonBytes = objectMapper.writeValueAsBytes(rawRecords);
                staged = stagingStorageService.stageFile(tenantId, jobId, new ByteArrayInputStream(jsonBytes), "json");
            } catch (Exception ex) {
                log.error("Failed to durably stage JSON payload for async processing", ex);
                throw new RuntimeException("Failed to durably stage JSON payload for job " + jobId, ex);
            }

            IngestionJobJpaEntity job = jobService.createJob(
                    tenantId,
                    SourceType.JSON,
                    sourceSystem,
                    "json_payload_" + total,
                    staged.fileHash(),
                    staged.stagingFilePath(),
                    profile.profileId(),
                    profile.version(),
                    total
            );

            String finalTenantId = tenantId;
            MappingProfile finalProfile = profile;
            CompletableFuture.runAsync(() -> {
                try {
                    TenantContext.setCurrentTenant(finalTenantId);
                    processStagedJson(job.getJobId(), staged.stagingFilePath(), finalProfile, finalTenantId, job.getSourceSystem(), 0);
                } catch (Exception e) {
                    log.error("Async JSON processing failed for job [{}]", job.getJobId(), e);
                    jobService.failJob(job.getJobId(), e.getMessage());
                } finally {
                    TenantContext.clear();
                }
            });

            Map<String, String> links = Map.of(
                    "self", "/api/v1/ingestion/jobs/" + job.getJobId(),
                    "records", "/api/v1/ingestion/jobs/" + job.getJobId() + "/records"
            );

            return new IngestionResponse(
                    job.getJobId(),
                    JobStatus.RECEIVED,
                    SourceType.JSON,
                    sourceSystem,
                    total,
                    0,
                    0,
                    0,
                    0,
                    links
            );
        }

        // -------------------------------------------------------------------------
        // Case 2: Synchronous Execution (for small batches requiring 200 OK)
        // -------------------------------------------------------------------------
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId,
                SourceType.JSON,
                sourceSystem,
                "json_payload_" + total,
                null,
                null,
                profile.profileId(),
                profile.version(),
                total
        );

        List<CanonicalEvent> forwardList = new ArrayList<>();
        Map<String, SourceRecord> recordMap = new HashMap<>();

        int accepted = 0;
        int unverified = 0;
        int rejected = 0;

        for (int i = 0; i < total; i++) {
            int rowIndex = i + 1;
            Map<String, Object> raw = rawRecords.get(i);
            Map<String, String> values = new HashMap<>();
            for (Map.Entry<String, Object> entry : raw.entrySet()) {
                values.put(entry.getKey(), entry.getValue() != null ? entry.getValue().toString() : "");
            }

            String sourceRecordId = null;
            for (String candidate : List.of("id", "sourceRecordId", "transactionId", "transaction_id", "txn_id", "txnid", "referenceNo")) {
                for (Map.Entry<String, String> e : values.entrySet()) {
                    if (e.getKey().trim().equalsIgnoreCase(candidate) && e.getValue() != null && !e.getValue().isBlank()) {
                        sourceRecordId = e.getValue();
                        break;
                    }
                }
                if (sourceRecordId != null) break;
            }
            if (sourceRecordId == null) {
                sourceRecordId = "JSON_ROW_" + rowIndex;
            }

            SourceRecord srcRecord = new SourceRecord(rowIndex, sourceRecordId, values, raw.toString());
            recordMap.put(sourceRecordId, srcRecord);

            try {
                DeclarativeMappingEngine.NormalizationResult result = mappingEngine.normalize(
                        srcRecord, profile, tenantId, sourceSystem, eventEngineClient::checkPaymentExists);

                if (result.isExternalRefundWithoutLocalPayment()) {
                    jobService.recordItem(
                            job.getJobId(), tenantId, rowIndex, sourceRecordId, result.entityType(), null,
                            RecordStatus.UNVERIFIED_EXTERNAL, ReviewStatus.PENDING_RECONCILIATION,
                            null, null, srcRecord.rawSnippet()
                    );
                    unverified++;
                } else {
                    forwardList.add(result.canonicalEvent());
                }

            } catch (Exception ex) {
                log.warn("Validation/Mapping failure for JSON record [{}]: {}", rowIndex, ex.getMessage());
                jobService.recordItem(
                        job.getJobId(), tenantId, rowIndex, sourceRecordId, profile.targetEntityType(), null,
                        RecordStatus.REJECTED, ReviewStatus.NONE,
                        "VALIDATION_ERROR", ex.getMessage(), srcRecord.rawSnippet()
                );
                rejected++;
            }
        }

        // Forward valid canonical events to Event Engine
        if (!forwardList.isEmpty()) {
            EventEngineClient.BatchForwardResult forwardResult = eventEngineClient.forwardBatch(forwardList);
            if (forwardResult != null) {
                if (forwardResult.persistedEvents() != null) {
                    for (CanonicalEvent persisted : forwardResult.persistedEvents()) {
                        jobService.recordItem(
                                job.getJobId(), tenantId, 0, persisted.entityId(), persisted.entityType(),
                                persisted.eventId(), RecordStatus.ACCEPTED, ReviewStatus.NONE,
                                null, null, null
                        );
                        accepted++;
                    }
                }
                if (forwardResult.failures() != null) {
                    for (EventEngineClient.FailedEvent failed : forwardResult.failures()) {
                        jobService.recordItem(
                                job.getJobId(), tenantId, 0, failed.event().entityId(), failed.event().entityType(),
                                null, RecordStatus.REJECTED, ReviewStatus.NONE,
                                failed.errorCode(), failed.errorMessage(), null
                        );
                        rejected++;
                    }
                }
            }
        }

        jobService.incrementCounters(job.getJobId(), total, accepted, unverified, rejected, 0, total);
        jobService.completeJob(job.getJobId(), null);

        Map<String, String> links = Map.of(
                "self", "/api/v1/ingestion/jobs/" + job.getJobId(),
                "records", "/api/v1/ingestion/jobs/" + job.getJobId() + "/records"
        );

        JobStatus finalStatus = rejected == 0 ? JobStatus.COMPLETED : JobStatus.PARTIALLY_COMPLETED;

        return new IngestionResponse(
                job.getJobId(),
                finalStatus,
                SourceType.JSON,
                sourceSystem,
                total,
                accepted,
                unverified,
                rejected,
                0,
                links
        );
    }

    public void processStagedJson(
            UUID jobId,
            String stagingFilePath,
            MappingProfile profile,
            String tenantId,
            String sourceSystem,
            int skipToRow) throws Exception {

        log.info("Starting streaming processing of JSON job [{}] from row [{}] using profile [{}]",
                jobId, skipToRow, profile.name());

        List<SourceRecord> chunk = new ArrayList<>();
        int[] stats = new int[5]; // 0: processed, 1: accepted, 2: unverified, 3: rejected, 4: duplicate
        final int CHUNK_SIZE = 100;

        try (InputStream is = stagingStorageService.openStagedFile(stagingFilePath)) {
            int totalProcessed = jsonStreamAdapter.parseStream(
                    is,
                    skipToRow,
                    record -> {
                        chunk.add(record);
                        if (chunk.size() >= CHUNK_SIZE) {
                            processChunk(jobId, chunk, profile, tenantId, sourceSystem, stats);
                            chunk.clear();
                        }
                    }
            );

            if (!chunk.isEmpty()) {
                processChunk(jobId, chunk, profile, tenantId, sourceSystem, stats);
                chunk.clear();
            }

            jobService.completeJob(jobId, null);

        } finally {
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
