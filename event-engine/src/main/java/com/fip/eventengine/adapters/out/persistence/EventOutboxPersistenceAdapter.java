package com.fip.eventengine.adapters.out.persistence;

import com.fip.eventengine.ports.out.OutboxPort;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class EventOutboxPersistenceAdapter implements OutboxPort {

    private final EventOutboxJpaRepository repository;

    public EventOutboxPersistenceAdapter(EventOutboxJpaRepository repository) {
        this.repository = repository;
    }

    @Override
    public void save(UUID eventId, String eventType, String routingKey, String payload) {
        EventOutboxJpaEntity entity = EventOutboxJpaEntity.builder()
                .id(UUID.randomUUID())
                .eventId(eventId)
                .eventType(eventType)
                .routingKey(routingKey)
                .payload(payload)
                .status("PENDING")
                .retryCount(0)
                .createdAt(LocalDateTime.now())
                .build();
        repository.save(entity);
    }
}
