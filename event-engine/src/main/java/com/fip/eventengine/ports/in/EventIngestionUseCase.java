package com.fip.eventengine.ports.in;

import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.shared.events.CanonicalEvent;
import jakarta.validation.constraints.NotBlank;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EventIngestionUseCase {

    FinancialEvent ingestAndPublish(Command command);

    List<FinancialEvent> ingestBatch(List<Command> commands);

    FinancialEvent getEventById(UUID eventId);

    List<FinancialEvent> searchEvents(String eventType, String eventSource);

    CanonicalEvent ingestCanonical(CanonicalEvent canonical);

    List<CanonicalEvent> ingestCanonicalBatch(List<CanonicalEvent> commands);

    Page<CanonicalEvent> searchCanonical(String entityType, String entityId, String eventType,
                                         String sourceSystem, LocalDateTime fromDate, LocalDateTime toDate,
                                         Pageable pageable);

    record Command(
            @NotBlank(message = "Event type is required")
            String eventType,

            @NotBlank(message = "Event source is required")
            String eventSource,

            @NotBlank(message = "Payload is required")
            String payload,

            String traceId,
            String version,
            Map<String, Object> metadata,
            String entityType,
            String entityId,
            String idempotencyKey,
            String sourceSystem
    ) {
        public Command(String eventType, String eventSource, String payload, String traceId, String version, Map<String, Object> metadata) {
            this(eventType, eventSource, payload, traceId, version, metadata, null, null, null, eventSource);
        }
    }
}
