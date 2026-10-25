package com.fip.eventengine.service;

import com.fip.eventengine.adapters.out.persistence.EventOutboxJpaEntity;
import com.fip.eventengine.adapters.out.persistence.EventOutboxJpaRepository;
import com.fip.shared.events.EventTopics;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.core.ReturnedMessage;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * High-reliability Transactional Outbox Polling Publisher.
 * Features:
 * - RabbitMQ Publisher Confirmations (CorrelationData future verification)
 * - Mandatory return detection for unroutable messages
 * - Safe concurrent worker coordination with leasing (locked_by, locked_at)
 * - Worker crash and abandoned claim recovery
 * - Bounded retry with exponential backoff (1s, 2s, 4s, 8s, 16s...)
 * - Operational recovery mechanism for failed outbox entries
 * - Durable consumer idempotency support
 */
@Service
public class OutboxPollingPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPollingPublisher.class);

    public static final int MAX_RETRIES = 5;
    public static final int BATCH_SIZE = 50;
    public static final long CLAIM_TIMEOUT_SECONDS = 60L; // Abandoned claim threshold
    public static final long INITIAL_BACKOFF_MS = 1000L;
    public static final long MAX_BACKOFF_MS = 60_000L;

    private final String workerId = "outbox-worker-" + UUID.randomUUID().toString().substring(0, 8);

    private final EventOutboxJpaRepository outboxRepository;
    private final RabbitTemplate rabbitTemplate;

    @Value("${fip.outbox.publisher-confirms-enabled:true}")
    private boolean publisherConfirmsEnabled;

    @Value("${fip.outbox.confirm-timeout-ms:5000}")
    private long confirmTimeoutMs;

    public OutboxPollingPublisher(EventOutboxJpaRepository outboxRepository, RabbitTemplate rabbitTemplate) {
        this.outboxRepository = outboxRepository;
        this.rabbitTemplate = rabbitTemplate;
    }

    @PostConstruct
    public void configureRabbitTemplate() {
        try {
            rabbitTemplate.setMandatory(true);
            rabbitTemplate.setReturnsCallback((ReturnedMessage returned) -> {
                log.error("UNROUTABLE OUTBOX MESSAGE DETECTED: replyCode=[{}] replyText=[{}] exchange=[{}] routingKey=[{}]",
                        returned.getReplyCode(), returned.getReplyText(), returned.getExchange(), returned.getRoutingKey());
            });
        } catch (Exception e) {
            log.warn("Could not register ReturnsCallback on RabbitTemplate (might be mock): {}", e.getMessage());
        }
    }

    public String getWorkerId() {
        return workerId;
    }

    public void setPublisherConfirmsEnabled(boolean publisherConfirmsEnabled) {
        this.publisherConfirmsEnabled = publisherConfirmsEnabled;
    }

    public void setConfirmTimeoutMs(long confirmTimeoutMs) {
        this.confirmTimeoutMs = confirmTimeoutMs;
    }

    @Scheduled(fixedDelayString = "${fip.outbox.polling-interval-ms:1000}")
    @Transactional
    public void scheduledPublish() {
        publishPendingEvents();
    }

    /**
     * Atomically claims a batch of claimable outbox records and publishes them with publisher confirmations.
     */
    @Transactional
    public int publishPendingEvents() {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime staleThreshold = now.minusSeconds(CLAIM_TIMEOUT_SECONDS);

        // 1. Claim eligible pending or crashed/abandoned processing records
        List<EventOutboxJpaEntity> claimable = outboxRepository.findClaimableRecords(
                now, staleThreshold, PageRequest.of(0, BATCH_SIZE)
        );

        if (claimable.isEmpty()) {
            return 0;
        }

        // 2. Mark claimed records as PROCESSING under this worker's lease
        for (EventOutboxJpaEntity entry : claimable) {
            entry.setStatus("PROCESSING");
            entry.setLockedBy(workerId);
            entry.setLockedAt(now);
        }
        outboxRepository.saveAll(claimable);

        int publishedCount = 0;
        for (EventOutboxJpaEntity entry : claimable) {
            CorrelationData correlationData = new CorrelationData(entry.getId().toString());

            try {
                // 3. Publish to broker with correlation data
                rabbitTemplate.convertAndSend(EventTopics.EXCHANGE, entry.getRoutingKey(), entry.getPayload(), correlationData);

                // 4. Verify publisher confirmation from broker
                if (publisherConfirmsEnabled) {
                    CorrelationData.Confirm confirm = correlationData.getFuture().get(confirmTimeoutMs, TimeUnit.MILLISECONDS);
                    if (confirm == null || !confirm.isAck()) {
                        String reason = confirm != null ? confirm.getReason() : "Broker NACK received (empty reason)";
                        throw new IllegalStateException("Broker NACK for event [" + entry.getEventId() + "]: " + reason);
                    }
                }

                // 5. Verified acceptance -> mark SENT
                entry.setStatus("SENT");
                entry.setSentAt(LocalDateTime.now());
                entry.setLockedBy(null);
                entry.setLockedAt(null);
                entry.setErrorMessage(null);
                outboxRepository.save(entry);
                publishedCount++;
                log.info("Outbox verified broker ACK for event [{}] routingKey [{}] worker [{}]",
                        entry.getEventId(), entry.getRoutingKey(), workerId);

            } catch (Exception ex) {
                // 6. Publication or Confirmation failure -> apply bounded retry with exponential backoff
                int retries = entry.getRetryCount() + 1;
                entry.setRetryCount(retries);
                entry.setLockedBy(null);
                entry.setLockedAt(null);

                if (retries >= MAX_RETRIES) {
                    entry.setStatus("FAILED");
                    entry.setErrorMessage("Max retries (" + MAX_RETRIES + ") exceeded: " + ex.getMessage());
                    log.error("Outbox event [{}] marked FAILED after {} retries: {}",
                            entry.getEventId(), retries, ex.getMessage());
                } else {
                    entry.setStatus("PENDING");
                    long backoffMs = Math.min(MAX_BACKOFF_MS, INITIAL_BACKOFF_MS * (1L << (retries - 1)));
                    entry.setNextAttemptAt(LocalDateTime.now().plus(backoffMs, ChronoUnit.MILLIS));
                    entry.setErrorMessage(ex.getMessage());
                    log.warn("Outbox event [{}] transient publication failure (attempt {}/{}). Retrying at {}: {}",
                            entry.getEventId(), retries, MAX_RETRIES, entry.getNextAttemptAt(), ex.getMessage());
                }
                outboxRepository.save(entry);
            }
        }

        return publishedCount;
    }

    /**
     * Operational recovery: re-queues all FAILED outbox messages for publication.
     */
    @Transactional
    public int recoverFailedRecords() {
        List<EventOutboxJpaEntity> failedRecords = outboxRepository.findByStatus("FAILED");
        if (failedRecords.isEmpty()) {
            return 0;
        }

        for (EventOutboxJpaEntity entry : failedRecords) {
            entry.setStatus("PENDING");
            entry.setRetryCount(0);
            entry.setNextAttemptAt(null);
            entry.setLockedBy(null);
            entry.setLockedAt(null);
            entry.setErrorMessage("Manually recovered by operator at " + LocalDateTime.now());
        }
        outboxRepository.saveAll(failedRecords);
        log.info("Operational recovery: Re-queued {} failed outbox records", failedRecords.size());
        return failedRecords.size();
    }
}
