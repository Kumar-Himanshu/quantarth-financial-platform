package com.fip.eventengine.ports.in;

import com.fip.eventengine.domain.model.FinancialEvent;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface EventIngestionUseCase {

    FinancialEvent ingestAndPublish(Command command);

    List<FinancialEvent> ingestBatch(List<Command> commands);

    FinancialEvent getEventById(UUID eventId);

    List<FinancialEvent> searchEvents(String eventType, String eventSource);

    record Command(
            @NotBlank(message = "Event type is required")
            String eventType,

            @NotBlank(message = "Event source is required")
            String eventSource,

            @NotBlank(message = "Payload is required")
            String payload,

            String traceId,
            String version,
            Map<String, Object> metadata
    ) {
    }
}
