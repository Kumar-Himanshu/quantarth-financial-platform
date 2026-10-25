package com.fip.shared.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CanonicalEvent(
        UUID eventId,
        String eventType,
        String eventSource,
        Instant timestamp,
        String payload,
        String version,
        String traceId,
        String tenantId,
        Map<String, Object> metadata
) {
    public static final String CURRENT_VERSION = "1.0.0";

    public static CanonicalEvent create(String eventType, String eventSource, String payload) {
        return new CanonicalEvent(
                UUID.randomUUID(),
                eventType,
                eventSource,
                Instant.now(),
                payload,
                CURRENT_VERSION,
                UUID.randomUUID().toString(),
                "default",
                Collections.emptyMap()
        );
    }

    public static CanonicalEvent create(String eventType, String eventSource, String payload, String traceId, String tenantId, Map<String, Object> metadata) {
        return new CanonicalEvent(
                UUID.randomUUID(),
                eventType,
                eventSource,
                Instant.now(),
                payload,
                CURRENT_VERSION,
                traceId != null ? traceId : UUID.randomUUID().toString(),
                tenantId != null ? tenantId : "default",
                metadata != null ? metadata : Collections.emptyMap()
        );
    }
}
