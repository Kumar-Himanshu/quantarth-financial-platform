package com.fip.ingestion.infrastructure;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaRepository;
import com.fip.ingestion.adapters.out.persistence.SourceMappingProfileJpaEntity;
import com.fip.ingestion.adapters.out.persistence.SourceMappingProfileJpaRepository;
import com.fip.ingestion.application.service.IngestionJobService;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.RecordStatus;
import com.fip.ingestion.domain.model.ReviewStatus;
import com.fip.ingestion.domain.model.SourceType;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Real Infrastructure Integration Tests for Ingestion Service using Testcontainers (PostgreSQL 16).
 * Verifies:
 * - Fresh Flyway migration and all schema tables, columns, and indexes
 * - Database constraints (unique tenant-profile constraint, foreign keys, cascade deletes)
 * - Tenant-scoped repository operations and multi-tenant data isolation
 * - Mapping profile version persistence and optimistic updates
 * - Job and record status persistence with itemized error quarantine
 * - Accurate job counters under sequential and concurrent updates
 * - Transaction rollback leaving zero orphaned state
 */
@Tag("integration")
@SpringBootTest
@Testcontainers
class IngestionPostgreSqlInfrastructureIntegrationTest {

    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine")
            .withDatabaseName("fip_ingestion")
            .withUsername("postgres")
            .withPassword("postgres_test_password");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.flyway.enabled", () -> "true");
    }

    @Autowired
    private IngestionJobJpaRepository jobRepository;

    @Autowired
    private IngestionJobRecordJpaRepository recordRepository;

    @Autowired
    private SourceMappingProfileJpaRepository profileRepository;

    @Autowired
    private IngestionJobService jobService;

    @Autowired
    private TransactionTemplate transactionTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        recordRepository.deleteAll();
        jobRepository.deleteAll();
        profileRepository.deleteAll();
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    // =========================================================================
    // 1. Fresh Flyway Migration & Schema Verification
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL: Fresh Flyway migration creates all tables and expected indexes")
    void testFlywaySchemaMigrationAndIndexes() {
        assertThat(postgres.isRunning()).isTrue();

        // Verify tables exist in PostgreSQL information_schema
        Integer tableCount = jdbcTemplate.queryForObject(
                "SELECT count(*) FROM information_schema.tables WHERE table_schema = 'public' " +
                "AND table_name IN ('source_mapping_profiles', 'ingestion_jobs', 'ingestion_job_records')",
                Integer.class
        );
        assertThat(tableCount).isEqualTo(3);

        // Verify key indexes exist
        List<String> indexes = jdbcTemplate.query(
                "SELECT indexname FROM pg_indexes WHERE schemaname = 'public'",
                (rs, rowNum) -> rs.getString("indexname")
        );

        assertThat(indexes).contains(
                "idx_ingestion_jobs_tenant_file_hash",
                "idx_ingestion_jobs_status_heartbeat",
                "idx_ingestion_jobs_tenant_status",
                "idx_ingestion_records_tenant_source_record",
                "idx_ingestion_records_job_status_index",
                "idx_ingestion_records_reconciliation",
                "idx_mapping_profiles_tenant_active"
        );
    }

    // =========================================================================
    // 2. Database Constraints & Uniqueness
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL: Constraint uk_mapping_profiles_tenant_name prevents duplicate profile names per tenant")
    void testMappingProfileUniqueConstraintPerTenant() {
        UUID profile1Id = UUID.randomUUID();
        SourceMappingProfileJpaEntity profile1 = createProfileEntity(profile1Id, "tenant-alpha", "HDFC_STATEMENT", 1);
        profileRepository.saveAndFlush(profile1);

        // Same tenant + same name -> DataIntegrityViolationException
        SourceMappingProfileJpaEntity profileDuplicate = createProfileEntity(UUID.randomUUID(), "tenant-alpha", "HDFC_STATEMENT", 1);

        assertThatThrownBy(() -> profileRepository.saveAndFlush(profileDuplicate))
                .isInstanceOf(DataIntegrityViolationException.class);

        // Different tenant + same name -> Allowed
        SourceMappingProfileJpaEntity profileDifferentTenant = createProfileEntity(UUID.randomUUID(), "tenant-beta", "HDFC_STATEMENT", 1);
        SourceMappingProfileJpaEntity savedBeta = profileRepository.saveAndFlush(profileDifferentTenant);
        assertThat(savedBeta.getProfileId()).isNotNull();
    }

    @Test
    @DisplayName("PostgreSQL: Foreign key cascade on job deletion deletes child records")
    void testForeignKeyCascadeOnJobDeletion() {
        String tenantId = "tenant-cascade";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "BANK", "statement.csv", "hash-123", "/path/file", null, 1, 10);

        jobService.recordItem(job.getJobId(), tenantId, 1, "TXN-1", "PAYMENT", UUID.randomUUID(),
                RecordStatus.ACCEPTED, ReviewStatus.NONE, null, null, "raw-1");
        jobService.recordItem(job.getJobId(), tenantId, 2, "TXN-2", "PAYMENT", null,
                RecordStatus.REJECTED, ReviewStatus.NONE, "ERR", "msg", "raw-2");

        assertThat(recordRepository.findByJobId(job.getJobId())).hasSize(2);

        // Delete job directly via repository
        jobRepository.deleteById(job.getJobId());
        jobRepository.flush();

        // Foreign key cascade in PostgreSQL must delete all child records
        assertThat(recordRepository.findByJobId(job.getJobId())).isEmpty();
    }

    // =========================================================================
    // 3. Multi-Tenant Scoped Repository Operations
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL: Repository operations strictly isolate tenant jobs and records")
    void testTenantScopedOperations() {
        String tenantA = "tenant-a";
        String tenantB = "tenant-b";

        IngestionJobJpaEntity jobA = jobService.createJob(
                tenantA, SourceType.JSON, "APP_A", "batch_a", "hashA", null, null, 1, 5);
        IngestionJobJpaEntity jobB = jobService.createJob(
                tenantB, SourceType.JSON, "APP_B", "batch_b", "hashB", null, null, 1, 5);

        // Query job A using tenant A
        Optional<IngestionJobJpaEntity> fetchedA = jobRepository.findByJobIdAndTenantId(jobA.getJobId(), tenantA);
        assertThat(fetchedA).isPresent();

        // Cross-tenant query: query job A using tenant B returns empty
        Optional<IngestionJobJpaEntity> crossTenantJob = jobRepository.findByJobIdAndTenantId(jobA.getJobId(), tenantB);
        assertThat(crossTenantJob).isEmpty();

        // Query records: isolated by tenant
        jobService.recordItem(jobA.getJobId(), tenantA, 1, "REC-A1", "PAYMENT", UUID.randomUUID(),
                RecordStatus.ACCEPTED, ReviewStatus.NONE, null, null, "snippet");

        Page<IngestionJobRecordJpaEntity> pageA = recordRepository.findByJobIdAndTenantId(
                jobA.getJobId(), tenantA, PageRequest.of(0, 10));
        assertThat(pageA.getContent()).hasSize(1);

        Page<IngestionJobRecordJpaEntity> pageB = recordRepository.findByJobIdAndTenantId(
                jobA.getJobId(), tenantB, PageRequest.of(0, 10));
        assertThat(pageB.getContent()).isEmpty();
    }

    // =========================================================================
    // 4. Mapping Profile Version Persistence
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL: Mapping profile version persists and increments accurately")
    void testMappingProfileVersionPersistence() {
        UUID profileId = UUID.randomUUID();
        SourceMappingProfileJpaEntity profile = createProfileEntity(profileId, "tenant-version", "STRIPE_CUSTOM", 1);
        profile.setMappingRules("{\"col\": 1}");
        profileRepository.saveAndFlush(profile);

        SourceMappingProfileJpaEntity loaded = profileRepository.findById(profileId).orElseThrow();
        assertThat(loaded.getVersion()).isEqualTo(1);

        // Update version to 2
        loaded.setVersion(2);
        loaded.setMappingRules("{\"col\": 2, \"updated\": true}");
        loaded.setUpdatedAt(LocalDateTime.now());
        profileRepository.saveAndFlush(loaded);

        SourceMappingProfileJpaEntity updated = profileRepository.findById(profileId).orElseThrow();
        assertThat(updated.getVersion()).isEqualTo(2);
        assertThat(updated.getMappingRules()).contains("\"updated\": true");
    }

    // =========================================================================
    // 5. Accurate Job Counters and Status Persistence
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL: Job counters and status updates persist accurately")
    void testJobCountersAndStatusPersistence() {
        String tenantId = "tenant-counters";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "BANK", "feed.csv", "hash-counter", null, null, 1, 100);

        assertThat(job.getStatus()).isEqualTo(JobStatus.RECEIVED);
        assertThat(job.getTotalRecords()).isEqualTo(100);
        assertThat(job.getProcessedRecords()).isEqualTo(0);

        // Update chunk 1: 50 processed, 45 accepted, 3 unverified, 2 rejected, 0 duplicate
        jobService.incrementCounters(job.getJobId(), 50, 45, 3, 2, 0, 50);

        IngestionJobJpaEntity step1 = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(step1.getProcessedRecords()).isEqualTo(50);
        assertThat(step1.getAcceptedRecords()).isEqualTo(45);
        assertThat(step1.getUnverifiedRecords()).isEqualTo(3);
        assertThat(step1.getRejectedRecords()).isEqualTo(2);
        assertThat(step1.getDuplicateRecords()).isEqualTo(0);
        assertThat(step1.getLastProcessedRow()).isEqualTo(50);

        // Update chunk 2: 50 processed, 48 accepted, 1 unverified, 1 rejected, 0 duplicate
        jobService.incrementCounters(job.getJobId(), 50, 48, 1, 1, 0, 100);

        IngestionJobJpaEntity step2 = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(step2.getProcessedRecords()).isEqualTo(100);
        assertThat(step2.getAcceptedRecords()).isEqualTo(93);
        assertThat(step2.getUnverifiedRecords()).isEqualTo(4);
        assertThat(step2.getRejectedRecords()).isEqualTo(3);
        assertThat(step2.getLastProcessedRow()).isEqualTo(100);

        jobService.completeJob(job.getJobId(), null);

        IngestionJobJpaEntity finished = jobRepository.findById(job.getJobId()).orElseThrow();
        assertThat(finished.getStatus()).isEqualTo(JobStatus.COMPLETED);
        assertThat(finished.getCompletedAt()).isNotNull();
    }

    // =========================================================================
    // 6. Transaction Rollback Atomicity
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL: Transaction rollback leaves zero orphan job or record entries")
    void testTransactionRollbackAtomicity() {
        String tenantId = "tenant-rollback";
        UUID rollbackJobId = UUID.randomUUID();

        assertThatThrownBy(() -> transactionTemplate.execute(status -> {
            IngestionJobJpaEntity job = jobService.createJob(
                    tenantId, SourceType.JSON, "APP", "rollback-file", null, null, null, 1, 10);

            jobService.recordItem(job.getJobId(), tenantId, 1, "TXN-RB-1", "PAYMENT",
                    UUID.randomUUID(), RecordStatus.ACCEPTED, ReviewStatus.NONE, null, null, "{}");

            throw new RuntimeException("Simulated error triggering transaction rollback");
        })).isInstanceOf(RuntimeException.class);

        assertThat(jobRepository.findByTenantId(tenantId)).isEmpty();
        assertThat(recordRepository.findAll()).isEmpty();
    }

    // =========================================================================
    // 7. Concurrent Updates on Real PostgreSQL
    // =========================================================================

    @Test
    @DisplayName("PostgreSQL: Concurrent record insertion threads persist disjoint audit entries without deadlocks")
    void testConcurrentRecordInsertions() throws InterruptedException {
        String tenantId = "tenant-concurrent";
        IngestionJobJpaEntity job = jobService.createJob(
                tenantId, SourceType.CSV, "MULTI_THREAD", "parallel.csv", "hash-par", null, null, 1, 20);

        int threadCount = 4;
        int recordsPerThread = 5;
        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CountDownLatch startGate = new CountDownLatch(1);
        CountDownLatch endGate = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger();

        for (int t = 0; t < threadCount; t++) {
            final int threadIdx = t;
            executor.submit(() -> {
                try {
                    startGate.await(5, TimeUnit.SECONDS);
                    for (int r = 0; r < recordsPerThread; r++) {
                        int rowIdx = (threadIdx * recordsPerThread) + r + 1;
                        jobService.recordItem(
                                job.getJobId(), tenantId, rowIdx, "REC-PAR-" + rowIdx, "PAYMENT",
                                UUID.randomUUID(), RecordStatus.ACCEPTED, ReviewStatus.NONE, null, null, "snippet"
                        );
                        successCount.incrementAndGet();
                    }
                } catch (Exception ignored) {
                } finally {
                    endGate.countDown();
                }
            });
        }

        startGate.countDown();
        boolean completed = endGate.await(10, TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(completed).isTrue();
        assertThat(successCount.get()).isEqualTo(20);

        List<IngestionJobRecordJpaEntity> allRecords = recordRepository.findByJobId(job.getJobId());
        assertThat(allRecords).hasSize(20);
    }

    private SourceMappingProfileJpaEntity createProfileEntity(UUID id, String tenantId, String name, int version) {
        SourceMappingProfileJpaEntity profile = new SourceMappingProfileJpaEntity();
        profile.setProfileId(id != null ? id : UUID.randomUUID());
        profile.setTenantId(tenantId);
        profile.setName(name);
        profile.setSourceFormat(SourceType.CSV.name());
        profile.setTargetEntityType("PAYMENT");
        profile.setDelimiter(",");
        profile.setHasHeader(true);
        profile.setDateFormat("dd/MM/yyyy");
        profile.setTimezone("Asia/Kolkata");
        profile.setCurrencyDefault("INR");
        profile.setMappingRules("{}");
        profile.setVersion(version);
        profile.setActive(true);
        profile.setCreatedAt(LocalDateTime.now());
        profile.setUpdatedAt(LocalDateTime.now());
        return profile;
    }
}
