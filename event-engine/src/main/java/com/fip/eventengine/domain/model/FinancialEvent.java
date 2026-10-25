package com.fip.eventengine.domain.model;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

public class FinancialEvent {
    private final UUID id;
    private final UUID eventId;
    private final String eventType;
    private final String eventSource;
    private final String traceId;
    private final String tenantId;
    private final LocalDateTime timestamp;
    private final String payload;
    private final String version;
    private final LocalDateTime processedAt;

    public FinancialEvent(UUID id, UUID eventId, String eventType, String eventSource,
                          String traceId, String tenantId, LocalDateTime timestamp,
                          String payload, String version, LocalDateTime processedAt) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.eventSource = eventSource;
        this.traceId = traceId;
        this.tenantId = tenantId;
        this.timestamp = timestamp;
        this.payload = payload;
        this.version = version;
        this.processedAt = processedAt;
    }

    public UUID getId() { return id; }
    public UUID getEventId() { return eventId; }
    public String getEventType() { return eventType; }
    public String getEventSource() { return eventSource; }
    public String getTraceId() { return traceId; }
    public String getTenantId() { return tenantId; }
    public LocalDateTime getTimestamp() { return timestamp; }
    public String getPayload() { return payload; }
    public String getVersion() { return version; }
    public LocalDateTime getProcessedAt() { return processedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private UUID eventId;
        private String eventType;
        private String eventSource;
        private String traceId;
        private String tenantId;
        private LocalDateTime timestamp;
        private String payload;
        private String version;
        private LocalDateTime processedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder eventId(UUID eventId) { this.eventId = eventId; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder eventSource(String eventSource) { this.eventSource = eventSource; return this; }
        public Builder traceId(String traceId) { this.traceId = traceId; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder timestamp(LocalDateTime timestamp) { this.timestamp = timestamp; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder processedAt(LocalDateTime processedAt) { this.processedAt = processedAt; return this; }

        public FinancialEvent build() {
            return new FinancialEvent(id, eventId, eventType, eventSource, traceId, tenantId, timestamp, payload, version, processedAt);
        }
    }

    public static FinancialEvent fromCanonical(UUID eventId, String eventType, String eventSource,
                                               String traceId, String tenantId,
                                               Instant timestamp, String payload, String version) {
        return FinancialEvent.builder()
                .id(UUID.randomUUID())
                .eventId(eventId != null ? eventId : UUID.randomUUID())
                .eventType(eventType)
                .eventSource(eventSource)
                .traceId(traceId != null ? traceId : UUID.randomUUID().toString())
                .tenantId(tenantId != null ? tenantId : "default")
                .timestamp(timestamp != null ? LocalDateTime.ofInstant(timestamp, ZoneOffset.UTC) : LocalDateTime.now())
                .payload(payload)
                .version(version != null ? version : "1.0")
                .processedAt(LocalDateTime.now())
                .build();
    }
}
