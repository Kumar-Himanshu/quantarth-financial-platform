package com.fip.ingestion.adapters.in.rest;

import com.fip.common.response.StandardResponse;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaEntity;
import com.fip.ingestion.application.service.CsvIngestionService;
import com.fip.ingestion.application.service.IngestionJobService;
import com.fip.ingestion.application.service.JsonIngestionService;
import com.fip.ingestion.domain.model.RecordStatus;
import com.fip.ingestion.domain.model.ReviewStatus;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ingestion")
@Tag(name = "Financial Ingestion API", description = "Endpoints for JSON and CSV external financial data ingestion")
public class IngestionController {

    private final JsonIngestionService jsonIngestionService;
    private final CsvIngestionService csvIngestionService;
    private final IngestionJobService jobService;

    public IngestionController(
            JsonIngestionService jsonIngestionService,
            CsvIngestionService csvIngestionService,
            IngestionJobService jobService) {
        this.jsonIngestionService = jsonIngestionService;
        this.csvIngestionService = csvIngestionService;
        this.jobService = jobService;
    }

    @PostMapping("/json")
    @Operation(summary = "Ingest structured financial records via JSON API")
    public ResponseEntity<StandardResponse<JsonIngestionService.IngestionResponse>> ingestJson(
            @RequestBody JsonIngestionService.JsonIngestionRequest request,
            @RequestParam(name = "sync", defaultValue = "false") boolean sync) {

        JsonIngestionService.IngestionResponse response = jsonIngestionService.ingestJson(request, sync);

        HttpStatus status = (sync && response.status().isTerminal()) ? HttpStatus.OK : HttpStatus.ACCEPTED;
        String message = status == HttpStatus.OK
                ? "Financial records processed synchronously"
                : "Financial records accepted for ingestion";

        return ResponseEntity.status(status).body(StandardResponse.success(status.value(), response, message));
    }

    @PostMapping(value = "/csv", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Operation(summary = "Ingest external bank statement or transaction export via CSV upload")
    public ResponseEntity<StandardResponse<JsonIngestionService.IngestionResponse>> ingestCsv(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "mappingProfileId", required = false) UUID mappingProfileId,
            @RequestParam(name = "sourceSystem", required = false, defaultValue = "CSV_UPLOAD") String sourceSystem,
            @RequestParam(name = "force", defaultValue = "false") boolean force) throws Exception {

        if (file.isEmpty()) {
            throw new IllegalArgumentException("Uploaded CSV file cannot be empty");
        }

        JsonIngestionService.IngestionResponse response = csvIngestionService.submitCsv(
                file, mappingProfileId, sourceSystem, force);

        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .body(StandardResponse.success(202, response, "CSV upload accepted for processing"));
    }

    @GetMapping("/jobs/{jobId}")
    @Operation(summary = "Get ingestion job progress, counters, and status")
    public ResponseEntity<StandardResponse<IngestionJobJpaEntity>> getJob(@PathVariable UUID jobId) {
        String tenantId = TenantContext.getCurrentTenant();
        return jobService.getJob(jobId, tenantId)
                .map(job -> ResponseEntity.ok(StandardResponse.success(200, job, "Job status retrieved")))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(StandardResponse.<IngestionJobJpaEntity>builder()
                                .success(false)
                                .status(404)
                                .message("Ingestion job not found: " + jobId)
                                .build()));
    }

    @GetMapping("/jobs/{jobId}/records")
    @Operation(summary = "Get itemized record logs and error quarantine for an ingestion job")
    public ResponseEntity<StandardResponse<Page<IngestionJobRecordJpaEntity>>> getJobRecords(
            @PathVariable UUID jobId,
            @RequestParam(name = "status", required = false) RecordStatus status,
            @RequestParam(name = "reviewStatus", required = false) ReviewStatus reviewStatus,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "50") int size) {

        String tenantId = TenantContext.getCurrentTenant();
        PageRequest pageRequest = PageRequest.of(page, Math.min(size, 200), Sort.by("recordIndex").ascending());
        Page<IngestionJobRecordJpaEntity> records = jobService.getJobRecords(jobId, tenantId, status, reviewStatus, pageRequest);

        return ResponseEntity.ok(StandardResponse.success(200, records, "Job records retrieved successfully"));
    }
}
