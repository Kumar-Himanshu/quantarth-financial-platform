package com.fip.eventengine.adapters.out.persistence;

import com.fip.common.model.AuditModel;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(
        name = "financial_events",
        indexes = {
                @Index(name = "idx_financial_events_tenant_entity", columnList = "tenant_id, entity_type, entity_id"),
                @Index(name = "idx_financial_events_tenant_occurred", columnList = "tenant_id, occurred_at"),
                @Index(name = "idx_financial_events_tenant_correlation", columnList = "tenant_id, correlation_id")
        }
)
@AttributeOverride(name = "lockVersion", column = @Column(name = "version_lock"))
public class FinancialEventJpaEntity extends AuditModel {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "entity_type", length = 50)
    private String entityType;

    @Column(name = "entity_id", length = 100)
    private String entityId;

    @Column(name = "event_source", nullable = false, length = 100)
    private String eventSource;

    @Column(name = "source_system", length = 100)
    private String sourceSystem;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "correlation_id", length = 100)
    private String correlationId;

    @Column(name = "causation_id", length = 100)
    private String causationId;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "occurred_at")
    private LocalDateTime occurredAt;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    @Column(name = "recorded_at")
    private LocalDateTime recordedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @Column(name = "schema_version", length = 20)
    private String schemaVersion;

    @Column(name = "idempotency_key", length = 150)
    private String idempotencyKey;

    @Column(name = "payload_hash", length = 64)
    private String payloadHash;

    public FinancialEventJpaEntity() {
    }

    public FinancialEventJpaEntity(UUID id, UUID eventId, String eventType, String entityType, String entityId,
                                  String eventSource, String sourceSystem, String traceId, String correlationId,
                                  String causationId, String tenantId, LocalDateTime timestamp, LocalDateTime occurredAt,
                                  LocalDateTime processedAt, LocalDateTime recordedAt, String payload,
                                  String version, String schemaVersion, String idempotencyKey, String payloadHash) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.entityType = entityType;
        this.entityId = entityId;
        this.eventSource = eventSource != null ? eventSource : sourceSystem;
        this.sourceSystem = sourceSystem != null ? sourceSystem : eventSource;
        this.traceId = traceId != null ? traceId : correlationId;
        this.correlationId = correlationId != null ? correlationId : traceId;
        this.causationId = causationId;
        this.tenantId = tenantId;
        this.timestamp = timestamp != null ? timestamp : occurredAt;
        this.occurredAt = occurredAt != null ? occurredAt : timestamp;
        this.processedAt = processedAt != null ? processedAt : recordedAt;
        this.recordedAt = recordedAt != null ? recordedAt : processedAt;
        this.payload = payload;
        this.version = version != null ? version : schemaVersion;
        this.schemaVersion = schemaVersion != null ? schemaVersion : version;
        this.idempotencyKey = idempotencyKey;
        this.payloadHash = payloadHash;
    }

    /**
     * Legacy constructor for backwards compatibility with M1 and M2 test fixtures.
     */
    public FinancialEventJpaEntity(UUID id, UUID eventId, String eventType, String eventSource,
                                  String traceId, String tenantId, LocalDateTime timestamp,
                                  String payload, String version, LocalDateTime processedAt) {
        this(id, eventId, eventType, null, null, eventSource, eventSource, traceId, traceId,
                null, tenantId, timestamp, timestamp, processedAt, processedAt, payload,
                version, version, null, null);
    }

    @PrePersist
    @PreUpdate
    public void syncCompatibilityFields() {
        if (sourceSystem == null) sourceSystem = eventSource;
        if (eventSource == null) eventSource = sourceSystem;
        if (correlationId == null) correlationId = traceId;
        if (traceId == null) traceId = correlationId;
        if (schemaVersion == null) schemaVersion = version != null ? version : "1.0.0";
        if (version == null) version = schemaVersion;
        if (occurredAt == null) occurredAt = timestamp != null ? timestamp : LocalDateTime.now();
        if (timestamp == null) timestamp = occurredAt;
        if (recordedAt == null) recordedAt = processedAt != null ? processedAt : LocalDateTime.now();
        if (processedAt == null) processedAt = recordedAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }

    public String getEntityId() { return entityId; }
    public void setEntityId(String entityId) { this.entityId = entityId; }

    public String getEventSource() { return eventSource; }
    public void setEventSource(String eventSource) {
        this.eventSource = eventSource;
        if (this.sourceSystem == null) this.sourceSystem = eventSource;
    }

    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) {
        this.sourceSystem = sourceSystem;
        if (this.eventSource == null) this.eventSource = sourceSystem;
    }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) {
        this.traceId = traceId;
        if (this.correlationId == null) this.correlationId = traceId;
    }

    public String getCorrelationId() { return correlationId; }
    public void setCorrelationId(String correlationId) {
        this.correlationId = correlationId;
        if (this.traceId == null) this.traceId = correlationId;
    }

    public String getCausationId() { return causationId; }
    public void setCausationId(String causationId) { this.causationId = causationId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) {
        this.timestamp = timestamp;
        if (this.occurredAt == null) this.occurredAt = timestamp;
    }

    public LocalDateTime getOccurredAt() { return occurredAt; }
    public void setOccurredAt(LocalDateTime occurredAt) {
        this.occurredAt = occurredAt;
        if (this.timestamp == null) this.timestamp = occurredAt;
    }

    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) {
        this.processedAt = processedAt;
        if (this.recordedAt == null) this.recordedAt = processedAt;
    }

    public LocalDateTime getRecordedAt() { return recordedAt; }
    public void setRecordedAt(LocalDateTime recordedAt) {
        this.recordedAt = recordedAt;
        if (this.processedAt == null) this.processedAt = recordedAt;
    }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getVersion() { return version; }
    public void setVersion(String version) {
        this.version = version;
        if (this.schemaVersion == null) this.schemaVersion = version;
    }

    public String getSchemaVersion() { return schemaVersion; }
    public void setSchemaVersion(String schemaVersion) {
        this.schemaVersion = schemaVersion;
        if (this.version == null) this.version = schemaVersion;
    }

    public String getIdempotencyKey() { return idempotencyKey; }
    public void setIdempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; }

    public String getPayloadHash() { return payloadHash; }
    public void setPayloadHash(String payloadHash) { this.payloadHash = payloadHash; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private UUID eventId;
        private String eventType;
        private String entityType;
        private String entityId;
        private String eventSource;
        private String sourceSystem;
        private String traceId;
        private String correlationId;
        private String causationId;
        private String tenantId;
        private LocalDateTime timestamp;
        private LocalDateTime occurredAt;
        private LocalDateTime processedAt;
        private LocalDateTime recordedAt;
        private String payload;
        private String version;
        private String schemaVersion;
        private String idempotencyKey;
        private String payloadHash;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder eventId(UUID eventId) { this.eventId = eventId; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder entityType(String entityType) { this.entityType = entityType; return this; }
        public Builder entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder eventSource(String eventSource) { this.eventSource = eventSource; return this; }
        public Builder sourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder causationId(String causationId) { this.causationId = causationId; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
        public Builder occurredAt(LocalDateTime occurredAt) { this.occurredAt = occurredAt; return this; }
        public Builder processedAt(LocalDateTime processedAt) { this.processedAt = processedAt; return this; }
        public Builder recordedAt(LocalDateTime recordedAt) { this.recordedAt = recordedAt; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder payloadHash(String payloadHash) { this.payloadHash = payloadHash; return this; }

        public FinancialEventJpaEntity build() {
            return new FinancialEventJpaEntity(id, eventId, eventType, entityType, entityId,
                    eventSource, sourceSystem, traceId, correlationId, causationId, tenantId,
                    timestamp, occurredAt, processedAt, recordedAt, payload, version, schemaVersion,
                    idempotencyKey, payloadHash);
        }
    }
}
