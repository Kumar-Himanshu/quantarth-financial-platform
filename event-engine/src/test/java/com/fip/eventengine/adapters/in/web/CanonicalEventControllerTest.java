package com.fip.eventengine.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.common.exception.ConflictException;
import com.fip.common.exception.ForbiddenException;
import com.fip.common.exception.GlobalExceptionHandler;
import com.fip.eventengine.config.EventEngineExceptionHandler;
import com.fip.eventengine.ports.in.EventIngestionUseCase;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EntityType;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({GlobalExceptionHandler.class, EventEngineExceptionHandler.class})
class CanonicalEventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private EventIngestionUseCase eventIngestionUseCase;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("POST /canonical should return 201 Created with persisted canonical event")
    void testIngestCanonicalSuccess() throws Exception {
        CanonicalEvent incoming = CanonicalEvent.builder()
                .eventId(UUID.randomUUID())
                .eventType("PaymentInitiated")
                .entityType(EntityType.PAYMENT)
                .entityId("PAY-1")
                .sourceSystem("core-payment")
                .payload("{\"paymentId\":\"PAY-1\"}")
                .build();

        when(eventIngestionUseCase.ingestCanonical(any(CanonicalEvent.class))).thenReturn(incoming);

        mockMvc.perform(post("/api/v1/events/canonical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incoming)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value(201))
                .andExpect(jsonPath("$.data.eventId").value(incoming.eventId().toString()))
                .andExpect(jsonPath("$.data.entityType").value("PAYMENT"));
    }

    @Test
    @DisplayName("POST /canonical should return RFC 7807 400 ProblemDetail on financial validation error")
    void testIngestCanonicalValidationErrorReturns400() throws Exception {
        CanonicalEvent incoming = CanonicalEvent.builder()
                .eventType("PaymentInitiated")
                .entityType(EntityType.PAYMENT)
                .payload("{\"paymentId\":\"PAY-1\"}")
                .build();

        when(eventIngestionUseCase.ingestCanonical(any(CanonicalEvent.class)))
                .thenThrow(new FinancialValidationException("Payment amount must be strictly positive"));

        mockMvc.perform(post("/api/v1/events/canonical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incoming)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.title").value("Financial Validation Error"))
                .andExpect(jsonPath("$.detail").value("Payment amount must be strictly positive"));
    }

    @Test
    @DisplayName("POST /canonical should return RFC 7807 409 ProblemDetail on idempotency conflict")
    void testIngestCanonicalIdempotencyConflictReturns409() throws Exception {
        CanonicalEvent incoming = CanonicalEvent.builder()
                .eventType("PaymentInitiated")
                .idempotencyKey("idem-duplicate")
                .payload("{}")
                .build();

        when(eventIngestionUseCase.ingestCanonical(any(CanonicalEvent.class)))
                .thenThrow(new ConflictException("Idempotency conflict: key 'idem-duplicate' was already used with a different payload"));

        mockMvc.perform(post("/api/v1/events/canonical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incoming)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.status").value(409))
                .andExpect(jsonPath("$.title").value("Conflict"))
                .andExpect(jsonPath("$.detail").value("Idempotency conflict: key 'idem-duplicate' was already used with a different payload"));
    }

    @Test
    @DisplayName("POST /canonical should return RFC 7807 403 ProblemDetail on cross-tenant write attempt")
    void testIngestCanonicalCrossTenantWriteReturns403() throws Exception {
        CanonicalEvent incoming = CanonicalEvent.builder()
                .tenantId("tenant-other")
                .eventType("PaymentInitiated")
                .payload("{}")
                .build();

        when(eventIngestionUseCase.ingestCanonical(any(CanonicalEvent.class)))
                .thenThrow(new ForbiddenException("Cross-tenant write rejected: payload tenantId does not match authenticated tenant"));

        mockMvc.perform(post("/api/v1/events/canonical")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(incoming)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.detail").value("Cross-tenant write rejected: payload tenantId does not match authenticated tenant"));
    }

    @Test
    @DisplayName("GET /api/v1/events should return 200 OK with paginated PageResponse")
    void testSearchCanonicalPaginated() throws Exception {
        CanonicalEvent event = new CanonicalEvent(
                UUID.randomUUID(), "tenant-alpha", "1.0.0", "PaymentCaptured", "PAYMENT", "PAY-99",
                "payment-service", Instant.now(), Instant.now(), "trace-1", null, "k1", null, "{}"
        );
        Page<CanonicalEvent> page = new PageImpl<>(List.of(event), PageRequest.of(0, 20), 1);

        when(eventIngestionUseCase.searchCanonical(eq("PAYMENT"), eq("PAY-99"), any(), any(), any(), any(), any(Pageable.class)))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/events")
                        .param("entityType", "PAYMENT")
                        .param("entityId", "PAY-99")
                        .param("page", "0")
                        .param("size", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.totalElements").value(1))
                .andExpect(jsonPath("$.data.content[0].entityId").value("PAY-99"));
    }
}
