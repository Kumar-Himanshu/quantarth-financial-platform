package com.fip.ingestion.adapters.in.rest;

import com.fip.ingestion.application.service.CsvIngestionService;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice("com.fip.ingestion")
@Order(Ordered.HIGHEST_PRECEDENCE)
public class IngestionExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(IngestionExceptionHandler.class);

    @ExceptionHandler(CsvIngestionService.DuplicateFileException.class)
    public ResponseEntity<ProblemDetail> handleDuplicateFile(CsvIngestionService.DuplicateFileException ex) {
        log.warn("Duplicate file rejected: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Duplicate File Upload");
        problem.setType(URI.create("https://api.quantarth.fip/errors/duplicate-file"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(FinancialValidationException.class)
    public ResponseEntity<ProblemDetail> handleFinancialValidation(FinancialValidationException ex) {
        log.warn("Financial domain validation failure: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Financial Domain Validation Error");
        problem.setType(URI.create("https://api.quantarth.fip/errors/financial-validation-failed"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<ProblemDetail> handleIllegalArgument(IllegalArgumentException ex) {
        log.warn("Illegal argument: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Invalid Request Parameters");
        problem.setType(URI.create("https://api.quantarth.fip/errors/bad-request"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_PROBLEM_JSON)
                .body(problem);
    }
}
