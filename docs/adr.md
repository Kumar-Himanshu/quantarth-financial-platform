# Architecture Decision Records (ADR) - Financial Intelligence Platform (FIP)

This document records the architectural choices made for FIP to guarantee high availability, strict compliance, and 10-year enterprise maintainability.

---

## ADR-001: Git-Backed Centralized Configuration Service
### Context
Microservices require externalized configurations across multiple deployment tiers (`dev`, `qa`, `uat`, `prod`). Hardcoding configurations violates the Twelve-Factor App methodology.
### Decision
We employ Spring Cloud Config Server backed by an auditable repository. Secrets and environmental properties are managed outside source code bundles.
### Consequences
- Git commit logs serve as an immutable audit trail of infrastructure changes.
- Decouples pipeline builds from environment-specific configurations.

---

## ADR-002: Service Discovery & Dynamic Routing
### Context
Microservices in containerized environments (Kubernetes / Docker) have dynamic IP addresses. Hardcoding inter-service IPs is brittle and hinders horizontal scaling.
### Decision
We use dynamic routing through Spring Cloud Gateway with service resolution and client-side load balancing.
### Consequences
- Zero hardcoded inter-service URLs in application code.
- Gateway acts as the single secure entrypoint to the microservices ecosystem.

---

## ADR-003: Logical Tenant Isolation Strategy
### Context
FIP is a multi-tenant platform serving multiple enterprises and internal products (ArthQuant AI, Document AI SaaS). Data leakage across tenants is unacceptable.
### Decision
We adopt a unified logical isolation pattern:
- Every database entity includes a `tenant_id` column with index coverage.
- The `TenantContext` ThreadLocal variable is populated at the gateway and verified on each service invocation.
- Database access queries automatically filter by `tenant_id`.
### Consequences
- Strong data isolation guarantees without the resource explosion of maintaining thousands of independent database instances.

---

## ADR-004: Database-Per-Service Architecture
### Context
Shared databases create tight coupling, schema gridlock, and cross-domain data dependencies that prevent independent service deployment and scaling.
### Decision
Every microservice owns its private PostgreSQL database (`fip_auth`, `fip_plugins`, `fip_events`). No service is ever permitted to access another service's database directly.
### Consequences
- Strict microservice autonomy: services can evolve their schemas independently via Flyway migrations.
- Inter-service communication happens exclusively via REST contracts or RabbitMQ events.

---

## ADR-005: Hexagonal Architecture (Ports and Adapters)
### Context
Fintech business rules must be preserved for decades without rewriting when database engines, messaging systems, or web frameworks change.
### Decision
All Java services are structured using Hexagonal Architecture:
- Core Domain models contain pure business logic with zero framework imports.
- Inbound Ports define use cases; Inbound Adapters handle HTTP and AMQP inputs.
- Outbound Ports define external dependencies; Outbound Adapters implement JPA repositories and message publishers.
### Consequences
- Business logic is 100% testable in unit tests without starting Spring or database containers.
- Framework upgrades and infrastructural swaps do not require changes to domain models.

---

## ADR-006: Asynchronous Event-Driven Messaging & Dead-Letter Exchanges
### Context
Synchronous REST calls across microservices cause cascading failures, high latency, and coupling during traffic spikes.
### Decision
We implement Event-Driven Architecture (EDA) via RabbitMQ with a centralized Topic Exchange (`fip.financial.exchange`) and a dedicated Dead-Letter Exchange (`fip.dlx.exchange`). All transactions, lifecycle changes, and user events emit standardized `CanonicalEvent` records.
### Consequences
- Asynchronous decoupling prevents outages in downstream consumers from blocking producer requests.
- Failed messages are automatically routed to the DLQ for inspection, retry, and alerting.

---

## ADR-007: Dynamic Classloader & Sandboxed Plugin Framework
### Context
Future products (ArthQuant, Risk APIs) require custom analytical logic and algorithms without modifying the core platform.
### Decision
We build an extensible Plugin Framework with dynamic URLClassLoader isolation and `FipPlugin` SDK interfaces. Plugins run with sandboxed context and cannot corrupt the host JVM.
### Consequences
- Independent lifecycle: third parties can register, activate, suspend, or update plugins at runtime.
- Auditable: every plugin execution records execution duration and produces audit trail events.

---

## ADR-008: Python FastAPI for Machine Learning & Document Intelligence
### Context
Computer vision (YOLO), OCR, and Natural Language Processing (NER) models are natively developed in Python with modern ML ecosystems. Embedding them directly into JVM services creates performance bottlenecks.
### Decision
We deploy the AI Service as a dedicated Python FastAPI microservice that communicates exclusively via REST APIs, returning structured JSON with confidence metrics and bounding boxes.
### Consequences
- Allows leveraging state-of-the-art Python ML libraries while maintaining clean separation from the Java backend.
- FastAPI automatically provides OpenAPI documentation and asynchronous high-throughput execution.

---

## ADR-009: JWT Claims Propagation & Gateway-Level RBAC
### Context
Validating JWT tokens and database permissions inside every individual microservice creates duplicate code and excessive database roundtrips.
### Decision
The API Gateway validates JWT signatures and decrypts claims (UserId, Roles, Permissions, TenantId), then injects trusted headers (`X-User-Subject`, `X-User-Id`, `X-Tenant-Id`, `X-User-Roles`, `X-User-Permissions`) onto downstream requests.
### Consequences
- Centralized security enforcement at the edge.
- Downstream microservices focus on business domain logic while retaining full access to authenticated caller identity.

---

## ADR-010: RFC 7807 Problem Details Error Standardization
### Context
Inconsistent error formats across microservices cause integration friction for downstream SDKs and consumer products.
### Decision
All microservices implement the RFC 7807 `application/problem+json` standard for error responses via Spring's `ProblemDetail` and FastAPI custom exception handlers.
### Consequences
- Predictable, machine-readable error responses with standard fields: `type`, `title`, `status`, `detail`, `timestamp`, and `errors`.

---

## ADR-011: Request-Bound HMAC-SHA256 Service-to-Service Authentication
### Context
Static shared secret tokens (`X-Gateway-Token`) in header forwarding can be forged if leaked, do not bind requests cryptographically, and do not protect against in-transit parameter tampering or replay attacks. Direct downstream port access must be prevented.
### Decision
We implement request-bound HMAC-SHA256 authentication:
- API Gateway signs canonical request representation: `HTTP_METHOD\nPATH\nQUERY\nSUBJECT\nUSER_ID\nTENANT_ID\nROLES\nTIMESTAMP\nNONCE\nBODY_SHA256_DIGEST`.
- Strips all client-supplied identity context and gateway headers before injecting signature headers.
- Downstream services enforce:
  1. Signature cryptographic match using constant-time comparison (`MessageDigest.isEqual`).
  2. Strict clock skew threshold (<= 60 seconds).
  3. Sliding-window nonce replay protection.
  4. Active key ring for zero-downtime key rotation.
  5. Body digest integrity check against received payload bytes.
- Docker Compose removes all host-port publishing for downstream application microservices.
### Consequences
- Eliminates gateway bypass and header spoofing vulnerabilities without requiring complex mTLS infrastructure in MVP.
- Any modification of method, path, query, tenant, identity, roles, or body results in RFC 7807 401 Unauthorized.

---

## ADR-012: Transactional Outbox with Publisher Confirmations, Worker Leasing, and Durable Idempotency
### Context
Dual-write patterns (writing to database and publishing to message broker in separate operations) cause phantom writes or lost events during network partitions, broker downtimes, or service crashes. Simple outbox polling without broker ACK can drop messages on broker nack or crash.
### Decision
We implement a high-reliability Transactional Outbox pattern in `event-engine`:
1. Database mutation and outbox record (`event_outbox`) are committed atomically within a single local database transaction.
2. `OutboxPollingPublisher` coordinates concurrent workers using database leasing (`locked_by`, `locked_at`) and bounded claim timeouts (60 seconds) to recover from crashed workers.
3. RabbitMQ publisher confirmations are enabled (`publisher-confirm-type: correlated`, `publisher-returns: true`). Outbox records are marked `SENT` only after verified broker ACK.
4. Transient failures trigger bounded retries (max 5) with exponential backoff (`next_attempt_at`). Messages exceeding retry limits transition to `FAILED` with operator recovery capability.
5. Consumers (`RabbitMQEventListener`) enforce durable idempotency via database `existsByEventId` and unique constraint violation handling (`DataIntegrityViolationException`), guaranteeing safe processing under duplicate delivery.
### Consequences
- Guarantees at-least-once message delivery without dual-write inconsistency.
- Prevents split-brain worker race conditions and handles abandoned claims safely.
- Eliminates duplicate execution risks at consumer boundaries.

---

## ADR-013: Canonical Financial Data Model, Precision Preservation, and Schema Evolution
### Context
Financial data exchange across multiple microservices (`event-engine`, `auth-service`, `plugin-framework`, `api-gateway`, and external integrations) requires strongly-typed, immutable, arbitrary-precision value objects and domain models. Floating-point arithmetic introduces IEEE 754 precision corruption, while unstandardized currency codes, untracked external identifiers (UPI RRN, UTR, CIN), and unmasked PII create severe regulatory compliance, accounting, and security vulnerabilities.
### Decision
We introduce the Canonical Financial Data Model in `shared-contracts`:
1. **Precision & Money Value Object**: All monetary values are encapsulated in immutable `Money` records backed by `BigDecimal` (scale <= 6) and validated ISO 4217 currency codes. Floating-point types (`float`, `double`) are strictly prohibited. Arithmetic operations enforce explicit Banker's Rounding (`RoundingMode.HALF_EVEN`).
2. **PII & Cardholder Data Protection**: Plaintext primary account numbers (PAN), Aadhaar numbers, and national tax identifiers are rejected at domain boundaries. Only masked references (e.g. `XXXXXX1234F`, `XXXX-XXXX-1234`) are retained.
3. **Decoupled Temporal Dimensions**: `FinancialTime` records UTC-normalized occurrence (`occurredAt`), accounting value date (`valueDate`), processing timestamp (`processedAt`), and ledger recording time (`recordedAt`).
4. **Canonical Financial Entities**: Nine canonical types (`Payment`, `Transfer`, `Salary`, `Refund`, `Loan`, `LoanRepayment`, `Invoice`, `Subscription`, `Tax`) model domain-specific rules:
   - Payments enforce non-terminal to terminal state machines.
   - Transfers mandate distinct source and destination accounts.
   - Salaries prohibit inferring gross from net and validate deduction ceilings.
   - Refunds enforce original payment ceilings and support partial refunds.
   - Loans and repayments support non-EMI structures and itemized component sums.
   - Invoices enforce subtotal + tax = total and partial payment tracking.
   - Taxes support India-specific references (GSTIN, Challan CIN, TAN) and negative amounts for tax refund receivables.
5. **Versioned Canonical Event Envelope**: `CanonicalEvent` is enhanced to schema version `1.0.0` with first-class metadata (`entityType`, `entityId`, `sourceSystem`, `occurredAt`, `recordedAt`, `correlationId`, `causationId`, `idempotencyKey`).
6. **Backward & Forward Compatibility**: Jackson `@JsonAlias` mappings and legacy 9-argument constructors maintain 100% binary, source, and wire compatibility with legacy M1/M2 messages. Unknown fields are ignored gracefully via `@JsonIgnoreProperties(ignoreUnknown = true)`.
### Consequences
- Eliminates monetary rounding errors across all distributed pipelines.
- Ensures strict compliance with PCI-DSS and privacy standards by blocking raw PII in event buses.
- Downstream services can adopt strongly-typed domain events incrementally without breaking existing event consumers.

---

## ADR-014: Canonical Financial Persistence, Database-Level Idempotency, and Refund Integrity Concurrency Controls
### Context
Milestone 3A established domain models and validation rules for nine canonical financial entities. In Milestone 3B, these events must be ingested, persisted, deduplicated, and published via the existing `event-engine` microservice. High-volume fintech platforms face concurrency hazards:
1. Re-entrant or concurrent requests reusing an idempotency key with conflicting payloads.
2. Concurrent refund requests racing against captured payment capacity, potentially causing financial overdrafts.
3. Database sprawl if each financial entity type were given an isolated relational table without OLTP requirement.
4. Tenant isolation bypass if client-supplied tenant identifiers were trusted.
### Decision
1. **Unified JSONB Persistence**:
   - Rather than creating nine distinct relational tables, `financial_events` is extended with dedicated indexed metadata columns (`entity_type`, `entity_id`, `schema_version`, `source_system`, `occurred_at`, `recorded_at`, `correlation_id`, `causation_id`, `idempotency_key`, `payload_hash`) while storing the validated typed domain model in native PostgreSQL `JSONB`.
   - Migration `V4__canonical_financial_events.sql` is strictly additive with historical row backfill.
2. **Database-Level Idempotency Enforcement**:
   - A composite unique index on `(tenant_id, source_system, idempotency_key)` guarantees deduplication at the ACID database layer.
   - Incoming payloads compute a SHA-256 `payload_hash`. Identical retried requests return the existing persisted event without duplicate processing. Key reuse with altered payloads produces an explicit RFC 7807 `409 Conflict`.
3. **Refund Integrity and Pessimistic Concurrency**:
   - Refunds execute within a Spring `@Transactional` boundary and acquire a `PESSIMISTIC_WRITE` lock on the target payment record via `findPaymentForUpdate(tenantId, paymentId)`.
   - Cumulative pending and completed refunds are tracked against captured payment balances, immediately reserving refundable capacity upon ingestion.
4. **Normalized Event Routing**:
   - Routing keys follow the normalized schema `financial.events.<entityType>.<action>`.
   - Legacy platform events retain their existing routing prefixes (`auth.events.*`, `plugin.events.*`, `ai.events.*`).
5. **Strict Tenant Context Isolation**:
   - `TenantContext` is non-negotiable. Incoming events attempting to specify a `tenantId` different from the authenticated context are rejected with RFC 7807 `403 Forbidden`.
### Consequences
- Prevents double-spend and overdrafts during parallel refund execution.
- Guarantees idempotent exactly-once processing semantics at database boundaries.
- Provides high search and audit throughput via tenant-scoped indexes without relational schema gridlock.

---

## ADR-015: Dedicated Ingestion Microservice (`ingestion-service`) vs Event Engine Co-Location
### Context
Financial data ingestion involves handling untrusted, heterogeneous external data sources (CSV spreadsheets, JSON webhook streams, legacy ERP exports) with variable schemas, potential payload corruptions, and large file sizes (up to 25 MB). We evaluated whether to extend the existing `event-engine` (Option A) or introduce a dedicated `ingestion-service` (Option B).
### Decision
We introduce an independent microservice: `ingestion-service` (Spring Boot 3.3.1, Java 21, Hexagonal Architecture) owning its private PostgreSQL database (`fip_ingestion`).
1. **Fault & Blast Radius Isolation**: Memory-intensive streaming CSV parsing and schema normalization errors are strictly isolated from the core event ledger and outbox publisher.
2. **Database Separation (ADR-004)**: Ephemeral job tracking tables (`ingestion_jobs`, `ingestion_job_records`) have a 90-day retention policy, while `financial_events` requires a 10-year immutable audit lifecycle.
3. **Single Responsibility**: `ingestion-service` is an ETL and normalization engine; `event-engine` is the immutable canonical event store.
### Consequences
- Requires one additional Spring Boot service container in Docker Compose and CI matrix.
- `event-engine` remains completely decoupled from external bank statement formats and parsing dependencies.
- Reuses `event-engine`'s battle-tested `POST /api/v1/events/canonical/batch` endpoint without code duplication.

---

## ADR-016: Synchronous vs Database-Backed Asynchronous Batch Processing with Resumable Staging
### Context
Financial records may arrive as small webhook bursts (1–50 records) requiring immediate synchronous confirmation, or as large batch exports (up to 50,000 rows in CSV) that would time out standard HTTP gateway connections. If an application restarts during an asynchronous batch, processing must resume without duplicating canonical events.
### Decision
We implement a hybrid processing model:
1. **Synchronous Ingestion**: Available for JSON streams when record count is $\le 50$ and the client specifies `?sync=true`. Validation, normalization, and canonical persistence complete within the HTTP request lifecycle, returning `200 OK`.
2. **Database-Backed Asynchronous Ingestion**: Default for all file uploads (`POST /api/v1/ingestion/csv`) and large JSON batches. Immediately streams payload to bounded local staging, persists an `ingestion_jobs` record (`status = 'RECEIVED'`, `last_processed_row = 0`), returns `202 Accepted` with a `jobId`, and processes the file via streaming worker chunks of 100 rows.
3. **Resumable Progress**: Every chunk commit updates `last_processed_row` and `heartbeat_at` in `ingestion_jobs`. Crashed workers resume streaming from `last_processed_row + 1` upon restart.
### Consequences
- Avoids gateway timeouts (HTTP 504) during multi-megabyte file uploads.
- Enables clients to poll job progress and fetch granular row-level error reports without holding open HTTP connections.
- Guarantees crash recovery without re-executing already committed canonical events.

---

## ADR-017: Bounded Ephemeral Staging, Configurable Retention, and Permanent Audit Summaries
### Context
External source files often contain sensitive PII. Retaining raw files indefinitely violates data minimization, yet processing asynchronously requires temporary file durability for restart recovery. Mandatory database partitioning in MVP introduces unnecessary DDL complexity.
### Decision
1. **Bounded Ephemeral Staging Volume**: Uploaded CSV files stream to `/var/data/ingestion/staging/{tenant_id}/{job_id}.csv.stage` with restricted permissions (`chmod 0600`), 25 MB file cap, and 10 GB partition ceiling. Files are securely wiped immediately upon job completion or failure.
2. **Standard Indexed PostgreSQL Tables**: `ingestion_job_records` is implemented as an ordinary PostgreSQL table with composite B-tree indexes, removing mandatory monthly partitioning from the MVP.
3. **Configurable Ephemeral Retention**: Detailed error and itemized records in `ingestion_job_records` are retained for a configurable period (default proposed: 90 days) and pruned via daily off-peak chunked deletes (1,000 rows/batch).
4. **Permanent Audit Metadata**: The master record in `ingestion_jobs` (counters, file hash, mapping version, timing, and error code summary) is retained indefinitely (or minimum 1 year), providing compliance auditability without storing raw payloads.
### Consequences
- Eliminates cloud object storage costs (S3/GCS) during MVP.
- Resolves the contradiction between async processing and zero-file durability.
- Complies with data minimization standards (GDPR, India DPDP Act) while maintaining full regulatory auditability.

---

## ADR-018: Declarative Source Mapping Profiles
### Context
Banking and financial CSV formats vary drastically across financial institutions (e.g. HDFC uses `Withdrawal Amt.` / `Deposit Amt.`, ICICI uses single `Amount` with `Type`, Stripe uses integer cents). Hardcoding parser logic per bank in Java code creates unmaintainable code sprawl.
### Decision
We implement a **Declarative Mapping Engine** driven by tenant-scoped `source_mapping_profiles`:
1. Profiles define source column names, date formats (e.g. `dd/MM/yyyy`), timezones (e.g. `Asia/Kolkata`), currency defaults, and transformation strategies (`SPLIT_DR_CR`, `SIGNED_AMOUNT`, `STATIC_DEFAULT`, `REGEX_EXTRACT`).
2. Mappings are stored in PostgreSQL JSONB and validated at profile creation time.
3. Parsers execute the declarative rules to map raw string tokens into canonical financial models (`Payment`, `Refund`, etc.).
### Consequences
- New bank statement layouts can be supported dynamically via API configuration without code deployments or server restarts.
- Domain models remain 100% agnostic to external banking column names.

---

## ADR-019: Ingestion-Side Holding & Reconciliation Review Queue for External Refunds
### Context
QuantArth is a Financial Intelligence Platform, not a payment gateway. External financial feeds frequently report refunds for payments that occurred externally prior to QuantArth onboarding. Persisting an unverified refund directly into `financial_events` pollutes the authoritative ledger, whereas rejecting it prevents financial intelligence. Synthetic dummy payments must never be fabricated.
### Decision
We establish a strict separation between **Ingestion Acceptance** and **Canonical Event Acceptance**:
1. **Local Payment Exists**: If the referenced payment exists in `financial_events`, M3B's pessimistic row lock validates remaining capacity and currency. The refund is verified, forwarded to `event-engine`, and marked `ACCEPTED`.
2. **Local Payment Absent**: If the payment does not exist in local persistence, the refund is accepted at the ingestion layer only. It is stored in `ingestion_job_records` with status `UNVERIFIED_EXTERNAL` and review state `PENDING_RECONCILIATION`. It is **NOT** forwarded to `event-engine` as a canonical event.
3. **Reconciliation Transitions**:
   - Automated reconciliation matches newly ingested historical payments and promotes the refund to `RECONCILED_VERIFIED`, forwarding it to `event-engine`.
   - Authorized operators can manually approve unverified refunds via `POST /api/v1/ingestion/reconciliation/refunds/{recordId}/approve` with audit justification (`HISTORICAL_EXTERNAL_PRE_QUANTARTH`).
### Consequences
- Preserves 100% of M3B refund integrity invariants without weakening canonical validation rules.
- Prevents unverified ledger pollution while capturing real-world external financial events for reconciliation.

---

## ADR-020: Internal REST Mesh Authentication via Service Identity and M2A HMAC Signer
### Context
`ingestion-service` must transfer batches of validated canonical events to `event-engine`. Directly accessing `fip_events` violates ADR-004. Inter-service calls must authenticate securely without assuming gateway-only credentials.
### Decision
`ingestion-service` authenticates to `event-engine`'s existing `POST /api/v1/events/canonical/batch` endpoint using its own distinct internal service identity signed via the M2A HMAC-SHA256 protocol:
1. **Service Identity Context**: `X-User-Subject: service:ingestion-service`, `X-User-Id: system:ingestion-service`, `X-User-Roles: ROLE_INTERNAL_SERVICE`, and propagated caller `X-Tenant-Id`.
2. **Cryptographic Signature**: `ingestion-service` configures `RequestHmacSigner` from `shared-contracts` with `activeKeyId = "v1"` and shared internal secret `${GATEWAY_INTERNAL_SECRET}`.
3. **Integrity & Anti-Replay**: Every batch request includes a unique UUID nonce, current UTC timestamp, and SHA-256 payload digest.
### Consequences
- Requires zero modifications to `GatewayAuthenticationFilter` in `event-engine`.
- 100% compliant with M2A zero-trust internal service authentication without gateway bypass vulnerabilities.

---

## ADR-021: Multi-Tier Deduplication and Advisory File Fingerprinting
### Context
Duplicate financial records can occur across HTTP retries, re-uploaded CSV files, duplicate rows within a single spreadsheet, or overlapping bank export date ranges. A hard database unique constraint on `(tenant_id, file_hash)` breaks legitimate re-processing when a job fails, a mapping profile changes, or a user forces a re-upload.
### Decision
We remove the database unique constraint on file hash and establish a 4-tier deduplication hierarchy:
1. **Tier 1 (Request Idempotency)**: `Idempotency-Key` header with 24-hour cache replay.
2. **Tier 2 (Upload File Fingerprinting - Advisory)**: SHA-256 file checksum stored with an index on `(tenant_id, file_hash)`. Duplicate file uploads are blocked by domain logic only if a previous job successfully `COMPLETED` with the same mapping profile version. Re-upload is explicitly allowed if the previous job `FAILED`, the mapping profile version changed, or the client supplies `force=true`.
3. **Tier 3 (Source Transaction Identity)**: Composite check on `(tenant_id, source_system, source_record_id)` flags already ingested records as `DUPLICATE` in `ingestion_job_records` and skips canonical forwarding.
4. **Tier 4 (Canonical Event Idempotency - ACID Ledger)**: M3B PostgreSQL composite unique index on `(tenant_id, source_system, idempotency_key)` guarantees zero duplicates at the final event ledger.
### Consequences
- Eliminates database blocks on legitimate file re-processing.
- Provides defense-in-depth against duplicate financial transactions across all ingest channels.





