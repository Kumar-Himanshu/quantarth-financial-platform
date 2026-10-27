package com.fip.eventengine.adapters.out.persistence;

import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.out.EventStorePort;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
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

    @Override
    public Optional<FinancialEvent> findByIdempotencyKey(String tenantId, String sourceSystem, String idempotencyKey) {
        return repository.findByTenantIdAndSourceSystemAndIdempotencyKey(tenantId, sourceSystem, idempotencyKey)
                .map(mapper::toDomain);
    }

    @Override
    public Page<FinancialEvent> searchCanonical(String tenantId, String entityType, String entityId,
                                                String eventType, String sourceSystem,
                                                LocalDateTime fromDate, LocalDateTime toDate,
                                                Pageable pageable) {
        return repository.searchCanonicalEvents(tenantId, entityType, entityId, eventType, sourceSystem, fromDate, toDate, pageable)
                .map(mapper::toDomain);
    }

    @Override
    public Optional<FinancialEvent> findPaymentForUpdate(String tenantId, String paymentId) {
        return repository.findPaymentForUpdate(tenantId, paymentId).map(mapper::toDomain);
    }

    @Override
    public List<FinancialEvent> findRefundsByPaymentId(String tenantId, String paymentId) {
        return repository.findRefundsByPaymentId(tenantId, paymentId)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }

    @Override
    public List<FinancialEvent> findRefundsByRefundId(String tenantId, String refundId) {
        return repository.findRefundsByRefundId(tenantId, refundId)
                .stream()
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }
}
