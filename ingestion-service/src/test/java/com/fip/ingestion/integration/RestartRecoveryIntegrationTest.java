package com.fip.ingestion.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.eventengine.EventEngineClient;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaRepository;
import com.fip.ingestion.adapters.out.storage.StagingStorageService;
import com.fip.ingestion.application.service.CsvIngestionService;
import com.fip.ingestion.application.service.IngestionJobService;
import com.fip.ingestion.application.service.IngestionRestartRecoveryRunner;
import com.fip.ingestion.application.service.JsonIngestionService;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.SourceType;
import com.fip.shared.events.CanonicalEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;

/**
 * Milestone 4B Section 4: Restart Recovery Verification Tests.
 * <p>
 * Verifies:
 * - Every API response returning 202 Accepted corresponds to work that survives an application restart
 * - Simulates interrupted processing of CSV and asynchronous JSON jobs
 * - Confirms IngestionRestartRecoveryRunner scans and resumes safely from last_processed_row
 * - Verifies final counters match total records
 * - Confirms zero duplicate canonical events forwarded across retries
 */
@SpringBootTest
@DisplayName("Restart Recovery & Durability Verification Tests")
class RestartRecoveryIntegrationTest {

    @Autowired
    private IngestionJobJpaRepository jobRepository;

    @Autowired
    private IngestionJobRecordJpaRepository recordRepository;

    @Autowired
    private IngestionJobService jobService;

    @Autowired
    private CsvIngestionService csvIngestionService;

    @Autowired
    private JsonIngestionService jsonIngestionService;

    @Autowired
    private IngestionRestartRecoveryRunner recoveryRunner;

    @Autowired
    private StagingStorageService stagingStorageService;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventEngineClient eventEngineClient;

    @TempDir
    Path tempDir;

    private final List<String> forwardedEventIds = new CopyOnWriteArrayList<>();

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        recordRepository.deleteAll();
        jobRepository.deleteAll();
        forwardedEventIds.clear();

        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            for (CanonicalEvent e : events) {
                forwardedEventIds.add(e.entityId());
            }
            return new EventEngineClient.BatchForwardResult(events, Collections.emptyList());
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("4.1 CSV Restart Recovery: Interrupted job resumes from last_processed_row with zero duplicate events")
    void testCsvInterruptedJobResumesFromCheckpoint() throws Exception {
        String tenantId = "tenant-recovery-csv";
        UUID jobId = UUID.randomUUID();

        // 1. Prepare 5 CSV records
        String csv = """
                id,amount,currency,payerName,payeeName,paymentMethod
                TXN-REC-1,100.00,INR,P1,M1,UPI
                TXN-REC-2,200.00,INR,P2,M2,UPI
                TXN-REC-3,300.00,INR,P3,M3,UPI
                TXN-REC-4,400.00,INR,P4,M4,UPI
                TXN-REC-5,500.00,INR,P5,M5,UPI
                """;

        // 2. Persist recoverable input to staging
        StagingStorageService.StagingResult staged = stagingStorageService.stageFile(
                tenantId, jobId, new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)), "csv");

        // 3. Create job in PROCESSING state, simulating a crash after row 2
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "BANK_FEED", "statement.csv",
                staged.fileHash(), staged.stagingFilePath(), null, 1, 5);

        job.setStatus(JobStatus.PROCESSING);
        job.setProcessedRecords(2);
        job.setAcceptedRecords(2);
        job.setLastProcessedRow(2);
        job.setHeartbeatAt(LocalDateTime.now().minusMinutes(10)); // Stale heartbeat > 5 mins
        jobRepository.saveAndFlush(job);

        // Pre-populate records 1 and 2 as already committed
        jobService.recordItem(job.getJobId(), tenantId, 1, "TXN-REC-1", "PAYMENT", UUID.randomUUID(),
                com.fip.ingestion.domain.model.RecordStatus.ACCEPTED, com.fip.ingestion.domain.model.ReviewStatus.NONE, null, null, "snippet1");
        jobService.recordItem(job.getJobId(), tenantId, 2, "TXN-REC-2", "PAYMENT", UUID.randomUUID(),
                com.fip.ingestion.domain.model.RecordStatus.ACCEPTED, com.fip.ingestion.domain.model.ReviewStatus.NONE, null, null, "snippet2");

        forwardedEventIds.add("TXN-REC-1");
        forwardedEventIds.add("TXN-REC-2");

        // 4. Trigger IngestionRestartRecoveryRunner (simulates application restart)
        recoveryRunner.run(null);

        // 5. Verify final job state
        IngestionJobJpaEntity recovered = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(recovered.getAcceptedRecords()).isEqualTo(5);
        assertThat(recovered.getLastProcessedRow()).isEqualTo(5);
        assertThat(recovered.getRetryCount()).isGreaterThanOrEqualTo(1);

        // 6. Verify zero duplicate events forwarded: rows 1 and 2 were NOT re-forwarded
        // The list should contain exactly: TXN-REC-1, TXN-REC-2, then TXN-REC-3, TXN-REC-4, TXN-REC-5
        assertThat(forwardedEventIds).containsExactly(
                "TXN-REC-1", "TXN-REC-2", "TXN-REC-3", "TXN-REC-4", "TXN-REC-5"
        );

        // 7. Staged file securely unlinked upon completed recovery
        assertThat(new File(staged.stagingFilePath()).exists()).isFalse();
    }

    @Test
    @DisplayName("4.2 Asynchronous JSON Restart Recovery: Interrupted staged JSON job resumes safely without losing jobs")
    void testJsonInterruptedJobResumesFromCheckpoint() throws Exception {
        String tenantId = "tenant-recovery-json";
        UUID jobId = UUID.randomUUID();

        List<Map<String, Object>> records = List.of(
                Map.of("id", "JSON-REC-1", "amount", "150.00", "currency", "INR"),
                Map.of("id", "JSON-REC-2", "amount", "250.00", "currency", "INR"),
                Map.of("id", "JSON-REC-3", "amount", "350.00", "currency", "INR"),
                Map.of("id", "JSON-REC-4", "amount", "450.00", "currency", "INR")
        );

        byte[] payloadBytes = objectMapper.writeValueAsBytes(records);
        StagingStorageService.StagingResult staged = stagingStorageService.stageFile(
                tenantId, jobId, new ByteArrayInputStream(payloadBytes), "json");

        // Simulate crash after row 1
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.JSON, "JSON_API", "payload.json",
                staged.fileHash(), staged.stagingFilePath(), null, 1, 4);

        job.setStatus(JobStatus.PROCESSING);
        job.setProcessedRecords(1);
        job.setAcceptedRecords(1);
        job.setLastProcessedRow(1);
        job.setHeartbeatAt(LocalDateTime.now().minusMinutes(10));
        jobRepository.saveAndFlush(job);

        jobService.recordItem(job.getJobId(), tenantId, 1, "JSON-REC-1", "PAYMENT", UUID.randomUUID(),
                com.fip.ingestion.domain.model.RecordStatus.ACCEPTED, com.fip.ingestion.domain.model.ReviewStatus.NONE, null, null, "snippet1");

        forwardedEventIds.add("JSON-REC-1");

        // Trigger container boot restart recovery
        recoveryRunner.run(null);

        IngestionJobJpaEntity recovered = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(recovered.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(recovered.getAcceptedRecords()).isEqualTo(4);
        assertThat(recovered.getLastProcessedRow()).isEqualTo(4);

        // Zero duplicate events: JSON-REC-1 was not re-forwarded
        assertThat(forwardedEventIds).containsExactly(
                "JSON-REC-1", "JSON-REC-2", "JSON-REC-3", "JSON-REC-4"
        );

        assertThat(new File(staged.stagingFilePath()).exists()).isFalse();
    }
}
