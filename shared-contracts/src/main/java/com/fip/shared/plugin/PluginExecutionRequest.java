package com.fip.shared.plugin;

import java.util.Map;

public record PluginExecutionRequest(
        String action,
        String payload,
        Map<String, Object> parameters
) {
    public static PluginExecutionRequest of(String action, String payload) {
        return new PluginExecutionRequest(action, payload, Map.of());
    }
}
