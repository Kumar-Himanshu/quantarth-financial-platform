# QuantArth Financial Intelligence Platform (FIP)
## Milestone 3B Database Schema: Canonical Financial Persistence

### 1. Flyway Migration Version History
- `V1__init_events_schema.sql`: Initial `financial_events` and `outbox` table definitions.
- `V2__audit_ledger.sql`: Immutable transaction audit log table.
- `V3__outbox_leasing.sql`: Worker claim tracking and lease expiration timestamping.
- **`V4__canonical_financial_events.sql`**: Canonical financial metadata columns, JSONB migration, backfill, and composite idempotency index.

---

### 2. Physical Schema: `financial_events` Table

```sql
-- Extended Schema Definition in PostgreSQL 16 (Additive Migration V4)
CREATE TABLE IF NOT EXISTS financial_events (
    event_id        UUID PRIMARY KEY,
    tenant_id       VARCHAR(64) NOT NULL,
    entity_type     VARCHAR(64),
    entity_id       VARCHAR(128),
    event_type      VARCHAR(128) NOT NULL,
    schema_version  VARCHAR(32) NOT NULL DEFAULT '1.0.0',
    source_system   VARCHAR(64) NOT NULL DEFAULT 'event-engine',
    occurred_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    recorded_at     TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP,
    correlation_id  VARCHAR(128),
    causation_id    VARCHAR(128),
    idempotency_key VARCHAR(128),
    payload_hash    VARCHAR(64),
    payload         JSONB NOT NULL,
    created_at      TIMESTAMP WITHOUT TIME ZONE NOT NULL DEFAULT CURRENT_TIMESTAMP
);
```

#### Detailed Column Dictionary
| Column Name | Type | Nullable | Default | Description |
|---|---|---|---|---|
| `event_id` | `UUID` | No | - | Primary Key. Global unique event identifier. |
| `tenant_id` | `VARCHAR(64)` | No | - | Multi-tenant isolation scope identifier. |
| `entity_type` | `VARCHAR(64)` | Yes | `UNKNOWN` | Domain financial entity type: `PAYMENT`, `TRANSFER`, `SALARY`, `REFUND`, `LOAN`, `LOAN_REPAYMENT`, `INVOICE`, `SUBSCRIPTION`, `TAX`. |
| `entity_id` | `VARCHAR(128)` | Yes | `event_id` | Business entity identifier (e.g. `PAY-10023`, `INV-8891`). |
| `event_type` | `VARCHAR(128)` | No | - | Event action classifier (e.g. `payment.initiated`, `refund.completed`). |
| `schema_version` | `VARCHAR(32)` | No | `'1.0.0'` | Semantic schema version string. |
| `source_system` | `VARCHAR(64)` | No | `'event-engine'` | Originating service or client namespace. |
| `occurred_at` | `TIMESTAMPTZ` | No | `NOW()` | Event occurrence timestamp. |
| `recorded_at` | `TIMESTAMPTZ` | No | `NOW()` | Ledger recording timestamp. |
| `correlation_id` | `VARCHAR(128)`| Yes | - | Distributed tracing correlation ID. |
| `causation_id` | `VARCHAR(128)`| Yes | - | Parent event ID or trigger reference. |
| `idempotency_key`| `VARCHAR(128)`| Yes | - | Caller-supplied deduplication key. |
| `payload_hash` | `VARCHAR(64)` | Yes | - | SHA-256 hexadecimal hash of serialized canonical payload. |
| `payload` | `JSONB` | No | - | Native PostgreSQL JSONB structured payload. |
| `created_at` | `TIMESTAMP` | No | `NOW()` | Database record insertion timestamp. |

---

### 3. Indexes & Constraints

```sql
-- 1. Uniqueness Index: Idempotency within Tenant and Source System Namespace
CREATE UNIQUE INDEX IF NOT EXISTS uk_financial_events_tenant_source_idempotency
ON financial_events (tenant_id, source_system, idempotency_key)
WHERE idempotency_key IS NOT NULL;

-- 2. Tenant + Entity Search Index: Accelerated Entity Timeline Queries
CREATE INDEX IF NOT EXISTS idx_financial_events_tenant_entity
ON financial_events (tenant_id, entity_type, entity_id);

-- 3. Tenant + Temporal Query Index: Accelerated Range-Based Auditing
CREATE INDEX IF NOT EXISTS idx_financial_events_tenant_occurred
ON financial_events (tenant_id, occurred_at);

-- 4. Tenant + Causation Reference Index: Refund and Parent Linkages
CREATE INDEX IF NOT EXISTS idx_financial_events_tenant_causation
ON financial_events (tenant_id, causation_id)
WHERE causation_id IS NOT NULL;

-- 5. Outbox Status & Leased At Index (From V1/V3)
CREATE INDEX IF NOT EXISTS idx_outbox_status_leased
ON outbox (status, leased_at);
```

---

### 4. Backfill & Compatibility Strategy
1. **Additive Schema Updates**: All newly introduced columns are nullable or have sensible defaults (`DEFAULT '1.0.0'`, `DEFAULT 'event-engine'`, `DEFAULT CURRENT_TIMESTAMP`).
2. **Backfill Routine**: Existing historical rows from M1/M2 are backfilled with:
   - `schema_version = '1.0.0'`
   - `source_system = 'event-engine'`
   - `occurred_at = created_at`
   - `recorded_at = created_at`
   - `entity_type = 'UNKNOWN'`
   - `entity_id = CAST(event_id AS VARCHAR)`
3. **Payload Type Alteration**: The `payload` column is converted from `TEXT` to `JSONB` via `USING payload::jsonb`, with non-JSON text falling back to `to_jsonb(payload)`.
