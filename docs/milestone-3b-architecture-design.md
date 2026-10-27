# QuantArth Financial Intelligence Platform (FIP)
## Milestone 3B Architecture Design: Canonical Financial Persistence and Event Integration

### 1. Executive Summary & Objectives
Milestone 3B integrates the canonical financial primitives and domain models established in Milestone 3A into the core `event-engine` microservice. This milestone establishes:
1. **Single-Table Canonical Persistence**: Extends PostgreSQL `financial_events` table with canonical metadata columns, JSONB payload storage, and tenant-scoped composite indexes without creating nine unneeded relational tables.
2. **Dual-Mode Event Ingestion**: Supports both strict canonical financial ingestion (`POST /api/v1/events/canonical`) and backward-compatible legacy ingestion (`POST /api/v1/events/publish`).
3. **Database-Level Idempotency**: Guarantees duplicate-safe ingestion under extreme concurrency via PostgreSQL unique constraints (`tenant_id`, `source_system`, `idempotency_key`) and payload-hash verification, producing deterministic RFC 7807 409 Conflict responses on key reuse with altered payloads.
4. **Atomic Transactional Outbox**: Co-locates canonical event persistence and outbox record insertion within a single `@Transactional` database boundary.
5. **Normalized AMQP Event Routing**: Introduces normalized topic routing keys (`financial.events.<entityType>.<action>`) with backward-compatible routing for legacy platform events.
6. **Refund Integrity & Pessimistic Concurrency**: Prevents race conditions and financial overdrafts during refunds through `PESSIMISTIC_WRITE` row locks on target payments, cumulative refund tracking, and pending capacity reservation.
7. **Strict Multi-Tenant Isolation**: Enforces authenticated `TenantContext` on all reads and writes, explicitly blocking cross-tenant tampering and credential spoofing.

---

### 2. End-to-End Component Architecture

```
                    +--------------------------------------------+
                    |        API Gateway / Reverse Proxy         |
                    | (HMAC Verification, JWT & TenantContext)   |
                    +---------------------+----------------------+
                                          | HTTP / REST
                                          v
+------------------------------------------------------------------------------------+
|                                  Event Engine                                      |
|                                                                                    |
|  +------------------------------------------------------------------------------+  |
|  |                     EventController (Inbound REST Port)                      |  |
|  |   - POST /api/v1/events/canonical (Canonical Ingestion)                      |  |
|  |   - POST /api/v1/events/canonical/batch (Bulk Canonical Ingestion)           |  |
|  |   - GET  /api/v1/events (Tenant-Scoped Paginated Search)                     |  |
|  |   - POST /api/v1/events/publish (Legacy M1/M2 Ingestion)                     |  |
|  +--------------------------------------+---------------------------------------+  |
|                                         |                                          |
|                                         v                                          |
|  +------------------------------------------------------------------------------+  |
|  |                     EventIngestionService (Application Service)              |  |
|  |   1. TenantContext verification (reject client overrides with 403)           |  |
|  |   2. Schema version validation (require 1.0.0 or 1.x)                        |  |
|  |   3. FinancialModelValidator domain invariant verification                   |  |
|  |   4. RefundIntegrityValidator (Pessimistic lock & capacity reserve)          |  |
|  |   5. Normalized Routing Resolution (financial.events.<entity>.<action>)      |  |
|  |   6. Idempotency Check (Payload SHA-256 vs existing database record)          |  |
|  +--------------------------------------+---------------------------------------+  |
|                                         | Atomic Spring @Transactional             |
|                    +--------------------+--------------------+                     |
|                    |                                         |                     |
|                    v                                         v                     |
|  +----------------------------------+   +----------------------------------+       |
|  |    FinancialEventJpaRepository   |   |        OutboxJpaRepository       |       |
|  |  (financial_events JSONB table)  |   |          (outbox table)          |       |
|  +----------------------------------+   +----------------------------------+       |
+--------------------|-----------------------------------------|---------------------+
                     |                                         |
                     | PostgreSQL 16 ACID Transaction          |
                     v                                         v
+------------------------------------------------------------------------------------+
|                                  PostgreSQL 16                                     |
|  +--------------------------------------+   +-----------------------------------+  |
|  | Table: financial_events              |   | Table: outbox                     |  |
|  | - event_id (UUID PK)                 |   | - id (UUID PK)                    |  |
|  | - tenant_id (VARCHAR)                |   | - event_id, aggregate_type, etc.  |  |
|  | - entity_type, entity_id (VARCHAR)   |   | - status (PENDING/CLAIMED/SENT)   |  |
|  | - idempotency_key, payload_hash      |   | - routing_key, payload (JSONB)    |  |
|  | - payload (JSONB)                    |   +-----------------------------------+  |
|  | - uk_financial_events_tenant_source_ |                                          |
|  |   idempotency (UNIQUE INDEX)         |                                          |
|  +--------------------------------------+                                          |
+--------------------------------------------------------------+---------------------+
                                                               |
                                       Asynchronous Polling   | (Worker Thread)
                                                               v
                                              +----------------------------------+
                                              |     OutboxPollingPublisher       |
                                              | (Batch Claim with FOR UPDATE     |
                                              |  SKIP LOCKED, Confirmation Ack)  |
                                              +----------------+-----------------+
                                                               |
                                                               v AMQP 0-9-1
                                              +----------------------------------+
                                              |       RabbitMQ 3.13 Exchange     |
                                              |     (fip.financial.exchange)     |
                                              +----------------+-----------------+
```

---

### 3. Key Architectural Decisions & Safeguards

#### 3.1 Single-Table JSONB Persistence vs Nine Relational Tables
- **Evaluated Trade-off**: Creating nine separate relational tables (`payments`, `transfers`, `invoices`, etc.) vs extending `financial_events` with indexed canonical metadata columns and a typed JSONB document.
- **Architectural Rationale**: FIP is an event streaming and auditing engine, not an OLTP billing engine. The core access patterns are:
  1. Retrieve by event ID.
  2. Search chronologically by tenant and entity ID (`entity_type` + `entity_id`).
  3. Replay canonical financial events to downstream accounting ledgers and analytical sinks.
- **Design Realization**: A single `financial_events` table with dedicated columns for high-cardinality routing and search keys (`entity_type`, `entity_id`, `schema_version`, `source_system`, `occurred_at`, `recorded_at`, `correlation_id`, `causation_id`, `idempotency_key`, `payload_hash`) paired with JSONB `payload` provides optimal indexing, zero schema migration gridlock when domain models evolve, and single-query outbox co-location.

#### 3.2 Concurrency and Idempotency Guarantees
- Idempotency is enforced at the database level via a composite unique index:
  ```sql
  CREATE UNIQUE INDEX uk_financial_events_tenant_source_idempotency
  ON financial_events (tenant_id, source_system, idempotency_key)
  WHERE idempotency_key IS NOT NULL;
  ```
- **Behavior Matrix**:
  | Ingestion Attempt | Scenario | Outcome |
  |---|---|---|
  | **First Request** | New idempotency key | Persists event & outbox record atomically; returns `201 Created`. |
  | **Duplicate Request** | Identical idempotency key + identical payload hash | Returns existing persisted event; returns `200 OK` or `201 Created` with idempotent response; no outbox duplicate. |
  | **Conflicting Request** | Identical idempotency key + altered payload | Triggers `ConflictException`; returns RFC 7807 `409 Conflict`. |
  | **Concurrent Race** | Multiple identical requests executing in parallel | PostgreSQL unique constraint triggers `DataIntegrityViolationException`; application intercepts and returns existing persisted record. |

#### 3.3 Refund Integrity & Capacity Reservation
- Refunds present a systemic risk of double-spend and overdraft under concurrent API invocation.
- **Pessimistic Row Locking**: When a refund references an existing payment (`originalPaymentReference`), `findPaymentForUpdate(tenantId, paymentId)` executes:
  ```sql
  SELECT e FROM FinancialEventJpaEntity e
  WHERE e.tenantId = :tenantId AND e.entityType = 'PAYMENT' AND e.entityId = :paymentId
  -- Locked with LockModeType.PESSIMISTIC_WRITE
  ```
- **Capacity Formula**:
  $$\text{Remaining Capacity} = \text{Captured Amount} - \sum (\text{Pending Refunds} + \text{Completed Refunds})$$
- Pending refunds reserve capacity immediately upon receipt (`RefundStatus.INITIATED` or `RefundStatus.PENDING`), preventing parallel requests from exceeding the original captured amount.

#### 3.4 Multi-Tenant Boundary Enforcement
- All inbound requests must carry an authenticated `TenantContext`.
- If the incoming event payload specifies a `tenantId` that differs from the authenticated `TenantContext.getTenantId()`, `EventIngestionService` explicitly rejects the request with RFC 7807 `403 Forbidden` (`FORBIDDEN_CROSS_TENANT_WRITE`).
- All JPA query methods (`findByEventIdAndTenantId`, `searchCanonicalEvents`, `findPaymentForUpdate`) strictly append `tenant_id = :tenantId`.
