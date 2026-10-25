package com.fip.eventengine.adapters.in.web;

import com.fip.common.response.StandardResponse;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.in.EventIngestionUseCase;
import io.swagger.v3.oas.annotations.Operation;
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
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Financial Event Engine", description = "Endpoints for canonical event ingestion, event persistence, audit queries, and event streaming")
public class EventController {

    private final EventIngestionUseCase eventIngestionUseCase;

    public EventController(EventIngestionUseCase eventIngestionUseCase) {
        this.eventIngestionUseCase = eventIngestionUseCase;
    }

    @PostMapping("/publish")
    @Operation(
            summary = "Ingest and publish a canonical financial event",
            description = "Validates event schema, saves to immutable event store scoped to caller tenant, and broadcasts to RabbitMQ.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Event ingested and published successfully"),
                    @ApiResponse(responseCode = "400", description = "Invalid payload or validation failure",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Authentication required",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "403", description = "Forbidden: Insufficient privileges (Requires ROLE_ANALYST or ROLE_ADMIN)",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<FinancialEvent>> publish(
            @Valid @RequestBody EventIngestionUseCase.Command command) {
        FinancialEvent ingested = eventIngestionUseCase.ingestAndPublish(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StandardResponse.success(201, ingested, "Financial event processed and published"));
    }

    @PostMapping("/batch")
    @Operation(summary = "Batch ingest multiple canonical financial events")
    public ResponseEntity<StandardResponse<List<FinancialEvent>>> publishBatch(
            @Valid @RequestBody List<EventIngestionUseCase.Command> commands) {
        List<FinancialEvent> batch = eventIngestionUseCase.ingestBatch(commands);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StandardResponse.success(201, batch, "Batch events ingested and published"));
    }

    @GetMapping("/{eventId}")
    @Operation(
            summary = "Get financial event by eventId (Strictly scoped to authenticated tenant)",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Event retrieved"),
                    @ApiResponse(responseCode = "404", description = "Event not found or belongs to another tenant",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<FinancialEvent>> getById(@PathVariable("eventId") UUID eventId) {
        FinancialEvent event = eventIngestionUseCase.getEventById(eventId);
        return ResponseEntity.ok(StandardResponse.success(event));
    }

    @GetMapping("/search")
    @Operation(summary = "Search financial audit events (Strictly scoped to authenticated tenant)")
    public ResponseEntity<StandardResponse<List<FinancialEvent>>> search(
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "eventSource", required = false) String eventSource) {
        List<FinancialEvent> results = eventIngestionUseCase.searchEvents(eventType, eventSource);
        return ResponseEntity.ok(StandardResponse.success(results));
    }
}
