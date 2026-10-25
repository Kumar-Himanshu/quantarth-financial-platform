package com.fip.eventengine.adapters.in.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.exception.GlobalExceptionHandler;
import com.fip.common.exception.ResourceNotFoundException;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.in.EventIngestionUseCase;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = EventController.class)
@AutoConfigureMockMvc(addFilters = false) // Unit test MVC mappings & exception handlers directly
@Import(GlobalExceptionHandler.class)
class EventControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private EventIngestionUseCase eventIngestionUseCase;

    @Test
    @DisplayName("POST /publish should return 201 Created and StandardResponse")
    void publish_validPayload_shouldReturnCreated() throws Exception {
        EventIngestionUseCase.Command cmd = new EventIngestionUseCase.Command(
                "DocumentValidationJobCreated",
                "doc-ai-service",
                "{\"documentId\":\"doc-123\"}",
                "trace-1",
                "1.0",
                null
        );

        UUID eventId = UUID.randomUUID();
        FinancialEvent mockSaved = FinancialEvent.fromCanonical(
                eventId, cmd.eventType(), cmd.eventSource(), "trace-1", "tenant-alpha", null, cmd.payload(), "1.0"
        );
        when(eventIngestionUseCase.ingestAndPublish(any(EventIngestionUseCase.Command.class)))
                .thenReturn(mockSaved);

        mockMvc.perform(post("/api/v1/events/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(cmd)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId").value(eventId.toString()))
                .andExpect(jsonPath("$.data.eventType").value("DocumentValidationJobCreated"));
    }

    @Test
    @DisplayName("POST /publish should return RFC 7807 400 Bad Request on invalid payload")
    void publish_invalidPayload_shouldReturn400ProblemDetail() throws Exception {
        // Missing required eventType and payload
        String invalidJson = "{\"eventSource\": \"doc-ai\"}";

        mockMvc.perform(post("/api/v1/events/publish")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(invalidJson))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.title").value("Validation Error"))
                .andExpect(jsonPath("$.status").value(400))
                .andExpect(jsonPath("$.errors.eventType").exists())
                .andExpect(jsonPath("$.errors.payload").exists());
    }

    @Test
    @DisplayName("GET /{eventId} should return 200 OK when event exists in caller tenant")
    void getById_existingEvent_shouldReturn200() throws Exception {
        UUID eventId = UUID.randomUUID();
        FinancialEvent mockEvent = FinancialEvent.fromCanonical(
                eventId, "DocumentValidationJobCreated", "doc-ai", "trace-1", "tenant-alpha", null, "{}", "1.0"
        );
        when(eventIngestionUseCase.getEventById(eventId)).thenReturn(mockEvent);

        mockMvc.perform(get("/api/v1/events/" + eventId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.eventId").value(eventId.toString()));
    }

    @Test
    @DisplayName("GET /{eventId} should return RFC 7807 404 Not Found when event missing or cross-tenant")
    void getById_missingOrCrossTenantEvent_shouldReturn404ProblemDetail() throws Exception {
        UUID eventId = UUID.randomUUID();
        when(eventIngestionUseCase.getEventById(eventId))
                .thenThrow(new ResourceNotFoundException("Financial Event not found: " + eventId));

        mockMvc.perform(get("/api/v1/events/" + eventId))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.title").value("Not Found"))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.detail").value("Financial Event not found: " + eventId));
    }
}
