package com.fip.eventengine.adapters.in.messaging;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.shared.events.CanonicalEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RabbitMQEventListenerTest {

    @Mock
    private EventStorePort eventStorePort;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private RabbitMQEventListener listener;

    private CanonicalEvent testCanonical;
    private String validJson;

    @BeforeEach
    void setUp() {
        testCanonical = new CanonicalEvent(
                UUID.randomUUID(),
                "DocumentValidationJobCreated",
                "document-ai-saas",
                Instant.now(),
                "{\"documentId\": \"doc-990\", \"amount\": 15000.0}",
                "1.0",
                "trace-12345",
                "tenant-alpha",
                null
        );
        validJson = "{\"eventType\":\"DocumentValidationJobCreated\"}";
    }

    @Test
    @DisplayName("onEventReceived should parse, check idempotency, and persist valid event")
    void onEventReceived_newUniqueEvent_shouldPersist() throws Exception {
        when(objectMapper.readValue(validJson, CanonicalEvent.class)).thenReturn(testCanonical);
        when(eventStorePort.existsByEventId(testCanonical.eventId())).thenReturn(false);

        FinancialEvent mockSaved = FinancialEvent.fromCanonical(
                testCanonical.eventId(),
                testCanonical.eventType(),
                testCanonical.eventSource(),
                testCanonical.traceId(),
                testCanonical.tenantId(),
                testCanonical.timestamp(),
                testCanonical.payload(),
                testCanonical.version()
        );
        when(eventStorePort.save(any(FinancialEvent.class))).thenReturn(mockSaved);

        listener.onEventReceived(validJson);

        verify(eventStorePort, times(1)).save(any(FinancialEvent.class));
    }

    @Test
    @DisplayName("onEventReceived should skip duplicate events without error (Idempotency Guard)")
    void onEventReceived_duplicateEvent_shouldSkipSafely() throws Exception {
        when(objectMapper.readValue(validJson, CanonicalEvent.class)).thenReturn(testCanonical);
        when(eventStorePort.existsByEventId(testCanonical.eventId())).thenReturn(true);

        listener.onEventReceived(validJson);

        // Verification: existing event is never saved again
        verify(eventStorePort, never()).save(any(FinancialEvent.class));
    }

    @Test
    @DisplayName("onEventReceived should throw AmqpRejectAndDontRequeueException on malformed JSON (DLQ Routing)")
    void onEventReceived_malformedJson_shouldRejectToDeadLetterQueue() throws Exception {
        String poisonPill = "{ malformed_json: bad_payload }}}";
        when(objectMapper.readValue(poisonPill, CanonicalEvent.class))
                .thenThrow(new JsonProcessingException("Cannot parse JSON") {});

        assertThatThrownBy(() -> listener.onEventReceived(poisonPill))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("routing to Dead-Letter Queue");

        verify(eventStorePort, never()).save(any());
    }

    @Test
    @DisplayName("onEventReceived should throw AmqpRejectAndDontRequeueException if mandatory eventId is missing (DLQ Routing)")
    void onEventReceived_missingMandatoryId_shouldRejectToDeadLetterQueue() throws Exception {
        CanonicalEvent missingIdEvent = new CanonicalEvent(
                null, "DocumentValidationJobCreated", "doc-ai", Instant.now(), "{}", "1.0", "t", "tenant", null
        );
        when(objectMapper.readValue(validJson, CanonicalEvent.class)).thenReturn(missingIdEvent);

        assertThatThrownBy(() -> listener.onEventReceived(validJson))
                .isInstanceOf(AmqpRejectAndDontRequeueException.class)
                .hasMessageContaining("Mandatory contract attributes missing");

        verify(eventStorePort, never()).save(any());
    }

    @Test
    @DisplayName("onEventReceived should throw RuntimeException on transient DB error to trigger retry policy")
    void onEventReceived_transientDatabaseError_shouldThrowForRetry() throws Exception {
        when(objectMapper.readValue(validJson, CanonicalEvent.class)).thenReturn(testCanonical);
        when(eventStorePort.existsByEventId(testCanonical.eventId())).thenReturn(false);
        when(eventStorePort.save(any(FinancialEvent.class)))
                .thenThrow(new RuntimeException("Database connection timeout"));

        assertThatThrownBy(() -> listener.onEventReceived(validJson))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Transient processing failure");
    }
}
