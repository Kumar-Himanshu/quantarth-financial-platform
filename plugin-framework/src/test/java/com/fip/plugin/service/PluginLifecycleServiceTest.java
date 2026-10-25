package com.fip.plugin.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.exception.BadRequestException;
import com.fip.plugin.domain.model.PluginMetadata;
import com.fip.plugin.ports.in.PluginLifecycleUseCase;
import com.fip.plugin.ports.out.PluginEventPublisherPort;
import com.fip.plugin.ports.out.PluginPersistencePort;
import com.fip.shared.events.CanonicalEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PluginLifecycleServiceTest {

    @Mock
    private PluginPersistencePort persistencePort;

    @Mock
    private PluginEventPublisherPort publisherPort;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private PluginLifecycleService service;

    private PluginLifecycleUseCase.Command registerCommand;

    @BeforeEach
    void setUp() {
        registerCommand = new PluginLifecycleUseCase.Command(
                "ArthQuantRiskEngine",
                "1.0.0",
                "QuantArth",
                "Advanced financial risk calculation plugin",
                "com.quantarth.RiskEngine",
                "/opt/plugins/risk-engine.jar",
                "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
                "RISK,ANALYTICS"
        );
    }

    @Test
    @DisplayName("registerPlugin should save plugin and publish event")
    void registerPlugin_shouldSaveAndPublishEvent() throws Exception {
        when(persistencePort.findByName("ArthQuantRiskEngine")).thenReturn(Optional.empty());

        PluginMetadata saved = PluginMetadata.createNew(
                registerCommand.name(), registerCommand.version(), registerCommand.provider(),
                registerCommand.description(), registerCommand.mainClass(), registerCommand.jarPath(),
                registerCommand.checksumSha256(), registerCommand.capabilities()
        ).withStatus("ACTIVE");

        when(persistencePort.save(any(PluginMetadata.class))).thenReturn(saved);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        PluginMetadata result = service.registerPlugin(registerCommand);

        assertThat(result.getName()).isEqualTo("ArthQuantRiskEngine");
        assertThat(result.getStatus()).isEqualTo("ACTIVE");
        verify(persistencePort).save(any(PluginMetadata.class));
        verify(publisherPort).publish(any(CanonicalEvent.class));
    }

    @Test
    @DisplayName("registerPlugin should throw BadRequestException if plugin name already exists")
    void registerPlugin_duplicateName_shouldThrow() {
        PluginMetadata existing = PluginMetadata.createNew(
                "ArthQuantRiskEngine", "1.0", "P", "D", "M", "J", "C", "CAP"
        );
        when(persistencePort.findByName("ArthQuantRiskEngine")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.registerPlugin(registerCommand))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already registered");
    }

    @Test
    @DisplayName("executePlugin should execute in sandbox and return output")
    void executePlugin_activePlugin_shouldExecute() {
        PluginMetadata meta = PluginMetadata.createNew(
                "ArthQuantRiskEngine", "1.0.0", "QuantArth", "Desc", "Main", "Path", "Hash", "RISK"
        ).withStatus("ACTIVE");

        when(persistencePort.findByName("ArthQuantRiskEngine")).thenReturn(Optional.of(meta));

        String result = service.executePlugin(
                "ArthQuantRiskEngine", "ANALYZE_RISK", "{\"portfolio\": \"alpha\"}", "tenant-1", "analyst_1"
        );

        assertThat(result).contains("Sandboxed execution of plugin [ArthQuantRiskEngine:v1.0.0] completed");
        assertThat(result).contains("ANALYZE_RISK");
    }

    @Test
    @DisplayName("activatePlugin and suspendPlugin should update status and publish events")
    void lifecycleTransitions_shouldUpdateStatus() throws Exception {
        PluginMetadata meta = PluginMetadata.createNew(
                "PluginA", "1.0", "P", "D", "M", "J", "C", "CAP"
        ).withStatus("REGISTERED");

        when(persistencePort.findByName("PluginA")).thenReturn(Optional.of(meta));
        when(persistencePort.save(any(PluginMetadata.class))).thenAnswer(i -> i.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        PluginMetadata activated = service.activatePlugin("PluginA");
        assertThat(activated.getStatus()).isEqualTo("ACTIVE");

        PluginMetadata suspended = service.suspendPlugin("PluginA");
        assertThat(suspended.getStatus()).isEqualTo("SUSPENDED");
    }
}
