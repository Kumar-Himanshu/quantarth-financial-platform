package com.fip.shared.plugin;

import java.util.Map;

public record PluginContext(
        String executionId,
        String tenantId,
        String requestedBy,
        Map<String, String> headers,
        Map<String, Object> environmentConfig
) {
}
