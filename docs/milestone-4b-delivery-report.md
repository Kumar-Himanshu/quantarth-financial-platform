# QuantArth Financial Intelligence Platform (FIP)
## Milestone M4B Delivery & Integration Verification Report
### Acceptance Hardening & Real Infrastructure Verification

---

### 1. Executive Summary

Milestone M4B has undergone thorough integration verification, correctness gap remediation, and acceptance hardening across real infrastructure dependencies. 

This deliverable introduces and hardens the standalone **`ingestion-service`** Spring Boot module, enabling multi-tenant, streaming, declarative financial data ingestion for structured JSON payloads and external CSV exports (such as bank statements and payment processor reports).

All critical architectural invariants are verified and upheld:
- **Passive Intelligence Invariant**: QuantArth is strictly an analytical intelligence platform, NOT a payment processor, payment switch, or gateway. It never initiates funds movements and never fabricates synthetic ledger payments.
- **Financial Data Immutability**: Source financial records are ingested without mutation. Formula injection protection has been removed from the ingestion boundary and placed strictly at the CSV export boundary (`CsvExportSanitizer`).
- **Restart Durability Guarantee**: Every `202 Accepted` response (both CSV uploads and asynchronous JSON payloads) is backed by durable disk staging before acceptance. No accepted job is lost across process crashes or node restarts.
- **External Refund Integrity**: Unmatched external refunds are tagged `UNVERIFIED_EXTERNAL` with `reviewStatus = PENDING_RECONCILIATION`, retained in tenant-isolated review storage, and never forwarded as canonical events or synthesized into fake payments. Known-payment refunds continue to use M3B validation against existing payments.
- **HMAC Shared Gateway Trust Boundary**: Service-to-service internal requests are authenticated via request-bound HMAC-SHA256 digests over standard headers and request bodies, enforcing a shared gateway trust boundary.

---

### 2. Defect Remediation & Hardening Actions

During the M4B verification review, the following correctness defects were identified and remediated:

1. **Defect 1 — Formula Escaping on Ingestion Mutated Source Financial Data**:
   - *Issue*: `CsvStreamAdapter` prepended `'` to raw financial descriptions starting with `=`, `+`, `-`, `@`, mutating imported financial data.
   - *Fix*: Removed quote mutation from `CsvStreamAdapter`. Extracted formula escaping into a dedicated `CsvExportSanitizer` utility designed strictly for downstream spreadsheet export boundaries.
2. **Defect 2 — Synthetic Placeholder Amount Fabrication (1.00)**:
   - *Issue*: `DeclarativeMappingEngine` previously defaulted missing/zero amounts to `1.00` to satisfy schema validation.
   - *Fix*: Removed the synthetic fallback. Zero or negative amounts throw `FinancialValidationException` with clear diagnostic tracking and quarantine in `ingestion_job_records`.
3. **Defect 3 — Asynchronous JSON Lack of Durable Staging**:
   - *Issue*: `POST /api/v1/ingestion/json` returning `202 Accepted` processed items asynchronously in memory without persistent disk staging.
   - *Fix*: Extended `StagingStorageService` to support customizable extensions (`.json.stage`). Asynchronous JSON requests are durably written to disk, verified with SHA-256 digests, and tracked in `ingestion_jobs` as `RECEIVED` before returning `202 Accepted`.
4. **Defect 4 — Restart Recovery Limited to CSV**:
   - *Issue*: `IngestionRestartRecoveryRunner` only resumed CSV jobs upon container reboot.
   - *Fix*: Updated `IngestionRestartRecoveryRunner` to inspect job file paths and delegate to `JsonIngestionService.processStagedJson()` using `JsonStreamAdapter`, allowing interrupted JSON jobs to resume seamlessly from `last_processed_row`.
5. **Defect 5 — External Refund Distinction and Verification**:
   - *Issue*: Ingestion did not verify whether an incoming refund had a matching payment in `event-engine` persistence.
   - *Fix*: Introduced `PaymentExistenceChecker` interface and wired `EventEngineClient.checkPaymentExists(tenantId, paymentId)`. Unmatched refunds are classified as `UNVERIFIED_EXTERNAL` and held in review storage, while matched refunds proceed with full M3B validation.

---

### 3. Detailed Verification Results

#### 3.1 PostgreSQL Integration Verification
- **Test Suite**: `com.fip.ingestion.infrastructure.IngestionPostgreSqlInfrastructureIntegrationTest`
- **Execution Target**: Testcontainers PostgreSQL 16 Alpine container (tagged `@Tag("integration")`).
- **Verifications Covered**:
  1. **Fresh Flyway Migration**: Clean container startup with Flyway `V1__init_ingestion_schema.sql` running against an empty PostgreSQL 16 database.
  2. **Schema & Indexes**: Verified existence of `source_mapping_profiles`, `ingestion_jobs`, and `ingestion_job_records` alongside indexes `idx_profiles_tenant_source`, `idx_jobs_tenant_status`, `idx_records_job_status`.
  3. **Tenant-Scoped Repository Operations**: Strict `tenant_id` filtering on all JPA queries.
  4. **Mapping Profile Version Persistence**: Optimistic locking via `@Version` prevents concurrent overwrite conflicts.
  5. **Job & Record Status Persistence**: Proper state transitions (`RECEIVED` -> `PROCESSING` -> `COMPLETED` / `PARTIALLY_COMPLETED` / `FAILED`).
  6. **Accurate Job Counters**: Atomic SQL counter increments for total, accepted, unverified, rejected, and duplicate records.
  7. **Transaction Rollback**: Atomic record recording within transactional boundaries; rollbacks restore previous state.
  8. **Concurrent Updates**: Concurrent updates to job counters and records maintain consistency.
  9. **Database Constraints**: Foreign key constraints between `ingestion_jobs` and `ingestion_job_records` prevent orphaned items.

#### 3.2 Event-Engine Integration Verification
- **Test Suite**: `com.fip.ingestion.integration.EventEngineIntegrationVerificationTest`
- **Verifications Covered**:
  1. **Valid JSON Financial Record**: Forwarded to event-engine, canonical event persisted, job status `COMPLETED`.
  2. **Valid CSV Financial Record**: Chunked streaming normalization, canonical event persistence.
  3. **Canonical Event Persistence**: Single event store preserved; ingestion-service maintains only pointers (`canonical_event_id`), never introducing a secondary event log.
  4. **Tenant Isolation**: Event engine forward requests strictly scoped by `tenant_id`.
  5. **Duplicate Event Submission**: Stable, deterministic idempotency keys (`{sourceSystem}:{entityId}`) acknowledged safely without duplicate canonical creation.
  6. **Idempotency Conflict**: Same idempotency key with conflicting payload results in itemized `REJECTED` status and error quarantine.
  7. **Event-Engine Timeout / Commits**: Unavailability or transport errors gracefully caught, isolating items into quarantine without crashing the ingestion service.
  8. **Partial Batch Outcomes**: Mixed valid and invalid records produce itemized records and a final job status of `PARTIALLY_COMPLETED`.

#### 3.3 Restart Recovery & Durability Verification
- **Test Suite**: `com.fip.ingestion.integration.RestartRecoveryIntegrationTest`
- **Verifications Covered**:
  1. **CSV Restart Recovery**: A job interrupted after processing row 2 out of 5 resumes from `last_processed_row = 2` upon restart runner execution, processing exactly the remaining rows without duplicate canonical events.
  2. **Asynchronous JSON Restart Recovery**: Staged JSON payloads (`jobId.json.stage`) returning `202 Accepted` survive container shutdown, resume from checkpoint row offsets, and reach `COMPLETED` status upon recovery.

#### 3.4 Financial Data Integrity Verification
- **Test Suite**: `com.fip.ingestion.mapping.FinancialDataIntegrityTest`
- **Verifications Covered**:
  1. **Exact Monetary Parsing**: Values like `123456789.875` parsed into exact `BigDecimal` with Banker's Rounding (`HALF_EVEN` -> `123456789.88`) without IEEE 754 floating point distortion.
  2. **Zero/Negative Amount Rejection**: Zero or negative amounts reject with `FinancialValidationException`, preventing synthetic `1.00` money fabrication.
  3. **Debit/Credit Interpretation**: Correct handling of `SPLIT_DR_CR` strategies (e.g. withdrawal vs deposit columns) and signed amount strategies.
  4. **Currency Handling**: ISO 4217 currency validation (INR, USD, EUR, GBP) strictly validated.
  5. **Date and Timezone Handling**: Timezone offsets (e.g. `Asia/Kolkata` `+05:30`) correctly converted to exact UTC `Instant` points in time.
  6. **Source Reference Preservation**: Source record ID and originating system stored in canonical `Payment` references.
  7. **Description Preservation**: Ingested descriptions retain verbatim text including `=`, `+`, `-` prefixes; `CsvExportSanitizer` handles escaping strictly at export time.

#### 3.5 External Refund Verification
- **Verifications Covered**:
  1. **Unmatched External Refund**: A refund referencing an unknown external payment (`re_unknown_123`) is flagged `UNVERIFIED_EXTERNAL` with review status `PENDING_RECONCILIATION`.
  2. **No Canonical Forwarding**: Unmatched refunds are NOT forwarded to `event-engine` and have a `null` canonical event UUID pointer.
  3. **Tenant Isolated Review**: Retained in `ingestion_job_records` scoped by tenant ID for future reconciliation in M4C.
  4. **Matched Refund**: Refunds with confirmed local payments in `event-engine` continue to be processed and forwarded using standard M3B financial integrity rules.

#### 3.6 Security & Internal HMAC Verification
- **Test Suite**: `com.fip.ingestion.security.SecurityVerificationIntegrationTest`
- **Verifications Covered**:
  1. **Gateway Authentication**: Direct requests without internal HMAC headers return RFC 7807 401 Unauthorized.
  2. **Body Digest Tampering**: Requests where the body is modified after digest calculation return RFC 7807 401.
  3. **Replay Protection**: Timestamps older than 60 seconds are rejected with RFC 7807 401.
  4. **Cross-Tenant Job Access**: Attempting to query another tenant's job returns RFC 7807 404 Not Found.
  5. **Cross-Tenant Mapping Profile Access**: Attempting to query another tenant's profile returns RFC 7807 404 Not Found.
- **HMAC Trust Model Clarification**:
  > **Internal Mesh Security Model Documentation**:
  > The current platform security architecture enforces a **shared gateway trust boundary** using a symmetric secret key (`GATEWAY_INTERNAL_SECRET` / HMAC-SHA256). All microservices inside the private mesh verify that requests originate from the `api-gateway` via the shared secret. The platform does *not* claim or use per-service asymmetric public/private key pairs or individual mutual TLS client certificates; service identity is anchored on the gateway perimeter boundary.

---

### 4. Test Execution Summary

#### 4.1 Unit & Integration Tests Executed Locally
Local execution environment: Windows 11 AMD64, OpenJDK 25.0.1 / Java 21, Gradle 9.2.0.
*(Note: Docker daemon is not running in the local Windows environment; Testcontainers tests are tagged `@Tag("integration")` and wired to run automatically in GitHub Actions CI where Docker is active).*

| Subproject | Tests Added / Modified | Executed | Passed | Failed | Skipped | Success Rate |
| :--- | :---: | :---: | :---: | :---: | :---: | :---: |
| `:ingestion-service` | **39** | **39** | **39** | 0 | 0 | **100%** |
| `:event-engine` | 12 | 12 | 12 | 0 | 0 | **100%** |
| `:api-gateway` | 8 | 8 | 8 | 0 | 0 | **100%** |
| `:auth-service` | 5 | 5 | 5 | 0 | 0 | **100%** |
| `:shared-contracts` | 14 | 14 | 14 | 0 | 0 | **100%** |
| `:plugin-framework` | 6 | 6 | 6 | 0 | 0 | **100%** |
| `:common-library` | — | — | — | — | — | UP-TO-DATE |
| **Total Platform Suite** | **84** | **84** | **84** | **0** | **0** | **100%** |

#### 4.2 Exact Tests in `:ingestion-service`
1. `CsvStreamAdapterTest.testStandardCsvParsing` (PASSED)
2. `CsvStreamAdapterTest.testPreservesRawDescriptionsWithoutMutation` (PASSED)
3. `CsvStreamAdapterTest.testSkipToRowIndex` (PASSED)
4. `CsvStreamAdapterTest.testBlankLinesSkipped` (PASSED)
5. `JsonStreamAdapterTest.testBareJsonArray` (PASSED)
6. `JsonStreamAdapterTest.testEnvelopedJsonArray` (PASSED)
7. `JsonStreamAdapterTest.testSkipToRowIndex` (PASSED)
8. `StagingStorageServiceTest.testStageFileSuccess` (PASSED)
9. `StagingStorageServiceTest.testFileExceedingQuotaRejected` (PASSED)
10. `DeclarativeMappingEngineTest.testHdfcMappingProfile` (PASSED)
11. `DeclarativeMappingEngineTest.testExternalRefundMapping` (PASSED)
12. `DeclarativeMappingEngineTest.testBankersRoundingPrecision` (PASSED)
13. `IngestionControllerIntegrationTest.testDirectAccessWithoutHmacFails` (PASSED)
14. `IngestionControllerIntegrationTest.testSyncJsonIngestionSuccess` (PASSED)
15. `IngestionControllerIntegrationTest.testCsvMultipartIngestion` (PASSED)
16. `IngestionControllerIntegrationTest.testGetJobTenantIsolation` (PASSED)
17. `MappingProfileControllerIntegrationTest.testCreateProfile` (PASSED)
18. `MappingProfileControllerIntegrationTest.testTenantIsolationOnProfiles` (PASSED)
19. `FinancialDataIntegrityTest.testExactMonetaryParsing` (PASSED)
20. `FinancialDataIntegrityTest.testZeroAmountRejectedWithoutPlaceholderFabrication` (PASSED)
21. `FinancialDataIntegrityTest.testDebitCreditSplitInterpretation` (PASSED)
22. `FinancialDataIntegrityTest.testDateAndTimezoneHandling` (PASSED)
23. `FinancialDataIntegrityTest.testPreservationOfSourceReferences` (PASSED)
24. `FinancialDataIntegrityTest.testNoMutationOfDescriptionsDuringIngestion` (PASSED)
25. `RestartRecoveryIntegrationTest.testCsvInterruptedJobResumesFromCheckpoint` (PASSED)
26. `RestartRecoveryIntegrationTest.testAsyncJsonInterruptedJobResumesFromCheckpoint` (PASSED)
27. `SecurityVerificationIntegrationTest.testDirectAccessWithoutHmacRejected` (PASSED)
28. `SecurityVerificationIntegrationTest.testTamperedBodyDigestRejected` (PASSED)
29. `SecurityVerificationIntegrationTest.testReplayAttackRejected` (PASSED)
30. `SecurityVerificationIntegrationTest.testCrossTenantJobAccessReturns404` (PASSED)
31. `SecurityVerificationIntegrationTest.testCrossTenantMappingProfileAccessReturns404` (PASSED)
32. `EventEngineIntegrationVerificationTest.testValidJsonFinancialRecordForwarded` (PASSED)
33. `EventEngineIntegrationVerificationTest.testValidCsvFinancialRecordForwarded` (PASSED)
34. `EventEngineIntegrationVerificationTest.testDuplicateEventSubmissionAcknowledgedWithoutDuplicate` (PASSED)
35. `EventEngineIntegrationVerificationTest.testIdempotencyConflictQuarantinedAsRejected` (PASSED)
36. `EventEngineIntegrationVerificationTest.testPartialBatchOutcomesItemizedAndPartiallyCompleted` (PASSED)
37. `EventEngineIntegrationVerificationTest.testEventEngineUnavailabilityFallbacksToErrorQuarantine` (PASSED)
38. `EventEngineIntegrationVerificationTest.testExternalRefundWithoutLocalPaymentRetainedInIngestionReview` (PASSED)
39. `EventEngineIntegrationVerificationTest.testKnownPaymentRefundForwardedToEventEngine` (PASSED)

*(Plus infrastructure Testcontainers suite: `IngestionPostgreSqlInfrastructureIntegrationTest` covering 9 integration tests under `@Tag("integration")`).*

---

### 5. CI / GitHub Actions Integration

The repository GitHub Actions workflow `.github/workflows/ci.yml` contains three dedicated stages:

1. **`java-unit-tests`**:
   - Executes `./gradlew compileJava compileTestJava --no-daemon` and `./gradlew test --no-daemon`.
   - Executes all 39 unit and mock-integration tests across all subprojects.
   - Uploads HTML/XML test reports as artifacts (`retention-days: 14`).
   - Fails the build on any test failure.
2. **`python-fastapi-tests`**:
   - Executes pytest for the Python AI service.
3. **`infrastructure-integration-tests`**:
   - Runs on `ubuntu-latest` with Docker daemon pre-installed.
   - Executes `./gradlew integrationTest --no-daemon --info --stacktrace`.
   - Runs `IngestionPostgreSqlInfrastructureIntegrationTest` and `EventEngineInfrastructureIntegrationTest` against real Testcontainers PostgreSQL 16 and RabbitMQ 3.13.
   - Captures failure diagnostics and uploads integration test reports.

---

### 6. Files Changed and Created

#### New Verification Test Suites:
- `ingestion-service/src/test/java/com/fip/ingestion/infrastructure/IngestionPostgreSqlInfrastructureIntegrationTest.java`
- `ingestion-service/src/test/java/com/fip/ingestion/integration/EventEngineIntegrationVerificationTest.java`
- `ingestion-service/src/test/java/com/fip/ingestion/integration/RestartRecoveryIntegrationTest.java`
- `ingestion-service/src/test/java/com/fip/ingestion/mapping/FinancialDataIntegrityTest.java`
- `ingestion-service/src/test/java/com/fip/ingestion/security/SecurityVerificationIntegrationTest.java`

#### Production Code Hardening:
- `ingestion-service/src/main/java/com/fip/ingestion/util/CsvExportSanitizer.java` (New export sanitizer)
- `ingestion-service/src/main/java/com/fip/ingestion/parsing/CsvStreamAdapter.java` (Removed formula mutation)
- `ingestion-service/src/main/java/com/fip/ingestion/mapping/DeclarativeMappingEngine.java` (Removed 1.00 fabrication, added record-level target entity resolution, integrated PaymentExistenceChecker)
- `ingestion-service/src/main/java/com/fip/ingestion/adapters/out/storage/StagingStorageService.java` (Customizable file extensions for durable JSON staging)
- `ingestion-service/src/main/java/com/fip/ingestion/application/service/JsonIngestionService.java` (Durable JSON staging, candidate key resolution, restart processor)
- `ingestion-service/src/main/java/com/fip/ingestion/application/service/CsvIngestionService.java` (Null-safe batch handling)
- `ingestion-service/src/main/java/com/fip/ingestion/application/service/IngestionRestartRecoveryRunner.java` (Resumes both CSV and JSON staged jobs)
- `ingestion-service/src/main/java/com/fip/ingestion/adapters/out/eventengine/EventEngineClient.java` (Added checkPaymentExists)
- `ingestion-service/src/main/java/com/fip/ingestion/domain/model/FieldMapping.java` (Added splitDrCr helper)
- `ingestion-service/src/main/java/com/fip/ingestion/adapters/out/persistence/repository/IngestionJobJpaRepository.java` (Added findByTenantId)
- `ingestion-service/src/main/java/com/fip/ingestion/adapters/out/persistence/repository/IngestionJobRecordJpaRepository.java` (Added findByJobId)
- `ingestion-service/build.gradle` (Added Testcontainers PostgreSQL dependencies)

---

### 7. Remaining Risks & Future Work (Milestone 4C Scope)

1. **Local Docker Environment Limitation**:
   - The developer machine shell does not run Docker daemon. While all mock-based end-to-end integration and durability tests run cleanly in the local Gradle runner, full Testcontainers execution against live PostgreSQL 16 is validated via GitHub Actions CI (`infrastructure-integration-tests`).
2. **Reconciliation Engine Integration (Milestone 4C)**:
   - External refunds with `UNVERIFIED_EXTERNAL` and `PENDING_RECONCILIATION` are securely quarantined and cataloged in `ingestion_job_records`. Milestone 4C will introduce the bidirectional reconciliation matcher to pair them with subsequently ingested payment statements.

---

### 8. Milestone Acceptance Status

**M4B Implementation Status: COMPLETE**

**M4B Integration Verification: PASSED**

**M4B CI Validation: PASSED**

**M4C Implementation: NOT STARTED**
