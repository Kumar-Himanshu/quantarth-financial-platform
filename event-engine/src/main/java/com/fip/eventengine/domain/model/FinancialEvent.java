package com.fip.eventengine.domain.model;

import com.fip.shared.events.CanonicalEvent;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.UUID;

public class FinancialEvent {
    private final UUID id;
    private final UUID eventId;
    private final String eventType;
    private final String entityType;
    private final String entityId;
    private final String eventSource;
    private final String sourceSystem;
    private final String traceId;
    private final String correlationId;
    private final String causationId;
    private final String tenantId;
    private final LocalDateTime timestamp;
    private final LocalDateTime occurredAt;
    private final LocalDateTime processedAt;
    private final LocalDateTime recordedAt;
    private final String payload;
    private final String version;
    private final String schemaVersion;
    private final String idempotencyKey;
    private final String payloadHash;

    public FinancialEvent(UUID id, UUID eventId, String eventType, String entityType, String entityId,
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
     * Backwards-compatible constructor for M1/M2 code.
     */
    public FinancialEvent(UUID id, UUID eventId, String eventType, String eventSource,
                          String traceId, String tenantId, LocalDateTime timestamp,
                          String payload, String version, LocalDateTime processedAt) {
        this(id, eventId, eventType, null, null, eventSource, eventSource, traceId, traceId,
                null, tenantId, timestamp, timestamp, processedAt, processedAt, payload,
                version, version, null, null);
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getEntityType() { return entityType; }
    public String getEntityId() { return entityId; }
    public String getEventSource() { return eventSource; }
    public String getSourceSystem() { return sourceSystem; }
    public String getTraceId() { return traceId; }
    public String getCorrelationId() { return correlationId; }
    public String getCausationId() { return causationId; }
    public String getTenantId() { return tenantId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public LocalDateTime getOccurredAt() { return occurredAt; }
    public LocalDateTime getProcessedAt() { return processedAt; }
    public LocalDateTime getRecordedAt() { return recordedAt; }
    public String getPayload() { return payload; }
    public String getVersion() { return version; }
    public String getSchemaVersion() { return schemaVersion; }
    public String getIdempotencyKey() { return idempotencyKey; }
    public String getPayloadHash() { return payloadHash; }

    public CanonicalEvent toCanonicalEvent() {
        return new CanonicalEvent(
                eventId,
                tenantId,
                schemaVersion != null ? schemaVersion : version,
                eventType,
                entityType != null ? entityType : CanonicalEvent.inferEntityType(eventType),
                entityId,
                sourceSystem != null ? sourceSystem : eventSource,
                occurredAt != null ? occurredAt.toInstant(ZoneOffset.UTC) : timestamp.toInstant(ZoneOffset.UTC),
                recordedAt != null ? recordedAt.toInstant(ZoneOffset.UTC) : processedAt.toInstant(ZoneOffset.UTC),
                correlationId != null ? correlationId : traceId,
                causationId,
                idempotencyKey,
                Collections.emptyMap(),
                payload
        );
    }

    public static FinancialEvent fromCanonicalEvent(CanonicalEvent canonical, String payloadHash) {
        Instant occInstant = canonical.occurredAt() != null ? canonical.occurredAt() : Instant.now();
        Instant recInstant = canonical.recordedAt() != null ? canonical.recordedAt() : Instant.now();
        LocalDateTime occ = LocalDateTime.ofInstant(occInstant, ZoneOffset.UTC);
        LocalDateTime rec = LocalDateTime.ofInstant(recInstant, ZoneOffset.UTC);
        String schemaVer = canonical.schemaVersion() != null ? canonical.schemaVersion() : "1.0.0";
        String entityType = canonical.entityType() != null ? canonical.entityType() : CanonicalEvent.inferEntityType(canonical.eventType());

        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventId(canonical.eventId() != null ? canonical.eventId() : UUID.randomUUID())
                .eventType(canonical.eventType())
                .entityType(entityType)
                .entityId(canonical.entityId())
                .eventSource(canonical.sourceSystem())
                .sourceSystem(canonical.sourceSystem())
                .traceId(canonical.correlationId())
                .correlationId(canonical.correlationId())
                .causationId(canonical.causationId())
                .tenantId(canonical.tenantId() != null ? canonical.tenantId() : "default")
                .timestamp(occ)
                .occurredAt(occ)
                .processedAt(rec)
                .recordedAt(rec)
                .payload(canonical.payload())
                .version(schemaVer)
                .schemaVersion(schemaVer)
                .idempotencyKey(canonical.idempotencyKey())
                .payloadHash(payloadHash)
                .build();
    }

    public static FinancialEvent fromCanonical(UUID eventId, String eventType, String eventSource,
                                               String traceId, String tenantId,
                                               Instant timestamp, String payload, String version) {
        LocalDateTime dt = timestamp != null ? LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC) : LocalDateTime.now();
        LocalDateTime now = LocalDateTime.now();
        String ver = version != null ? version : "1.0";
        String entityType = CanonicalEvent.inferEntityType(eventType);

        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventId(eventId != null ? eventId : UUID.randomUUID())
                .eventType(eventType)
                .entityType(entityType)
                .eventSource(eventSource)
                .sourceSystem(eventSource)
                .traceId(traceId != null ? traceId : UUID.randomUUID().toString())
                .correlationId(traceId != null ? traceId : UUID.randomUUID().toString())
                .tenantId(tenantId != null ? tenantId : "default")
                .timestamp(dt)
                .occurredAt(dt)
                .payload(payload)
                .version(ver)
                .schemaVersion(ver)
                .processedAt(now)
                .recordedAt(now)
                .build();
    }

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

        public FinancialEvent build() {
            return new FinancialEvent(id, eventId, eventType, entityType, entityId,
                    eventSource, sourceSystem, traceId, correlationId, causationId,
                    tenantId, timestamp, occurredAt, processedAt, recordedAt,
                    payload, version, schemaVersion, idempotencyKey, payloadHash);
        }
    }
}
