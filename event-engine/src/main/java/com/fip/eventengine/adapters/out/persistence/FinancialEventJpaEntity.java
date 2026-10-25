package com.fip.eventengine.adapters.out.persistence;

import com.fip.common.model.AuditModel;
import jakarta.persistence.AttributeOverride;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "financial_events")
@AttributeOverride(name = "lockVersion", column = @Column(name = "version_lock"))
public class FinancialEventJpaEntity extends AuditModel {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false, unique = true, updatable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "event_source", nullable = false, length = 100)
    private String eventSource;

    @Column(name = "trace_id", length = 100)
    private String traceId;

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId;

    @Column(name = "timestamp", nullable = false)
    private LocalDateTime timestamp;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @Column(name = "processed_at", nullable = false)
    private LocalDateTime processedAt;

    public FinancialEventJpaEntity() {
    }

    public FinancialEventJpaEntity(UUID id, UUID eventId, String eventType, String eventSource,
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
    public void setId(UUID id) { this.id = id; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getEventSource() { return eventSource; }
    public void setEventSource(String eventSource) { this.eventSource = eventSource; }

    public String getTraceId() { return traceId; }
    public void setTraceId(String traceId) { this.traceId = traceId; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public LocalDateTime getTimestamp() { return timestamp; }
    public void setTimestamp(LocalDateTime timestamp) { this.timestamp = timestamp; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public LocalDateTime getProcessedAt() { return processedAt; }
    public void setProcessedAt(LocalDateTime processedAt) { this.processedAt = processedAt; }

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

        public FinancialEventJpaEntity build() {
            return new FinancialEventJpaEntity(id, eventId, eventType, eventSource, traceId, tenantId, timestamp, payload, version, processedAt);
        }
    }
}
