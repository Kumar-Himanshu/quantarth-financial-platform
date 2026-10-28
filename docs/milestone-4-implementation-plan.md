# QuantArth Financial Intelligence Platform (FIP)
## Milestone 4 Implementation Roadmap: Financial Data Ingestion (Refined)

### Executive Roadmap Overview

Milestone 4 delivers an enterprise-grade, multi-tenant financial data ingestion pipeline for QuantArth. The implementation is phased into four sequential, well-defined milestones:

```
[ M4A: Architecture & Design (Refined & Approved) ]
                       |
                       v
[ M4B: Service Bootstrap, Bounded Staging, Streaming Adapters, & Resumable Parsers ]
                       |
                       v
[ M4C: 3-Stage Normalization, Ingestion Job Tracking, Refund Review Queue, & M3B Integration ]
                       |
                       v
[ M4D: Testcontainers Verification, Crash/Restart Recovery Tests, & CI Pipeline ]
```

---

### Phase M4A — Architecture and Technical Design (Current Phase)

- **Scope**:
  - Author comprehensive architectural specifications, REST API contracts, database schemas, and implementation roadmap.
  - Refine decisions for external refunds, upload durability, deduplication, database simplicity, and service-to-service authentication.
  - Define formal Architecture Decision Records (ADRs 015–021).
  - Perform repository assessment and gap analysis against completed M1–M3B infrastructure.
- **Files / Artifacts Refined**:
  - `docs/milestone-4a-ingestion-architecture.md`
  - `docs/milestone-4a-ingestion-api-spec.md`
  - `docs/milestone-4a-ingestion-database-design.md`
  - `docs/milestone-4-implementation-plan.md`
  - `docs/adr.md` (ADRs 015 to 021)
- **Dependencies**: Completed Milestones M1, M2A, M2B, M3A, M3B.
- **Acceptance Criteria**:
  - Full architectural approval by Principal Architect / Founder.
  - Zero premature implementation code, schema migrations, or new runtime dependencies.
- **Estimated Complexity**: Low (pure design and specification).
- **Major Risks**: Misalignment between external source variety and strict canonical models (mitigated by declarative mapping profiles).

---

### Phase M4B — Service Bootstrap, Bounded Staging, Streaming Adapters & Resumable Parsers

- **Scope**:
  - Create the new Gradle module `:ingestion-service` with Spring Boot 3.3.1, Java 21, and Hexagonal Architecture.
  - Configure `settings.gradle`, `build.gradle`, and `deployment/docker/init-databases.sql` for database `fip_ingestion`.
  - Implement Flyway migration `V1__init_ingestion_schema.sql` (creating `source_mapping_profiles`, `ingestion_jobs`, `ingestion_job_records` without partitioning).
  - Set up bounded local staging directory `/var/data/ingestion/staging` with permissions `chmod 0600` and volume mounting.
  - Implement streaming RFC 4180 CSV parser and chunked JSON parser with resumability tracking (`last_processed_row`).
  - Implement `IngestionRestartRecoveryRunner` scanning stuck jobs on startup.
  - Implement Declarative Mapping Profile engine (supporting date parsing, split DR/CR columns, static defaults, and currency scales).
  - Implement Gateway routes for `/api/v1/ingestion/**` with HMAC validation filter.
- **Files / Modules to Create or Modify**:
  - `settings.gradle`: Include `'ingestion-service'`
  - `ingestion-service/build.gradle`: Dependencies (`shared-contracts`, `common-library`, Spring Web, Spring Data JPA, Flyway, Apache Commons CSV).
  - `ingestion-service/src/main/resources/application.yml`
  - `ingestion-service/src/main/resources/db/migration/V1__init_ingestion_schema.sql`
  - `ingestion-service/src/main/java/com/fip/ingestion/...`:
    - `adapters/in/web/IngestionController.java`
    - `adapters/in/web/MappingProfileController.java`
    - `service/staging/StagingStorageService.java`
    - `service/recovery/IngestionRestartRecoveryRunner.java`
    - `service/parser/CsvStreamAdapter.java`
    - `service/parser/JsonStreamAdapter.java`
    - `service/mapping/DeclarativeMappingEngine.java`
    - `domain/model/SourceRecord.java`, `domain/model/MappingProfile.java`
  - `api-gateway/src/main/resources/application.yml`: Add route for `ingestion-service` (`:8084`).
  - `docker-compose.yml`: Add `ingestion-service` container definition and `ingestion_staging` named volume.
- **Dependencies**: M4A design approval.
- **Acceptance Criteria**:
  - `ingestion-service` starts cleanly and passes Flyway schema initialization.
  - Gateway routes requests to `/api/v1/ingestion/**` with verified JWT and HMAC signatures.
  - Mapping profiles can be created, updated, and retrieved per tenant.
  - CSV files up to 25 MB stream to staging disk and parse with constant memory overhead.
- **Testing Requirements**:
  - Unit tests for CSV and JSON stream parsers with malformed, empty, and edge-case inputs.
  - Unit tests for `DeclarativeMappingEngine` covering HDFC, ICICI, and Razorpay sample data.
  - Unit tests for `StagingStorageService` verifying bounded storage quotas and secure file permissions.
- **Estimated Complexity**: Medium.
- **Major Risks**: Heap exhaustion during large CSV uploads (mitigated by row-by-row streaming parsers).

---

### Phase M4C — 3-Stage Normalization, Job Tracking, Refund Review Queue, and M3B Integration

- **Scope**:
  - Implement 3-Stage Validation Pipeline:
    - Stage 1: Structural verification.
    - Stage 2: Canonical financial domain invariant validation via `FinancialModelValidator`.
    - Stage 3: Business consistency, multi-tier deduplication, and external refund verification.
  - Implement Ingestion Job Tracker (`IngestionJobService`) maintaining persistent job counters (`total`, `processed`, `accepted`, `rejected`, `duplicate`, `unverified`) with atomic SQL increments.
  - Implement `EventEngineRestClientAdapter` using `RequestHmacSigner` (`subject: "service:ingestion-service"`, `roles: "ROLE_INTERNAL_SERVICE"`) calling `event-engine`'s `POST /api/v1/events/canonical/batch`.
  - Implement batch fallback: automatically dispatch row-by-row on chunk failure to isolate invalid records.
  - Implement Ingestion-Side Holding for Unverified External Refunds (`ingestion_job_records` status `UNVERIFIED_EXTERNAL`, review `PENDING_RECONCILIATION`).
  - Implement Operator Reconciliation endpoints (`POST /api/v1/ingestion/reconciliation/refunds/{recordId}/approve`).
  - Implement `IngestionDataPruningScheduler` for configurable off-peak batch deletion (1000 rows/batch).
- **Files to Create or Modify**:
  - `ingestion-service/src/main/java/com/fip/ingestion/...`:
    - `service/validation/IngestionValidationPipeline.java`
    - `service/reconciliation/ExternalRefundReconciliationService.java`
    - `service/job/IngestionJobService.java`
    - `service/dedup/MultiTierDeduplicationService.java`
    - `service/retention/IngestionDataPruningScheduler.java`
    - `ports/out/EventEngineClientPort.java`
    - `adapters/out/rest/EventEngineRestClientAdapter.java`
    - `adapters/in/web/ReconciliationController.java`
    - `adapters/out/persistence/IngestionJobJpaEntity.java`, `IngestionJobRecordJpaEntity.java`
    - `adapters/out/persistence/IngestionJobJpaRepository.java`, `IngestionJobRecordJpaRepository.java`
- **Dependencies**: M4B completion, M3B `event-engine` REST endpoints.
- **Acceptance Criteria**:
  - Heterogeneous source records normalize deterministically into `CanonicalEvent` instances.
  - Valid batches persist into `event-engine` and emit outbox records.
  - External refunds without local payment ancestry are held in `UNVERIFIED_EXTERNAL` state without polluting `financial_events`.
  - Duplicate uploads (file hash or record ID) are handled advisory-first, allowing `force=true` overrides.
  - Partial batch failures isolate invalid records while committing valid rows.
- **Testing Requirements**:
  - Unit tests for all 3 validation stages.
  - Unit tests for external refund reconciliation transitions.
  - Mock-backed tests for `EventEngineRestClientAdapter` with HMAC signature validation.
  - Unit tests for batch fallback mechanism.
- **Estimated Complexity**: High (fintech domain rules, concurrency, and chunking transactions).
- **Major Risks**: Inconsistent job counter rollbacks under network failures (mitigated by chunked transactional commits).

---

### Phase M4D — End-to-End Testing, Hardening, and CI/CD Verification

- **Scope**:
  - Implement comprehensive Testcontainers integration tests in `ingestion-service` running against real PostgreSQL 16 and RabbitMQ 3.13.
  - Implement end-to-end integration tests:
    - CSV upload -> staging -> `ingestion-service` -> `event-engine` -> PostgreSQL `financial_events` -> Transactional Outbox -> RabbitMQ.
    - Application restart recovery: simulate mid-flight crash, verify resumption from `last_processed_row + 1` with zero duplicate events.
    - Duplicate CSV upload and re-processing with `force=true`.
    - Unverified external refund ingestion and operator reconciliation approval.
  - Perform security hardening (CSV formula injection sanitization, gateway HMAC spoofing tests).
  - Update GitHub Actions CI workflow to compile and test `:ingestion-service`.
  - Author M4 verification report and complete documentation package.
- **Files to Create or Modify**:
  - `ingestion-service/src/test/java/com/fip/ingestion/...`:
    - `infrastructure/IngestionInfrastructureIntegrationTest.java`
    - `service/IngestionEndToEndIntegrationTest.java`
    - `service/IngestionRestartRecoveryIntegrationTest.java`
    - `security/IngestionSecurityTest.java`
  - `.github/workflows/ci.yml`: Add `ingestion-service` build and test matrix.
  - `README.md`: Update architectural diagrams, service port matrix, and test metrics.
  - `docs/milestone-4-verification-report.md`
- **Dependencies**: M4C completion.
- **Acceptance Criteria**:
  - All unit tests pass across all 7 modules.
  - All Testcontainers integration tests pass against live PostgreSQL and RabbitMQ containers.
  - GitHub Actions runs clean across all jobs (`java-unit-tests`, `python-fastapi-tests`, `infrastructure-integration-tests`).
  - Zero regression in M1–M3B test suites.
- **Estimated Complexity**: Medium.
- **Major Risks**: Testcontainers Docker timeout on CI runners (mitigated by reusing container instances where appropriate).
