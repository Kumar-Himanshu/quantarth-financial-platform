package com.fip.eventengine.adapters.out.persistence;

import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.out.EventStorePort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class EventPersistenceAdapter implements EventStorePort {

    private final FinancialEventJpaRepository repository;
    private final FinancialEventMapper mapper;

    public EventPersistenceAdapter(FinancialEventJpaRepository repository,
                                   FinancialEventMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public FinancialEvent save(FinancialEvent event) {
        FinancialEventJpaEntity entity = mapper.toEntity(event);
        FinancialEventJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<FinancialEvent> findByEventId(UUID eventId) {
        return repository.findByEventId(eventId).map(mapper::toDomain);
    }

    @Override
    public Optional<FinancialEvent> findByEventIdAndTenantId(UUID eventId, String tenantId) {
        return repository.findByEventIdAndTenantId(eventId, tenantId).map(mapper::toDomain);
    }

    @Override
    public List<FinancialEvent> search(String eventType, String eventSource, String tenantId) {
        return repository.searchEvents(eventType, eventSource, tenantId)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public boolean existsByEventId(UUID eventId) {
        return repository.existsByEventId(eventId);
    }
}
