package com.fip.plugin.ports.out;

import com.fip.shared.events.CanonicalEvent;

public interface PluginEventPublisherPort {
    void publish(CanonicalEvent event);
}
