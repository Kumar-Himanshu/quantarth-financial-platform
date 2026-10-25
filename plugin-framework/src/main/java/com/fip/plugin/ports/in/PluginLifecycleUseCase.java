package com.fip.plugin.ports.in;

import com.fip.plugin.domain.model.PluginMetadata;
import jakarta.validation.constraints.NotBlank;
import java.util.List;

public interface PluginLifecycleUseCase {

    PluginMetadata registerPlugin(Command command);

    PluginMetadata activatePlugin(String name);

    PluginMetadata suspendPlugin(String name);

    String executePlugin(String name, String action, String payload, String tenantId, String user);

    List<PluginMetadata> getPlugins();

    PluginMetadata getPlugin(String name);

    record Command(
            @NotBlank(message = "Plugin name is required")
            String name,

            @NotBlank(message = "Plugin version is required")
            String version,

            String provider,
            String description,

            @NotBlank(message = "Main class fully qualified name is required")
            String mainClass,

            @NotBlank(message = "JAR path is required")
            String jarPath,

            String checksumSha256,
            String capabilities
    ) {
    }
}
