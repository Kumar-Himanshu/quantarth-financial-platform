-- =============================================================================
-- Financial Intelligence Platform (FIP) - Outbox Reliability & Leasing Migration
-- Flyway Migration: V3__enhance_event_outbox.sql
-- =============================================================================

ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS locked_by VARCHAR(100);
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS locked_at TIMESTAMP;
ALTER TABLE event_outbox ADD COLUMN IF NOT EXISTS next_attempt_at TIMESTAMP;

CREATE INDEX IF NOT EXISTS idx_outbox_claimable ON event_outbox(status, next_attempt_at, locked_at);
