package com.fip.plugin.adapters.in.web;

import com.fip.common.response.StandardResponse;
import com.fip.plugin.domain.model.PluginMetadata;
import com.fip.plugin.ports.in.PluginLifecycleUseCase;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/v1/plugins")
@Tag(name = "Plugin Management", description = "Endpoints for registering, activating, executing, and auditing analytics and AI plugins")
public class PluginController {

    private final PluginLifecycleUseCase pluginLifecycleUseCase;

    public PluginController(PluginLifecycleUseCase pluginLifecycleUseCase) {
        this.pluginLifecycleUseCase = pluginLifecycleUseCase;
    }

    public record ExecuteRequest(String action, String payload) {
    }

    @PostMapping
    @Operation(
            summary = "Register a new financial plugin",
            description = "Registers plugin metadata, validates bytecode compliance with FipPlugin SDK, and activates the sandbox.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Plugin successfully registered"),
                    @ApiResponse(responseCode = "400", description = "Validation failure or duplicate plugin",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<PluginMetadata>> register(
            @Valid @RequestBody PluginLifecycleUseCase.Command command) {
        PluginMetadata registered = pluginLifecycleUseCase.registerPlugin(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StandardResponse.success(registered, "Plugin successfully registered"));
    }

    @GetMapping
    @Operation(summary = "List all registered plugins")
    public ResponseEntity<StandardResponse<List<PluginMetadata>>> getAll() {
        List<PluginMetadata> plugins = pluginLifecycleUseCase.getPlugins();
        return ResponseEntity.ok(StandardResponse.success(plugins));
    }

    @GetMapping("/{name}")
    @Operation(summary = "Get plugin details by name")
    public ResponseEntity<StandardResponse<PluginMetadata>> getByName(@PathVariable String name) {
        PluginMetadata plugin = pluginLifecycleUseCase.getPlugin(name);
        return ResponseEntity.ok(StandardResponse.success(plugin));
    }

    @PutMapping("/{name}/activate")
    @Operation(summary = "Activate a suspended or registered plugin")
    public ResponseEntity<StandardResponse<PluginMetadata>> activate(@PathVariable String name) {
        PluginMetadata updated = pluginLifecycleUseCase.activatePlugin(name);
        return ResponseEntity.ok(StandardResponse.success(updated, "Plugin activated successfully"));
    }

    @PutMapping("/{name}/suspend")
    @Operation(summary = "Suspend an active plugin")
    public ResponseEntity<StandardResponse<PluginMetadata>> suspend(@PathVariable String name) {
        PluginMetadata updated = pluginLifecycleUseCase.suspendPlugin(name);
        return ResponseEntity.ok(StandardResponse.success(updated, "Plugin suspended successfully"));
    }

    @PostMapping("/{name}/execute")
    @Operation(
            summary = "Execute an active plugin in sandbox environment",
            description = "Invokes the plugin action, captures telemetry, and logs audit events to RabbitMQ.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Plugin executed successfully"),
                    @ApiResponse(responseCode = "400", description = "Plugin inactive or invalid payload",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "404", description = "Plugin not found",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<String>> execute(
            @PathVariable String name,
            @RequestBody ExecuteRequest request,
            @RequestHeader(value = "X-Tenant-Id", required = false, defaultValue = "default") String tenantId,
            @RequestHeader(value = "X-User-Subject", required = false, defaultValue = "anonymous") String user) {
        String result = pluginLifecycleUseCase.executePlugin(
                name,
                request.action(),
                request.payload(),
                tenantId,
                user
        );
        return ResponseEntity.ok(StandardResponse.success(result, "Plugin execution finished"));
    }
}
