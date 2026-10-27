-- =============================================================================
-- Financial Intelligence Platform (FIP) - Milestone 3B Schema Migration
-- Flyway Migration: V4__canonical_financial_events.sql
-- Canonical Financial Persistence, Idempotency, and Tenant-Scoped Indexing
-- =============================================================================

-- 1. Add canonical metadata columns to existing financial_events table
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS entity_type VARCHAR(50);
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS entity_id VARCHAR(100);
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS schema_version VARCHAR(20) DEFAULT '1.0.0';
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS source_system VARCHAR(100);
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS occurred_at TIMESTAMP;
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS recorded_at TIMESTAMP;
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS correlation_id VARCHAR(100);
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS causation_id VARCHAR(100);
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS idempotency_key VARCHAR(150);
ALTER TABLE financial_events ADD COLUMN IF NOT EXISTS payload_hash VARCHAR(64);

-- 2. Backfill existing rows for seamless backwards compatibility
UPDATE financial_events SET
    entity_type = COALESCE(entity_type, 'OTHER'),
    schema_version = COALESCE(schema_version, version, '1.0.0'),
    source_system = COALESCE(source_system, event_source, 'legacy-system'),
    occurred_at = COALESCE(occurred_at, timestamp, created_at),
    recorded_at = COALESCE(recorded_at, processed_at, created_at),
    correlation_id = COALESCE(correlation_id, trace_id, id::text)
WHERE entity_type IS NULL OR source_system IS NULL;

-- 3. In PostgreSQL, convert payload to JSONB if currently text
DO $$
BEGIN
    IF EXISTS (
        SELECT 1
        FROM information_schema.columns
        WHERE table_name = 'financial_events'
          AND column_name = 'payload'
          AND data_type = 'text'
    ) THEN
        ALTER TABLE financial_events ALTER COLUMN payload TYPE JSONB USING payload::jsonb;
    END IF;
END $$;

-- 4. Idempotency Constraint: Tenant + Source System + Idempotency Key uniqueness
CREATE UNIQUE INDEX IF NOT EXISTS uk_financial_events_tenant_source_idempotency
ON financial_events (tenant_id, source_system, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- 5. Performance and Query Indexes (strictly tenant-scoped)
CREATE INDEX IF NOT EXISTS idx_financial_events_tenant_entity
ON financial_events (tenant_id, entity_type, entity_id);

CREATE INDEX IF NOT EXISTS idx_financial_events_tenant_occurred
ON financial_events (tenant_id, occurred_at);

CREATE INDEX IF NOT EXISTS idx_financial_events_tenant_entity_occurred
ON financial_events (tenant_id, entity_type, occurred_at);

CREATE INDEX IF NOT EXISTS idx_financial_events_tenant_correlation
ON financial_events (tenant_id, correlation_id);
