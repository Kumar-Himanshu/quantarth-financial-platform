package com.fip.shared.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Strongly-typed generic canonical event envelope.
 * Wraps canonical domain entities with standardized event metadata and serialization helpers.
 *
 * @param <T> The financial domain type (e.g. Payment, Invoice, Transfer)
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalFinancialEvent<T>(
        UUID eventId,
        String tenantId,
        String schemaVersion,
        String eventType,
        EntityType entityType,
        String entityId,
        String sourceSystem,
        Instant occurredAt,
        Instant recordedAt,
        String correlationId,
        String causationId,
        String idempotencyKey,
        Map<String, Object> metadata,
        T payload
) implements Serializable {

    private static final long serialVersionUID = 1L;

    public CanonicalFinancialEvent {
        if (eventId == null) {
            eventId = UUID.randomUUID();
        }
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = "default";
        }
        if (schemaVersion == null || schemaVersion.isBlank()) {
            schemaVersion = CanonicalEvent.CURRENT_VERSION;
        }
        if (occurredAt == null) {
            occurredAt = Instant.now();
        }
        if (recordedAt == null) {
            recordedAt = Instant.now();
        }
        if (correlationId == null || correlationId.isBlank()) {
            correlationId = UUID.randomUUID().toString();
        }
        metadata = metadata != null ? Collections.unmodifiableMap(new HashMap<>(metadata)) : Collections.emptyMap();
    }

    public CanonicalEvent toCanonicalEvent(ObjectMapper objectMapper) throws JsonProcessingException {
        String payloadJson = payload != null ? objectMapper.writeValueAsString(payload) : null;
        return new CanonicalEvent(
                eventId,
                tenantId,
                schemaVersion,
                eventType,
                entityType != null ? entityType.name() : CanonicalEvent.inferEntityType(eventType),
                entityId,
                sourceSystem,
                occurredAt,
                recordedAt,
                correlationId,
                causationId,
                idempotencyKey,
                metadata,
                payloadJson
        );
    }

    public static <T> CanonicalFinancialEvent<T> fromCanonicalEvent(
            CanonicalEvent canonical,
            Class<T> payloadClass,
            ObjectMapper objectMapper) throws JsonProcessingException {
        T typedPayload = canonical.payload() != null ? objectMapper.readValue(canonical.payload(), payloadClass) : null;
        EntityType type = null;
        if (canonical.entityType() != null) {
            try {
                type = EntityType.valueOf(canonical.entityType());
            } catch (IllegalArgumentException ignored) {
                type = EntityType.OTHER;
            }
        }
        return new CanonicalFinancialEvent<>(
                canonical.eventId(),
                canonical.tenantId(),
                canonical.schemaVersion(),
                canonical.eventType(),
                type,
                canonical.entityId(),
                canonical.sourceSystem(),
                canonical.occurredAt(),
                canonical.recordedAt(),
                canonical.correlationId(),
                canonical.causationId(),
                canonical.idempotencyKey(),
                canonical.metadata(),
                typedPayload
        );
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    public static class Builder<T> {
        private UUID eventId = UUID.randomUUID();
        private String tenantId = "default";
        private String schemaVersion = CanonicalEvent.CURRENT_VERSION;
        private String eventType;
        private EntityType entityType;
        private String entityId;
        private String sourceSystem;
        private Instant occurredAt = Instant.now();
        private Instant recordedAt = Instant.now();
        private String correlationId = UUID.randomUUID().toString();
        private String causationId;
        private String idempotencyKey;
        private Map<String, Object> metadata = new HashMap<>();
        private T payload;

        public Builder<T> eventId(UUID eventId) { this.eventId = eventId; return this; }
        public Builder<T> tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder<T> schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder<T> eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder<T> entityType(EntityType entityType) { this.entityType = entityType; return this; }
        public Builder<T> entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder<T> sourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; return this; }
        public Builder<T> occurredAt(Instant occurredAt) { this.occurredAt = occurredAt; return this; }
        public Builder<T> recordedAt(Instant recordedAt) { this.recordedAt = recordedAt; return this; }
        public Builder<T> correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder<T> causationId(String causationId) { this.causationId = causationId; return this; }
        public Builder<T> idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder<T> metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            return this;
        }
        public Builder<T> addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        public Builder<T> payload(T payload) { this.payload = payload; return this; }

        public CanonicalFinancialEvent<T> build() {
            return new CanonicalFinancialEvent<>(
                    eventId, tenantId, schemaVersion, eventType, entityType, entityId,
                    sourceSystem, occurredAt, recordedAt, correlationId, causationId, idempotencyKey, metadata, payload
            );
        }
    }
}
