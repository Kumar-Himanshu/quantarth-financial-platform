package com.fip.shared.plugin;

import java.util.Map;

public record PluginExecutionResult(
        boolean success,
        String output,
        long executionTimeMs,
        String errorMessage,
        Map<String, Object> outputMetadata
) {
    public static PluginExecutionResult success(String output, long executionTimeMs) {
        return new PluginExecutionResult(true, output, executionTimeMs, null, Map.of());
    }

    public static PluginExecutionResult failure(String errorMessage, long executionTimeMs) {
        return new PluginExecutionResult(false, null, executionTimeMs, errorMessage, Map.of());
    }
}
