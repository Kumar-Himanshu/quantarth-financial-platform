package com.fip.eventengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.exception.ResourceNotFoundException;
import com.fip.common.tenant.TenantContext;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.in.EventIngestionUseCase;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.eventengine.ports.out.OutboxPort;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EventTopics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Service
@Transactional
public class EventIngestionService implements EventIngestionUseCase {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private final EventStorePort eventStorePort;
    private final OutboxPort outboxPort;
    private final ObjectMapper objectMapper;

    public EventIngestionService(EventStorePort eventStorePort, OutboxPort outboxPort, ObjectMapper objectMapper) {
        this.eventStorePort = eventStorePort;
        this.outboxPort = outboxPort;
        this.objectMapper = objectMapper;
    }

    @Override
    public FinancialEvent ingestAndPublish(Command command) {
        UUID eventId = UUID.randomUUID();
        String traceId = command.traceId() != null ? command.traceId() : UUID.randomUUID().toString();
        // SECURITY: Tenant is strictly derived from the validated TenantContext
        String tenantId = TenantContext.getCurrentTenant();
        String version = command.version() != null ? command.version() : "1.0";

        FinancialEvent domain = FinancialEvent.fromCanonical(
                eventId,
                command.eventType(),
                command.eventSource(),
                traceId,
                tenantId,
                Instant.now(),
                command.payload(),
                version
        );

        FinancialEvent saved = eventStorePort.save(domain);
        log.info("Persisted financial event [{}] type [{}] for tenant [{}]",
                saved.getEventId(), saved.getEventType(), saved.getTenantId());

        // RELIABILITY (P0-003): Atomic Transactional Outbox persistence.
        // Business operation and outgoing event are committed in the same database transaction.
        CanonicalEvent canonical = new CanonicalEvent(
                saved.getEventId(),
                saved.getEventType(),
                saved.getEventSource(),
                Instant.now(),
                saved.getPayload(),
                saved.getVersion(),
                saved.getTraceId(),
                saved.getTenantId(),
                command.metadata()
        );

        String routingKey = resolveRoutingKey(saved.getEventType());
        try {
            String payloadJson = objectMapper.writeValueAsString(canonical);
            outboxPort.save(saved.getEventId(), saved.getEventType(), routingKey, payloadJson);
            log.info("Enqueued event [{}] to transactional outbox with routingKey [{}]",
                    saved.getEventId(), routingKey);
        } catch (Exception ex) {
            log.error("Failed to serialize canonical event for outbox [{}]", saved.getEventId(), ex);
            throw new RuntimeException("Failed to enqueue event to transactional outbox", ex);
        }

        return saved;
    }

    private String resolveRoutingKey(String eventType) {
        if (eventType == null) {
            return "financial.events.general";
        }
        if (eventType.startsWith("User")) {
            return EventTopics.ROUTING_KEY_AUTH;
        }
        if (eventType.startsWith("Plugin")) {
            return EventTopics.ROUTING_KEY_PLUGIN;
        }
        if (eventType.startsWith("AI") || eventType.startsWith("Doc")) {
            return EventTopics.ROUTING_KEY_AI;
        }
        return "financial.events." + eventType.toLowerCase();
    }

    @Override
    public List<FinancialEvent> ingestBatch(List<Command> commands) {
        List<FinancialEvent> savedList = new ArrayList<>();
        for (Command command : commands) {
            savedList.add(ingestAndPublish(command));
        }
        return savedList;
    }

    @Override
    @Transactional(readOnly = true)
    public FinancialEvent getEventById(UUID eventId) {
        String currentTenant = TenantContext.getCurrentTenant();
        // SECURITY: Strict multi-tenant isolation. A tenant cannot access another tenant's records.
        return eventStorePort.findByEventIdAndTenantId(eventId, currentTenant)
                .orElseThrow(() -> new ResourceNotFoundException("Financial Event not found: " + eventId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinancialEvent> searchEvents(String eventType, String eventSource) {
        String currentTenant = TenantContext.getCurrentTenant();
        return eventStorePort.search(eventType, eventSource, currentTenant);
    }
}
