package com.fip.eventengine.ports.out;

import com.fip.shared.events.CanonicalEvent;

public interface EventPublishingPort {
    void publish(CanonicalEvent event);
}
