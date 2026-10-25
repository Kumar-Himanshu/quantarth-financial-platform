package com.fip.eventengine.ports.out;

import com.fip.eventengine.domain.model.FinancialEvent;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EventStorePort {
    FinancialEvent save(FinancialEvent event);

    Optional<FinancialEvent> findByEventId(UUID eventId);

    Optional<FinancialEvent> findByEventIdAndTenantId(UUID eventId, String tenantId);

    List<FinancialEvent> search(String eventType, String eventSource, String tenantId);

    boolean existsByEventId(UUID eventId);
}
