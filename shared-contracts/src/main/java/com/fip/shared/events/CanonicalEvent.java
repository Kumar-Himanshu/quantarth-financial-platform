package com.fip.shared.events;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.JsonDeserializer;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.io.IOException;
import java.io.Serializable;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned Canonical Event Envelope for the QuantArth Financial Intelligence Platform.
 * <p>
 * Decouples event routing metadata from domain business payloads, guarantees tenant isolation,
 * provides deterministic serialization, and supports backward/forward schema evolution.
 * Accommodates both legacy escaped JSON strings and modern nested JSON objects in the payload field.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record CanonicalEvent(
        @JsonProperty("eventId")
        UUID eventId,

        @JsonProperty("tenantId")
        String tenantId,

        @JsonProperty("schemaVersion")
        @JsonAlias({"version", "schemaVersion"})
        String schemaVersion,

        @JsonProperty("eventType")
        String eventType,

        @JsonProperty("entityType")
        String entityType,

        @JsonProperty("entityId")
        String entityId,

        @JsonProperty("sourceSystem")
        @JsonAlias({"eventSource", "sourceSystem"})
        String sourceSystem,

        @JsonProperty("occurredAt")
        @JsonAlias({"timestamp", "occurredAt"})
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt,

        @JsonProperty("recordedAt")
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant recordedAt,

        @JsonProperty("correlationId")
        @JsonAlias({"traceId", "correlationId"})
        String correlationId,

        @JsonProperty("causationId")
        String causationId,

        @JsonProperty("idempotencyKey")
        String idempotencyKey,

        @JsonProperty("metadata")
        Map<String, Object> metadata,

        @JsonProperty("payload")
        @JsonDeserialize(using = PayloadDeserializer.class)
        String payload
) implements Serializable {

    private static final long serialVersionUID = 1L;
    public static final String CURRENT_VERSION = "1.0.0";

    @JsonCreator
    public CanonicalEvent(
            @JsonProperty("eventId") UUID eventId,
            @JsonProperty("tenantId") String tenantId,
            @JsonProperty("schemaVersion") @JsonAlias({"version", "schemaVersion"}) String schemaVersion,
            @JsonProperty("eventType") String eventType,
            @JsonProperty("entityType") String entityType,
            @JsonProperty("entityId") String entityId,
            @JsonProperty("sourceSystem") @JsonAlias({"eventSource", "sourceSystem"}) String sourceSystem,
            @JsonProperty("occurredAt") @JsonAlias({"timestamp", "occurredAt"}) Instant occurredAt,
            @JsonProperty("recordedAt") Instant recordedAt,
            @JsonProperty("correlationId") @JsonAlias({"traceId", "correlationId"}) String correlationId,
            @JsonProperty("causationId") String causationId,
            @JsonProperty("idempotencyKey") String idempotencyKey,
            @JsonProperty("metadata") Map<String, Object> metadata,
            @JsonProperty("payload") @JsonDeserialize(using = PayloadDeserializer.class) String payload
    ) {
        this.eventId = eventId;
        this.tenantId = (tenantId != null && !tenantId.isBlank()) ? tenantId : "default";
        this.schemaVersion = (schemaVersion != null && !schemaVersion.isBlank()) ? schemaVersion : CURRENT_VERSION;
        this.eventType = eventType;
        this.entityType = entityType != null ? entityType : inferEntityType(eventType);
        this.entityId = entityId;
        this.sourceSystem = sourceSystem;
        this.occurredAt = occurredAt != null ? occurredAt : Instant.now();
        this.recordedAt = recordedAt != null ? recordedAt : Instant.now();
        this.correlationId = correlationId != null ? correlationId : UUID.randomUUID().toString();
        this.causationId = causationId;
        this.idempotencyKey = idempotencyKey;
        this.metadata = metadata != null ? Collections.unmodifiableMap(new HashMap<>(metadata)) : Collections.emptyMap();
        this.payload = payload;
    }

    /**
     * Legacy 9-argument constructor for backwards compatibility with M1 and M2 code/tests.
     */
    public CanonicalEvent(
            UUID eventId,
            String eventType,
            String eventSource,
            Instant timestamp,
            String payload,
            String version,
            String traceId,
            String tenantId,
            Map<String, Object> metadata
    ) {
        this(
                eventId,
                tenantId != null ? tenantId : "default",
                version != null ? version : CURRENT_VERSION,
                eventType,
                inferEntityType(eventType),
                null,
                eventSource,
                timestamp != null ? timestamp : Instant.now(),
                Instant.now(),
                traceId != null ? traceId : UUID.randomUUID().toString(),
                null,
                null,
                metadata != null ? metadata : Collections.emptyMap(),
                payload
        );
    }

    // Backwards-compatible accessors for legacy callers
    @JsonIgnore
    public String eventSource() {
        return sourceSystem;
    }

    @JsonIgnore
    public Instant timestamp() {
        return occurredAt;
    }

    @JsonIgnore
    public String traceId() {
        return correlationId;
    }

    @JsonIgnore
    public String version() {
        return schemaVersion;
    }

    public static CanonicalEvent create(String eventType, String eventSource, String payload) {
        return new CanonicalEvent(
                UUID.randomUUID(),
                eventType,
                eventSource,
                Instant.now(),
                payload,
                CURRENT_VERSION,
                UUID.randomUUID().toString(),
                "default",
                Collections.emptyMap()
        );
    }

    public static CanonicalEvent create(String eventType, String eventSource, String payload, String traceId, String tenantId, Map<String, Object> metadata) {
        return new CanonicalEvent(
                UUID.randomUUID(),
                eventType,
                eventSource,
                Instant.now(),
                payload,
                CURRENT_VERSION,
                traceId != null ? traceId : UUID.randomUUID().toString(),
                tenantId != null ? tenantId : "default",
                metadata != null ? metadata : Collections.emptyMap()
        );
    }

    public static String inferEntityType(String eventType) {
        if (eventType == null) return EntityType.OTHER.name();
        String upper = eventType.toUpperCase();
        if (upper.contains("PAYMENT")) return EntityType.PAYMENT.name();
        if (upper.contains("TRANSFER")) return EntityType.TRANSFER.name();
        if (upper.contains("SALARY") || upper.contains("PAYROLL")) return EntityType.SALARY.name();
        if (upper.contains("REFUND")) return EntityType.REFUND.name();
        if (upper.contains("REPAYMENT")) return EntityType.LOAN_REPAYMENT.name();
        if (upper.contains("LOAN")) return EntityType.LOAN.name();
        if (upper.contains("INVOICE")) return EntityType.INVOICE.name();
        if (upper.contains("SUBSCRIPTION")) return EntityType.SUBSCRIPTION.name();
        if (upper.contains("TAX") || upper.contains("GST") || upper.contains("TDS")) return EntityType.TAX.name();
        if (upper.contains("USER") || upper.contains("AUTH")) return EntityType.AUTH.name();
        if (upper.contains("PLUGIN")) return EntityType.PLUGIN.name();
        return EntityType.OTHER.name();
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private UUID eventId = UUID.randomUUID();
        private String tenantId = "default";
        private String schemaVersion = CURRENT_VERSION;
        private String eventType;
        private String entityType;
        private String entityId;
        private String sourceSystem;
        private Instant occurredAt = Instant.now();
        private Instant recordedAt = Instant.now();
        private String correlationId = UUID.randomUUID().toString();
        private String causationId;
        private String idempotencyKey;
        private Map<String, Object> metadata = new HashMap<>();
        private String payload;

        public Builder eventId(UUID eventId) { this.eventId = eventId; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder schemaVersion(String schemaVersion) { this.schemaVersion = schemaVersion; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder entityType(String entityType) { this.entityType = entityType; return this; }
        public Builder entityType(EntityType entityType) { this.entityType = entityType != null ? entityType.name() : null; return this; }
        public Builder entityId(String entityId) { this.entityId = entityId; return this; }
        public Builder sourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; return this; }
        public Builder occurredAt(Instant occurredAt) { this.occurredAt = occurredAt; return this; }
        public Builder recordedAt(Instant recordedAt) { this.recordedAt = recordedAt; return this; }
        public Builder correlationId(String correlationId) { this.correlationId = correlationId; return this; }
        public Builder causationId(String causationId) { this.causationId = causationId; return this; }
        public Builder idempotencyKey(String idempotencyKey) { this.idempotencyKey = idempotencyKey; return this; }
        public Builder metadata(Map<String, Object> metadata) {
            this.metadata = metadata != null ? new HashMap<>(metadata) : new HashMap<>();
            return this;
        }
        public Builder addMetadata(String key, Object value) {
            this.metadata.put(key, value);
            return this;
        }
        public Builder payload(String payload) { this.payload = payload; return this; }

        public CanonicalEvent build() {
            return new CanonicalEvent(eventId, tenantId, schemaVersion, eventType, entityType, entityId, sourceSystem, occurredAt, recordedAt, correlationId, causationId, idempotencyKey, metadata, payload);
        }
    }

    /**
     * Flexible deserializer accepting both escaped JSON strings and nested structured JSON objects.
     */
    public static class PayloadDeserializer extends JsonDeserializer<String> {
        @Override
        public String deserialize(JsonParser p, DeserializationContext ctxt) throws IOException {
            JsonToken token = p.currentToken();
            if (token == JsonToken.VALUE_STRING) {
                return p.getText();
            } else if (token == JsonToken.VALUE_NULL) {
                return null;
            } else {
                JsonNode node = p.readValueAsTree();
                return node.toString();
            }
        }
    }
}
