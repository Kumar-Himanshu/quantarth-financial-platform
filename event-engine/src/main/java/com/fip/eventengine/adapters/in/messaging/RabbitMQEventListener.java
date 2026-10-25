package com.fip.eventengine.adapters.in.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class RabbitMQEventListener {

    private static final Logger log = LoggerFactory.getLogger(RabbitMQEventListener.class);

    private final EventStorePort eventStorePort;
    private final ObjectMapper objectMapper;

    public RabbitMQEventListener(EventStorePort eventStorePort, ObjectMapper objectMapper) {
        this.eventStorePort = eventStorePort;
        this.objectMapper = objectMapper;
    }

    @RabbitListener(queues = {
            EventTopics.AUDIT_QUEUE,
            EventTopics.AUTH_QUEUE,
            EventTopics.PLUGIN_QUEUE,
            EventTopics.EVENT_QUEUE,
            EventTopics.AI_QUEUE
    })
    public void onEventReceived(String message) {
        log.debug("Received raw AMQP message: {}", message);

        CanonicalEvent canonical;
        try {
            canonical = objectMapper.readValue(message, CanonicalEvent.class);
        } catch (JsonProcessingException e) {
            log.error("POISON PILL DETECTED: Unparseable event payload. Rejecting directly to DLQ without requeue. Payload: {}", message, e);
            // Non-recoverable malformed message -> reject directly to dead-letter queue (x-dead-letter-exchange)
            throw new AmqpRejectAndDontRequeueException("Malformed JSON payload, routing to Dead-Letter Queue", e);
        }

        if (canonical.eventId() == null || canonical.eventType() == null) {
            log.error("POISON PILL DETECTED: Missing mandatory eventId or eventType. Routing to DLQ. Event: {}", canonical);
            throw new AmqpRejectAndDontRequeueException("Mandatory contract attributes missing, routing to Dead-Letter Queue");
        }

        // IDEMPOTENCY GUARD: Skip duplicate events safely without re-processing or failing
        if (eventStorePort.existsByEventId(canonical.eventId())) {
            log.warn("IDEMPOTENCY: Event [{}] of type [{}] already processed. Skipping duplicate delivery.",
                    canonical.eventId(), canonical.eventType());
            return;
        }

        try {
            FinancialEvent domain = FinancialEvent.fromCanonical(
                    canonical.eventId(),
                    canonical.eventType(),
                    canonical.eventSource(),
                    canonical.traceId(),
                    canonical.tenantId(),
                    canonical.timestamp(),
                    canonical.payload(),
                    canonical.version()
            );

            FinancialEvent saved = eventStorePort.save(domain);
            log.info("Successfully persisted and audited event [{}] type [{}] for tenant [{}]",
                    saved.getEventId(), saved.getEventType(), saved.getTenantId());

        } catch (org.springframework.dao.DataIntegrityViolationException dive) {
            log.warn("IDEMPOTENCY: Concurrent duplicate delivery detected via database constraint for event [{}]. Acknowledged and skipped.",
                    canonical.eventId());
        } catch (Exception e) {
            log.error("Transient error during event persistence for event [{}]. Propagating for bounded retry.",
                    canonical.eventId(), e);
            // Transient database/network error -> propagate to trigger Spring AMQP retry policy
            throw new RuntimeException("Transient processing failure for event " + canonical.eventId(), e);
        }
    }
}
