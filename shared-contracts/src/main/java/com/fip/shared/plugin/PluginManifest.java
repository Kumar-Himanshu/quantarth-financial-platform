package com.fip.shared.plugin;

import java.util.List;
import java.util.Map;

public record PluginManifest(
        String id,
        String name,
        String version,
        String provider,
        String description,
        String mainClass,
        List<String> capabilities,
        List<String> requiredPermissions,
        Map<String, Object> configurationSchema
) {
}
