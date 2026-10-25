package com.fip.eventengine.ports.out;

import java.util.UUID;

public interface OutboxPort {
    void save(UUID eventId, String eventType, String routingKey, String payload);
}
