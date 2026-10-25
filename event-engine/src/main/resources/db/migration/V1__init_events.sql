-- =============================================================================
-- Financial Intelligence Platform (FIP) - Event Engine Schema Migration
-- Flyway Migration: V1__init_events.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS financial_events (
    id UUID NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    event_source VARCHAR(100) NOT NULL,
    trace_id VARCHAR(100),
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
    timestamp TIMESTAMP NOT NULL,
    payload TEXT NOT NULL,
    version VARCHAR(20) NOT NULL DEFAULT '1.0',
    processed_at TIMESTAMP NOT NULL,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version_lock BIGINT DEFAULT 0,
    soft_delete BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_financial_events PRIMARY KEY (id),
    CONSTRAINT uk_financial_events_event_id UNIQUE (event_id)
);

CREATE TABLE IF NOT EXISTS event_dlq_records (
    id UUID NOT NULL,
    original_message TEXT NOT NULL,
    failure_reason TEXT NOT NULL,
    retry_count INT NOT NULL DEFAULT 0,
    status VARCHAR(20) NOT NULL DEFAULT 'FAILED',
    recorded_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_event_dlq_records PRIMARY KEY (id)
);

-- Performance and Audit Query Indexes
CREATE INDEX IF NOT EXISTS idx_financial_events_type ON financial_events(event_type);
CREATE INDEX IF NOT EXISTS idx_financial_events_source ON financial_events(event_source);
CREATE INDEX IF NOT EXISTS idx_financial_events_tenant ON financial_events(tenant_id);
CREATE INDEX IF NOT EXISTS idx_financial_events_trace ON financial_events(trace_id);
CREATE INDEX IF NOT EXISTS idx_financial_events_timestamp ON financial_events(timestamp);
CREATE INDEX IF NOT EXISTS idx_event_dlq_status ON event_dlq_records(status);
