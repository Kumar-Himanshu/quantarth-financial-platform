-- =============================================================================
-- Flyway Migration: V1__init_ingestion_schema.sql
-- Financial Intelligence Platform (FIP) - Ingestion Service Schema
-- =============================================================================

-- 1. Source Mapping Profiles: Declarative Transformation Rules
CREATE TABLE IF NOT EXISTS source_mapping_profiles (
    profile_id          UUID PRIMARY KEY,
    tenant_id           VARCHAR(64) NOT NULL,
    name                VARCHAR(128) NOT NULL,
    source_format       VARCHAR(32) NOT NULL,
    target_entity_type  VARCHAR(64) NOT NULL,
    delimiter           VARCHAR(5) NOT NULL DEFAULT ',',
    has_header          BOOLEAN NOT NULL DEFAULT TRUE,
    date_format         VARCHAR(64) NOT NULL DEFAULT 'yyyy-MM-dd HH:mm:ss',
    timezone            VARCHAR(64) NOT NULL DEFAULT 'UTC',
    currency_default    VARCHAR(3) NOT NULL DEFAULT 'INR',
    mapping_rules       TEXT NOT NULL,
    version             INT NOT NULL DEFAULT 1,
    is_active           BOOLEAN NOT NULL DEFAULT TRUE,
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by          VARCHAR(64),
    CONSTRAINT uk_mapping_profiles_tenant_name UNIQUE (tenant_id, name)
);

-- 2. Ingestion Jobs: Top-Level Execution and Progress Tracking
CREATE TABLE IF NOT EXISTS ingestion_jobs (
    job_id                  UUID PRIMARY KEY,
    tenant_id               VARCHAR(64) NOT NULL,
    source_type             VARCHAR(32) NOT NULL,
    source_system           VARCHAR(64) NOT NULL,
    source_name             VARCHAR(256),
    file_hash               VARCHAR(64),
    staging_file_path       VARCHAR(512),
    mapping_profile_id      UUID REFERENCES source_mapping_profiles(profile_id),
    mapping_profile_version INT NOT NULL DEFAULT 1,
    status                  VARCHAR(32) NOT NULL DEFAULT 'RECEIVED',
    total_records           INT NOT NULL DEFAULT 0,
    processed_records       INT NOT NULL DEFAULT 0,
    accepted_records        INT NOT NULL DEFAULT 0,
    unverified_records      INT NOT NULL DEFAULT 0,
    rejected_records        INT NOT NULL DEFAULT 0,
    duplicate_records       INT NOT NULL DEFAULT 0,
    last_processed_row      INT NOT NULL DEFAULT 0,
    retry_count             INT NOT NULL DEFAULT 0,
    heartbeat_at            TIMESTAMP,
    started_at              TIMESTAMP,
    completed_at            TIMESTAMP,
    error_summary           TEXT,
    created_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP,
    created_by              VARCHAR(64),
    updated_at              TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- 3. Ingestion Job Records: Itemized Audit Trail, Review States, and Error Quarantine
CREATE TABLE IF NOT EXISTS ingestion_job_records (
    record_id           UUID PRIMARY KEY,
    job_id              UUID NOT NULL REFERENCES ingestion_jobs(job_id) ON DELETE CASCADE,
    tenant_id           VARCHAR(64) NOT NULL,
    record_index        INT NOT NULL,
    source_record_id    VARCHAR(128),
    entity_type         VARCHAR(64),
    canonical_event_id  UUID,
    status              VARCHAR(32) NOT NULL,
    review_status       VARCHAR(32) NOT NULL DEFAULT 'NONE',
    error_code          VARCHAR(64),
    error_message       TEXT,
    raw_snippet         TEXT,
    reconciled_at       TIMESTAMP,
    reconciled_by       VARCHAR(64),
    created_at          TIMESTAMP NOT NULL DEFAULT CURRENT_TIMESTAMP
);

-- Indexes for Fast Querying, Deduplication, and Recovery
CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_tenant_file_hash
ON ingestion_jobs (tenant_id, file_hash);

CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_status_heartbeat
ON ingestion_jobs (status, heartbeat_at);

CREATE INDEX IF NOT EXISTS idx_ingestion_jobs_tenant_status
ON ingestion_jobs (tenant_id, status, created_at);

CREATE INDEX IF NOT EXISTS idx_ingestion_records_tenant_source_record
ON ingestion_job_records (tenant_id, source_record_id);

CREATE INDEX IF NOT EXISTS idx_ingestion_records_job_status_index
ON ingestion_job_records (job_id, status, record_index);

CREATE INDEX IF NOT EXISTS idx_ingestion_records_reconciliation
ON ingestion_job_records (tenant_id, review_status, created_at);

CREATE INDEX IF NOT EXISTS idx_ingestion_records_created_at
ON ingestion_job_records (created_at);

CREATE INDEX IF NOT EXISTS idx_mapping_profiles_tenant_active
ON source_mapping_profiles (tenant_id, is_active);
