package com.fip.ingestion.integration;

import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaRepository;
import com.fip.ingestion.application.service.IngestionJobService;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.SourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Milestone 4B Task 4: Ingestion Job Lifecycle & Status Transition Regression Tests.
 * <p>
 * Verifies the 7 essential job lifecycle scenarios according to QuantArth business semantics:
 * 1. All records accepted -> COMPLETED
 * 2. Some records rejected -> PARTIALLY_COMPLETED
 * 3. Duplicate records -> COMPLETED (idempotent acknowledgments preserve completion)
 * 4. Unverified external refunds -> COMPLETED (quarantined in review storage without failing overall job)
 * 5. Zero-record jobs -> COMPLETED
 * 6. Processing failure -> FAILED with error summary
 * 7. Persisted counters and status verified after repository reload
 */
@SpringBootTest
@DisplayName("Ingestion Job Lifecycle & Status Transition Regression Tests")
class IngestionJobLifecycleTest {

    @Autowired
    private IngestionJobJpaRepository jobRepository;

    @Autowired
    private IngestionJobRecordJpaRepository recordRepository;

    @Autowired
    private IngestionJobService jobService;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        recordRepository.deleteAll();
        jobRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("Lifecycle 1: All records accepted results in COMPLETED status")
    void testAllRecordsAcceptedResultsInCompleted() {
        String tenantId = "tenant-all-acc";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.JSON, "GATEWAY", "clean.json", "hash-clean", null, null, 1, 100);

        jobService.incrementCounters(job.getJobId(), 100, 100, 0, 0, 0, 100);
        jobService.completeJob(job.getJobId(), null);

        IngestionJobJpaEntity reloaded = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.getTotalRecords()).isEqualTo(100);
        assertThat(reloaded.getProcessedRecords()).isEqualTo(100);
        assertThat(reloaded.getAcceptedRecords()).isEqualTo(100);
        assertThat(reloaded.getRejectedRecords()).isEqualTo(0);
        assertThat(reloaded.getUnverifiedRecords()).isEqualTo(0);
        assertThat(reloaded.getDuplicateRecords()).isEqualTo(0);
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Lifecycle 2: Some records rejected results in PARTIALLY_COMPLETED status")
    void testSomeRecordsRejectedResultsInPartiallyCompleted() {
        String tenantId = "tenant-part-rej";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "BANK", "statement.csv", "hash-part", null, null, 1, 100);

        // 100 processed: 92 accepted, 5 unverified, 3 rejected
        jobService.incrementCounters(job.getJobId(), 100, 92, 5, 3, 0, 100);
        jobService.completeJob(job.getJobId(), "3 items quarantined due to mapping errors");

        IngestionJobJpaEntity reloaded = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);
        assertThat(reloaded.getTotalRecords()).isEqualTo(100);
        assertThat(reloaded.getAcceptedRecords()).isEqualTo(92);
        assertThat(reloaded.getUnverifiedRecords()).isEqualTo(5);
        assertThat(reloaded.getRejectedRecords()).isEqualTo(3);
        assertThat(reloaded.getErrorSummary()).isEqualTo("3 items quarantined due to mapping errors");
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Lifecycle 3: Duplicate records acknowledge idempotently with COMPLETED status")
    void testDuplicateRecordsAcknowledgeIdempotentlyWithCompletedStatus() {
        String tenantId = "tenant-dup-acc";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.JSON, "APP", "stream.json", "hash-dup", null, null, 1, 50);

        // 50 processed: 35 newly accepted, 15 duplicate recognized, 0 rejected
        jobService.incrementCounters(job.getJobId(), 50, 35, 0, 0, 15, 50);
        jobService.completeJob(job.getJobId(), null);

        IngestionJobJpaEntity reloaded = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.getAcceptedRecords()).isEqualTo(35);
        assertThat(reloaded.getDuplicateRecords()).isEqualTo(15);
        assertThat(reloaded.getRejectedRecords()).isEqualTo(0);
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Lifecycle 4: Unverified external refunds preserved in review storage with COMPLETED job status")
    void testUnverifiedExternalRefundsPreserveCompletedStatus() {
        String tenantId = "tenant-unv-acc";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "GATEWAY", "refunds.csv", "hash-unv", null, null, 1, 40);

        // 40 processed: 30 accepted, 10 unverified external refunds, 0 rejected
        jobService.incrementCounters(job.getJobId(), 40, 30, 10, 0, 0, 40);
        jobService.completeJob(job.getJobId(), null);

        IngestionJobJpaEntity reloaded = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.getAcceptedRecords()).isEqualTo(30);
        assertThat(reloaded.getUnverifiedRecords()).isEqualTo(10);
        assertThat(reloaded.getRejectedRecords()).isEqualTo(0);
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Lifecycle 5: Zero-record job completes safely with COMPLETED status")
    void testZeroRecordJobCompletesSafely() {
        String tenantId = "tenant-zero-acc";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "BANK", "empty.csv", "hash-empty", null, null, 1, 0);

        jobService.completeJob(job.getJobId(), null);

        IngestionJobJpaEntity reloaded = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(reloaded.getTotalRecords()).isEqualTo(0);
        assertThat(reloaded.getProcessedRecords()).isEqualTo(0);
        assertThat(reloaded.getAcceptedRecords()).isEqualTo(0);
        assertThat(reloaded.getRejectedRecords()).isEqualTo(0);
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Lifecycle 6: Processing failure marks job FAILED with error summary")
    void testProcessingFailureMarksJobFailed() {
        String tenantId = "tenant-fail-acc";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "BANK", "broken.csv", "hash-fail", null, null, 1, 80);

        jobService.failJob(job.getJobId(), "Fatal storage exception: file truncated");

        IngestionJobJpaEntity reloaded = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(reloaded.getErrorSummary()).isEqualTo("Fatal storage exception: file truncated");
        assertThat(reloaded.getCompletedAt()).isNotNull();
    }

    @Test
    @DisplayName("Lifecycle 7: Persisted counters and status verified after repository reload")
    void testPersistedCountersAndStatusVerifiedAfterReload() {
        String tenantId = "tenant-reload-acc";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "BANK", "audit.csv", "hash-audit", null, null, 1, 100);

        // Step 1: Chunk 1 increment
        jobService.incrementCounters(job.getJobId(), 50, 40, 5, 4, 1, 50);
        IngestionJobJpaEntity step1 = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(step1.getProcessedRecords()).isEqualTo(50);
        assertThat(step1.getAcceptedRecords()).isEqualTo(40);
        assertThat(step1.getUnverifiedRecords()).isEqualTo(5);
        assertThat(step1.getRejectedRecords()).isEqualTo(4);
        assertThat(step1.getDuplicateRecords()).isEqualTo(1);
        assertThat(step1.getLastProcessedRow()).isEqualTo(50);

        // Step 2: Chunk 2 increment
        jobService.incrementCounters(job.getJobId(), 50, 45, 2, 2, 1, 100);
        IngestionJobJpaEntity step2 = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(step2.getProcessedRecords()).isEqualTo(100);
        assertThat(step2.getAcceptedRecords()).isEqualTo(85);
        assertThat(step2.getUnverifiedRecords()).isEqualTo(7);
        assertThat(step2.getRejectedRecords()).isEqualTo(6);
        assertThat(step2.getDuplicateRecords()).isEqualTo(2);
        assertThat(step2.getLastProcessedRow()).isEqualTo(100);

        // Complete job
        jobService.completeJob(job.getJobId(), "6 rejected records during processing");

        // Fresh reload
        IngestionJobJpaEntity finalReload = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(finalReload.getStatus()).isEqualTo(JobStatus.PARTIALLY_COMPLETED);
        assertThat(finalReload.getTotalRecords()).isEqualTo(100);
        assertThat(finalReload.getProcessedRecords()).isEqualTo(100);
        assertThat(finalReload.getAcceptedRecords()).isEqualTo(85);
        assertThat(finalReload.getUnverifiedRecords()).isEqualTo(7);
        assertThat(finalReload.getRejectedRecords()).isEqualTo(6);
        assertThat(finalReload.getDuplicateRecords()).isEqualTo(2);
        assertThat(finalReload.getErrorSummary()).isEqualTo("6 rejected records during processing");
        assertThat(finalReload.getCompletedAt()).isNotNull();
    }
}
