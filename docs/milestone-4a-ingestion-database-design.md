# QuantArth Financial Intelligence Platform (FIP)
## Milestone 4A Database Design: Financial Data Ingestion Persistence (Refined)

### 1. Database Autonomy & Isolation

In accordance with **ADR-004 (Database-Per-Service)**, `ingestion-service` maintains its own dedicated PostgreSQL 16 database:
```
Database Name: fip_ingestion
Connection URL: jdbc:postgresql://postgres:5432/fip_ingestion
Managed via: Flyway additive migrations (V1 onwards)
```

#### Responsibility Boundaries:
- `fip_ingestion` is an **operational, tracking, and transformation store**, NOT a permanent financial ledger.
- `fip_events` (owned by `event-engine`) remains the **sole authoritative permanent store** for canonical financial events and the transactional outbox.
- No direct database-level foreign keys or joins exist between `fip_ingestion` and `fip_events`. Linkage occurs strictly via distributed UUID references (`canonical_event_id`).

---

### 2. Physical Schema Definitions

```sql
-- =============================================================================
-- Flyway Migration: V1__init_ingestion_schema.sql
-- Ingestion Jobs, Itemized Records, and Source Mapping Profiles
-- =============================================================================

-- 1. Source Mapping Profiles: Declarative Transformation Rules
CREATE TABLE IF NOT EXISTS source_mapping_profiles (
    profile_id          UUID PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    source_format       VARCHAR(32) NOT NULL, -- CSV, JSON
    target_entity_type  VARCHAR(64) NOT NULL, -- PAYMENT, REFUND, INVOICE, etc.
    delimiter           VARCHAR(5) NOT NULL DEFAULT ',',
    has_header          BOOLEAN NOT NULL DEFAULT TRUE,
    date_format         VARCHAR(64) NOT NULL DEFAULT 'yyyy-MM-dd HH:mm:ss',
    timezone            VARCHAR(64) NOT NULL DEFAULT 'UTC',
    currency_default    VARCHAR(3) NOT NULL DEFAULT 'INR',
    mapping_rules       JSONB NOT NULL,
    version             INT NOT NULL DEFAULT 1,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(64),
    CONSTRAINT uk_mapping_profiles_tenant_name UNIQUE (tenant_id, name)
);

-- 2. Ingestion Jobs: Top-Level Execution, Durability, and Progress Tracking
CREATE TABLE IF NOT EXISTS ingestion_jobs (
    job_id                  UUID PRIMARY KEY,
    tenant_id               VARCHAR(64) NOT NULL,
    source_type             VARCHAR(32) NOT NULL, -- CSV, JSON
    source_system           VARCHAR(64) NOT NULL, -- razorpay, hdfc_bank, tally, etc.
    source_name             VARCHAR(256),         -- Original file name or batch label
    file_hash               VARCHAR(64),          -- SHA-256 of raw file for advisory deduplication
    staging_file_path       VARCHAR(512),         -- Local disk path for durable restart recovery
    mapping_profile_id      UUID REFERENCES source_mapping_profiles(profile_id),
    mapping_profile_version INT NOT NULL DEFAULT 1,
    status                  VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    total_records           INT NOT NULL DEFAULT 0,
    processed_records       INT NOT NULL DEFAULT 0,
    accepted_records        INT NOT NULL DEFAULT 0,
    unverified_records      INT NOT NULL DEFAULT 0,
    rejected_records        INT NOT NULL DEFAULT 0,
    duplicate_records       INT NOT NULL DEFAULT 0,
    last_processed_row      INT NOT NULL DEFAULT 0, -- Monotonic progress for resumable streaming
    retry_count             INT NOT NULL DEFAULT 0, -- Recovery retry counter
    heartbeat_at            TIMESTAMPTZ,          -- Updated by worker on batch commit
    started_at              TIMESTAMPTZ,
    completed_at            TIMESTAMPTZ,
    error_summary           JSONB,                -- Aggregated error counts surviving record pruning
    created_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(64),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Ingestion Job Records: Itemized Audit Trail, Review States, and Error Quarantine
-- Ordinary PostgreSQL table (No monthly partitioning in MVP)
CREATE TABLE IF NOT EXISTS ingestion_job_records (
    record_id           UUID PRIMARY KEY,
    job_id              UUID NOT NULL REFERENCES ingestion_jobs(job_id) ON DELETE CASCADE,
    tenant_id           VARCHAR(64) NOT NULL,
    record_index        INT NOT NULL,          -- 1-based row or array index
    source_record_id    VARCHAR(128),          -- External transaction ID (e.g. TXN-10023)
    entity_type         VARCHAR(64),
    canonical_event_id  UUID,                  -- Reference to financial_events.event_id in fip_events
    status              VARCHAR(32) NOT NULL,  -- ACCEPTED, UNVERIFIED_EXTERNAL, REJECTED, DUPLICATE
    review_status       VARCHAR(32) NOT NULL DEFAULT 'NONE', -- NONE, PENDING_RECONCILIATION, RECONCILED, REJECTED
    error_code          VARCHAR(64),
    error_message       TEXT,
    raw_snippet         JSONB,                 -- Quarantined, sanitized source payload (PII masked)
    reconciled_at       TIMESTAMPTZ,
    reconciled_by       VARCHAR(64),
    created_at          TIMESTAMPTZ NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

---

### 3. Indexes & Constraints

```sql
-- 1. Advisory File Hash Index (NON-UNIQUE to allow force reprocessing and retries)
CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_tenant_file_hash
ON ingestion_jobs (tenant_id, file_hash);

-- 2. Worker Heartbeat & Crash Recovery Index: Scans stuck or abandoned jobs
CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_status_heartbeat
ON ingestion_jobs (status, heartbeat_at)
WHERE status IN ('VALIDATING', 'PROCESSING');

-- 3. Job status querying and dashboard listing
CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_tenant_status
ON ingestion_jobs (tenant_id, status, created_at DESC);

-- 4. Record-level deduplication index within tenant namespace
CREATE INDEX IF NOT EXISTS idx_ingestion_records_tenant_source_record
ON ingestion_job_records (tenant_id, source_record_id)
WHERE source_record_id IS NOT NULL;

-- 5. Paginated audit log retrieval by job, status, and record index
CREATE INDEX IF NOT EXISTS idx_ingestion_records_job_status_index
ON ingestion_job_records (job_id, status, record_index ASC);

-- 6. Unverified Refund Reconciliation Queue Index
CREATE INDEX IF NOT EXISTS idx_ingestion_records_reconciliation
ON ingestion_job_records (tenant_id, review_status, created_at)
WHERE review_status = 'PENDING_RECONCILIATION';

-- 7. Pruning Index for off-peak cleanup
CREATE INDEX IF NOT EXISTS idx_ingestion_records_created_at
ON ingestion_job_records (created_at);

-- 8. Tenant-scoped mapping profile lookup
CREATE INDEX IF NOT EXISTS idx_mapping_profiles_tenant_active
ON source_mapping_profiles (tenant_id, is_active);
```

---

### 4. Transaction Boundaries & Chunking Strategy

To prevent large file uploads (up to 50,000 rows) from exhausting transaction log buffers or holding database locks indefinitely:

1. **Job Initialization Transaction**:
   - Streams multipart payload to bounded staging `/var/data/ingestion/staging/{tenant_id}/{job_id}.csv.stage`.
   - Computes SHA-256 checksum.
   - Inserts `ingestion_jobs` record with status `RECEIVED`, `staging_file_path`, and `last_processed_row = 0`.
   - Commits immediately. Returns HTTP `202 Accepted` to client.
2. **Chunked Processing Transactions (Chunk size = 100 records)**:
   - Worker reads a stream chunk of 100 source records starting at `last_processed_row + 1`.
   - Normalizes and executes Stage 1–3 validation.
   - Sends verified canonical events to `event-engine` via `POST /api/v1/events/canonical/batch` (signed with Ingestion Service HMAC).
   - If `event-engine` accepts the batch:
     - Writes 100 itemized rows to `ingestion_job_records`.
     - Atomically updates progress counters and advances `last_processed_row = :chunkEndRow` in `ingestion_jobs`.
     - Updates `heartbeat_at = CURRENT_TIMESTAMP`.
     - Commits the chunk.
   - If `event-engine` rejects the batch (e.g. 400 validation error):
     - Ingestion service falls back to **row-by-row dispatch** for that chunk.
     - The single bad row is quarantined in `ingestion_job_records` as `REJECTED`, while the remaining 99 valid rows succeed and commit.
3. **Job Finalization Transaction**:
   - Updates `ingestion_jobs` status (`COMPLETED` if `rejected_records == 0`, otherwise `PARTIALLY_COMPLETED`).
   - Sets `completed_at = CURRENT_TIMESTAMP`.
   - Securely unlinks the staged file from disk.
   - Commits.

---

### 5. Data Retention, Ephemeral Purging & Audit Preservation

#### 5.1 Removal of Mandatory Table Partitioning
Table partitioning introduces unnecessary operational and migration complexity for initial MVP deployment scales ($< 10\text{M}$ records). Standard PostgreSQL tables with composite B-tree indexes provide microsecond retrieval times and simple Flyway lifecycle management.

#### 5.2 Configurable Retention Policies
Retention is externalized via configuration properties:
```yaml
ingestion:
  retention:
    records-days: ${INGESTION_RECORDS_RETENTION_DAYS:90}
    jobs-days: ${INGESTION_JOBS_RETENTION_DAYS:365}
    staging-cleanup-hours: 24
```
- Proposed baseline: 90 days for itemized `ingestion_job_records`, 1 year (or indefinite) for parent `ingestion_jobs`.
- These are proposed starting policies requiring future confirmation from corporate compliance officers.

#### 5.3 Scheduled Off-Peak Pruning
An asynchronous scheduler (`IngestionDataPruningScheduler`) executes daily at 02:00 UTC:
- Deletes expired records in bounded batches (1,000 rows per batch) to avoid long table locks:
  ```sql
  DELETE FROM ingestion_job_records
  WHERE record_id IN (
      SELECT record_id FROM ingestion_job_records
      WHERE created_at < NOW() - INTERVAL '90 days'
      LIMIT 1000
  );
  ```

#### 5.4 Permanent Audit Metadata Surviving Pruning
Even after detailed row-level error records in `ingestion_job_records` are permanently deleted, the master record in `ingestion_jobs` survives:
- `job_id`, `tenant_id`, `source_type`, `source_system`, `source_name`, `file_hash`.
- `mapping_profile_id`, `mapping_profile_version`.
- `total_records`, `accepted_records`, `unverified_records`, `rejected_records`, `duplicate_records`.
- `started_at`, `completed_at`, `created_by`.
- `error_summary`: JSON aggregate summary of error codes and counts (e.g. `{"INVALID_CURRENCY": 2, "DUPLICATE_TXN": 5}`).
This guarantees permanent regulatory and operational auditability without storing raw financial PII.
