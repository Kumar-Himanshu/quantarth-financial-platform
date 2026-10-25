-- =============================================================================
-- Financial Intelligence Platform (FIP) - Event Outbox Schema Migration
-- Flyway Migration: V2__create_event_outbox.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS event_outbox (
    id UUID NOT NULL,
    event_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL,
    routing_key VARCHAR(100) NOT NULL,
    payload TEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    retry_count INT NOT NULL DEFAULT 0,
    created_at TIMESTAMP NOT NULL,
    sent_at TIMESTAMP,
    error_message TEXT,
    CONSTRAINT pk_event_outbox PRIMARY KEY (id)
);

CREATE INDEX IF NOT EXISTS idx_outbox_status_created ON event_outbox(status, created_at);
