package com.fip.eventengine.ports.out;

import com.fip.eventengine.domain.model.FinancialEvent;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventStorePort {
    FinancialEvent save(FinancialEvent event);

    Optional<FinancialEvent> findByEventId(UUID eventId);

    Optional<FinancialEvent> findByEventIdAndTenantId(UUID eventId, String tenantId);

    List<FinancialEvent> search(String eventType, String eventSource, String tenantId);

    boolean existsByEventId(UUID eventId);

    Optional<FinancialEvent> findByIdempotencyKey(String tenantId, String sourceSystem, String idempotencyKey);

    Page<FinancialEvent> searchCanonical(String tenantId, String entityType, String entityId,
                                         String eventType, String sourceSystem,
                                         LocalDateTime fromDate, LocalDateTime toDate,
                                         Pageable pageable);

    Optional<FinancialEvent> findPaymentForUpdate(String tenantId, String paymentId);

    List<FinancialEvent> findRefundsByPaymentId(String tenantId, String paymentId);

    List<FinancialEvent> findRefundsByRefundId(String tenantId, String refundId);
}
