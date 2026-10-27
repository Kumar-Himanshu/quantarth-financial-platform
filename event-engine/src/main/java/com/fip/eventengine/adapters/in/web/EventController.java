package com.fip.eventengine.adapters.in.web;

import com.fip.common.response.StandardResponse;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.in.EventIngestionUseCase;
import com.fip.shared.events.CanonicalEvent;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@Tag(name = "Financial Event Engine", description = "Endpoints for canonical financial event ingestion, persistence, audit queries, and event streaming")
public class EventController {

    public static final int MAX_PAGE_SIZE = 100;
    public static final int DEFAULT_PAGE_SIZE = 20;

    private final EventIngestionUseCase eventIngestionUseCase;

    public EventController(EventIngestionUseCase eventIngestionUseCase) {
        this.eventIngestionUseCase = eventIngestionUseCase;
    }

    @PostMapping("/canonical")
    @Operation(
            summary = "Ingest a canonical financial event with schema validation, domain rules, and idempotency",
            description = "Validates schema version, financial domain invariants, executes refund integrity checks, enforces tenant isolation and idempotency, persists event, and atomically enqueues to transactional outbox.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "Canonical event ingested and enqueued successfully"),
                    @ApiResponse(responseCode = "400", description = "Invalid payload or financial domain validation failure",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "401", description = "Authentication required (Missing or invalid HMAC signature)",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "403", description = "Forbidden: Insufficient privileges or cross-tenant write rejected",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class))),
                    @ApiResponse(responseCode = "409", description = "Idempotency key reuse conflict with different payload",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<CanonicalEvent>> ingestCanonical(
            @Valid @RequestBody CanonicalEvent canonical) {
        CanonicalEvent persisted = eventIngestionUseCase.ingestCanonical(canonical);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StandardResponse.success(201, persisted, "Canonical financial event processed and published"));
    }

    @PostMapping("/canonical/batch")
    @Operation(summary = "Batch ingest multiple canonical financial events")
    public ResponseEntity<StandardResponse<List<CanonicalEvent>>> ingestCanonicalBatch(
            @Valid @RequestBody List<CanonicalEvent> events) {
        List<CanonicalEvent> batch = eventIngestionUseCase.ingestCanonicalBatch(events);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StandardResponse.success(201, batch, "Batch canonical financial events processed and published"));
    }

    @PostMapping("/publish")
    @Operation(
            summary = "Ingest and publish a financial event (Command format)",
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
    @Operation(summary = "Batch ingest multiple financial events (Command format)")
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

    @GetMapping
    @Operation(
            summary = "Search canonical financial events by entityType, entityId, date range with bounded pagination",
            description = "Strictly tenant-scoped query supporting financial entity type, financial entity ID, date range, and pagination."
    )
    public ResponseEntity<StandardResponse<PageResponse<CanonicalEvent>>> searchCanonical(
            @RequestParam(name = "entityType", required = false) String entityType,
            @RequestParam(name = "entityId", required = false) String entityId,
            @RequestParam(name = "eventType", required = false) String eventType,
            @RequestParam(name = "sourceSystem", required = false) String sourceSystem,
            @RequestParam(name = "fromDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime fromDate,
            @RequestParam(name = "toDate", required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime toDate,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size) {

        int boundedSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        int boundedPage = Math.max(0, page);
        Pageable pageable = PageRequest.of(boundedPage, boundedSize);

        Page<CanonicalEvent> results = eventIngestionUseCase.searchCanonical(
                entityType, entityId, eventType, sourceSystem, fromDate, toDate, pageable
        );

        return ResponseEntity.ok(StandardResponse.success(PageResponse.fromPage(results)));
    }
}
