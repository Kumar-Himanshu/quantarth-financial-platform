package com.fip.shared.plugin;

public interface FipPlugin {

    PluginManifest getManifest();

    default void initialize(PluginContext context) {
        // Default lifecycle hook
    }

    PluginExecutionResult execute(PluginExecutionRequest request, PluginContext context);

    default void destroy() {
        // Default lifecycle cleanup hook
    }
}
