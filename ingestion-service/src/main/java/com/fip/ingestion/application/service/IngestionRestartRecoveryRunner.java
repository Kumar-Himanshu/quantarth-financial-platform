package com.fip.ingestion.application.service;

import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.io.File;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Component
public class IngestionRestartRecoveryRunner implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(IngestionRestartRecoveryRunner.class);

    private final IngestionJobJpaRepository jobRepository;
    private final MappingProfileService profileService;
    private final CsvIngestionService csvIngestionService;
    private final JsonIngestionService jsonIngestionService;

    public IngestionRestartRecoveryRunner(
            IngestionJobJpaRepository jobRepository,
            MappingProfileService profileService,
            CsvIngestionService csvIngestionService,
            JsonIngestionService jsonIngestionService) {
        this.jobRepository = jobRepository;
        this.profileService = profileService;
        this.csvIngestionService = csvIngestionService;
        this.jsonIngestionService = jsonIngestionService;
    }

    @Override
    public void run(ApplicationArguments args) {
        log.info("Running IngestionRestartRecoveryRunner: checking for interrupted jobs from prior process instance...");
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(5);

        List<IngestionJobJpaEntity> stuckJobs = jobRepository.findByStatusInAndHeartbeatAtBefore(
                List.of(JobStatus.RECEIVED, JobStatus.VALIDATING, JobStatus.PROCESSING), threshold);

        if (stuckJobs.isEmpty()) {
            log.info("No interrupted ingestion jobs detected.");
            return;
        }

        log.info("Detected [{}] interrupted ingestion jobs. Attempting restart recovery...", stuckJobs.size());

        for (IngestionJobJpaEntity job : stuckJobs) {
            String stagingPath = job.getStagingFilePath();
            if (stagingPath != null && new File(stagingPath).exists()) {
                log.info("Resuming job [{}] (type: {}) from row [{}] on staged file [{}]",
                        job.getJobId(), job.getSourceType(), job.getLastProcessedRow(), stagingPath);
                try {
                    TenantContext.setCurrentTenant(job.getTenantId());
                    job.setRetryCount(job.getRetryCount() + 1);
                    job.setHeartbeatAt(LocalDateTime.now());
                    jobRepository.save(job);

                    Optional<MappingProfile> profileOpt = job.getMappingProfileId() != null
                            ? profileService.getProfile(job.getMappingProfileId())
                            : Optional.empty();

                    MappingProfile profile = profileOpt.orElseGet(() ->
                            new MappingProfile(null, job.getTenantId(), "RECOVERY", job.getSourceType(),
                                    "PAYMENT", ",", true, "yyyy-MM-dd", "UTC", "INR", java.util.Collections.emptyMap(), 1, true));

                    if (job.getSourceType() == SourceType.JSON) {
                        jsonIngestionService.processStagedJson(
                                job.getJobId(),
                                stagingPath,
                                profile,
                                job.getTenantId(),
                                job.getSourceSystem(),
                                job.getLastProcessedRow()
                        );
                    } else {
                        csvIngestionService.processStagedCsv(
                                job.getJobId(),
                                stagingPath,
                                profile,
                                job.getTenantId(),
                                job.getSourceSystem(),
                                job.getLastProcessedRow()
                        );
                    }
                } catch (Exception e) {
                    log.error("Failed to recover job [{}]", job.getJobId(), e);
                    job.setStatus(JobStatus.FAILED);
                    job.setErrorSummary("RECOVERY_FAILED: " + e.getMessage());
                    jobRepository.save(job);
                } finally {
                    TenantContext.clear();
                }
            } else {
                log.warn("Staged file missing for job [{}], marking FAILED (NODE_RESTART_FILE_UNAVAILABLE)", job.getJobId());
                job.setStatus(JobStatus.FAILED);
                job.setErrorSummary("NODE_RESTART_FILE_UNAVAILABLE: Staged file was not present on disk after container restart.");
                job.setCompletedAt(LocalDateTime.now());
                jobRepository.save(job);
            }
        }
    }
}
