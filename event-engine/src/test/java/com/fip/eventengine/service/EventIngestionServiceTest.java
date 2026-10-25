package com.fip.eventengine.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.exception.ResourceNotFoundException;
import com.fip.common.tenant.TenantContext;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.in.EventIngestionUseCase;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.eventengine.ports.out.OutboxPort;
import com.fip.shared.events.CanonicalEvent;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EventIngestionServiceTest {

    @Mock
    private EventStorePort eventStorePort;

    @Mock
    private OutboxPort outboxPort;

    @Spy
    private ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    @InjectMocks
    private EventIngestionService service;

    private EventIngestionUseCase.Command command;
    private final String testTenant = "tenant-alpha";

    @BeforeEach
    void setUp() {
        TenantContext.setCurrentTenant(testTenant);
        command = new EventIngestionUseCase.Command(
                "DocumentValidationJobCreated",
                "doc-ai-service",
                "{\"documentId\": \"doc-990\", \"amount\": 15000.0}",
                "trace-xyz",
                "1.0",
                Map.of("portfolio", "quant-wealth")
        );
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    @Test
    @DisplayName("ingestAndPublish should force event tenant to TenantContext and persist to transactional outbox")
    void ingestAndPublish_shouldEnforceTenantAndPublish() {
        when(eventStorePort.save(any(FinancialEvent.class))).thenAnswer(i -> i.getArgument(0));

        FinancialEvent result = service.ingestAndPublish(command);

        assertThat(result.getEventType()).isEqualTo("DocumentValidationJobCreated");
        assertThat(result.getEventSource()).isEqualTo("doc-ai-service");
        // Must match TenantContext
        assertThat(result.getTenantId()).isEqualTo(testTenant);
        verify(eventStorePort).save(any(FinancialEvent.class));
        verify(outboxPort).save(any(UUID.class), any(String.class), any(String.class), any(String.class));
    }

    @Test
    @DisplayName("getEventById should succeed when event belongs to caller tenant")
    void getEventById_sameTenant_shouldReturnEvent() {
        UUID eventId = UUID.randomUUID();
        FinancialEvent mockEvent = FinancialEvent.fromCanonical(
                eventId, "DocumentValidationJobCreated", "doc-ai", "trace", testTenant, null, "{}", "1.0"
        );
        when(eventStorePort.findByEventIdAndTenantId(eventId, testTenant)).thenReturn(Optional.of(mockEvent));

        FinancialEvent retrieved = service.getEventById(eventId);

        assertThat(retrieved).isNotNull();
        assertThat(retrieved.getEventId()).isEqualTo(eventId);
        assertThat(retrieved.getTenantId()).isEqualTo(testTenant);
    }

    @Test
    @DisplayName("getEventById should throw ResourceNotFoundException when Tenant A accesses Tenant B event")
    void getEventById_crossTenantAccess_shouldThrowNotFound() {
        UUID eventId = UUID.randomUUID();
        // Repository returns empty because query filters by caller tenant 'tenant-alpha'
        when(eventStorePort.findByEventIdAndTenantId(eventId, testTenant)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getEventById(eventId))
                .isInstanceOf(ResourceNotFoundException.class)
                .hasMessageContaining("Financial Event not found: " + eventId);
    }

    @Test
    @DisplayName("searchEvents should lock search to caller tenant")
    void searchEvents_shouldFilterByCallerTenant() {
        FinancialEvent event = FinancialEvent.fromCanonical(
                UUID.randomUUID(), "DocumentValidationJobCreated", "doc-ai", "trace", testTenant, null, "{}", "1.0"
        );
        when(eventStorePort.search("DocumentValidationJobCreated", "doc-ai", testTenant)).thenReturn(List.of(event));

        List<FinancialEvent> results = service.searchEvents("DocumentValidationJobCreated", "doc-ai");

        assertThat(results).hasSize(1);
        assertThat(results.get(0).getTenantId()).isEqualTo(testTenant);
    }
}
