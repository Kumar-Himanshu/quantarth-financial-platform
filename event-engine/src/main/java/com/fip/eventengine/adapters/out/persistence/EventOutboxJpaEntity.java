package com.fip.eventengine.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "event_outbox")
public class EventOutboxJpaEntity {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "event_type", nullable = false, length = 100)
    private String eventType;

    @Column(name = "routing_key", nullable = false, length = 100)
    private String routingKey;

    @Column(name = "payload", nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(name = "status", nullable = false, length = 20)
    private String status; // PENDING, PROCESSING, SENT, FAILED

    @Column(name = "retry_count", nullable = false)
    private int retryCount;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "sent_at")
    private LocalDateTime sentAt;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "locked_by", length = 100)
    private String lockedBy;

    @Column(name = "locked_at")
    private LocalDateTime lockedAt;

    @Column(name = "next_attempt_at")
    private LocalDateTime nextAttemptAt;

    public EventOutboxJpaEntity() {
    }

    public EventOutboxJpaEntity(UUID id, UUID eventId, String eventType, String routingKey,
                                String payload, String status, int retryCount,
                                LocalDateTime createdAt, LocalDateTime sentAt, String errorMessage,
                                String lockedBy, LocalDateTime lockedAt, LocalDateTime nextAttemptAt) {
        this.id = id;
        this.eventId = eventId;
        this.eventType = eventType;
        this.routingKey = routingKey;
        this.payload = payload;
        this.status = status;
        this.retryCount = retryCount;
        this.createdAt = createdAt;
        this.sentAt = sentAt;
        this.errorMessage = errorMessage;
        this.lockedBy = lockedBy;
        this.lockedAt = lockedAt;
        this.nextAttemptAt = nextAttemptAt;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UUID getEventId() { return eventId; }
    public void setEventId(UUID eventId) { this.eventId = eventId; }

    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }

    public String getRoutingKey() { return routingKey; }
    public void setRoutingKey(String routingKey) { this.routingKey = routingKey; }

    public String getPayload() { return payload; }
    public void setPayload(String payload) { this.payload = payload; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }

    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }

    public LocalDateTime getSentAt() { return sentAt; }
    public void setSentAt(LocalDateTime sentAt) { this.sentAt = sentAt; }

    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }

    public String getLockedBy() { return lockedBy; }
    public void setLockedBy(String lockedBy) { this.lockedBy = lockedBy; }

    public LocalDateTime getLockedAt() { return lockedAt; }
    public void setLockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; }

    public LocalDateTime getNextAttemptAt() { return nextAttemptAt; }
    public void setNextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private UUID eventId;
        private String eventType;
        private String routingKey;
        private String payload;
        private String status = "PENDING";
        private int retryCount = 0;
        private LocalDateTime createdAt = LocalDateTime.now();
        private LocalDateTime sentAt;
        private String errorMessage;
        private String lockedBy;
        private LocalDateTime lockedAt;
        private LocalDateTime nextAttemptAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder eventId(UUID eventId) { this.eventId = eventId; return this; }
        public Builder eventType(String eventType) { this.eventType = eventType; return this; }
        public Builder routingKey(String routingKey) { this.routingKey = routingKey; return this; }
        public Builder payload(String payload) { this.payload = payload; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder retryCount(int retryCount) { this.retryCount = retryCount; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder sentAt(LocalDateTime sentAt) { this.sentAt = sentAt; return this; }
        public Builder errorMessage(String errorMessage) { this.errorMessage = errorMessage; return this; }
        public Builder lockedBy(String lockedBy) { this.lockedBy = lockedBy; return this; }
        public Builder lockedAt(LocalDateTime lockedAt) { this.lockedAt = lockedAt; return this; }
        public Builder nextAttemptAt(LocalDateTime nextAttemptAt) { this.nextAttemptAt = nextAttemptAt; return this; }

        public EventOutboxJpaEntity build() {
            return new EventOutboxJpaEntity(id, eventId, eventType, routingKey, payload, status, retryCount, createdAt, sentAt, errorMessage, lockedBy, lockedAt, nextAttemptAt);
        }
    }
}
