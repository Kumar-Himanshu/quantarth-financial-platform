package com.fip.eventengine.config;

import com.fip.common.exception.ConflictException;
import com.fip.common.exception.ForbiddenException;
import com.fip.common.exception.ResourceNotFoundException;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Instant;

@RestControllerAdvice
@Order(Ordered.HIGHEST_PRECEDENCE)
public class EventEngineExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(EventEngineExceptionHandler.class);

    @ExceptionHandler(FinancialValidationException.class)
    public ResponseEntity<ProblemDetail> handleFinancialValidationException(FinancialValidationException ex) {
        log.warn("Financial domain validation failure: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.BAD_REQUEST, ex.getMessage());
        problem.setTitle("Financial Validation Error");
        problem.setType(URI.create("https://fip.com/errors/financial-validation"));
        problem.setProperty("timestamp", Instant.now());
        problem.setProperty("errors", ex.getErrors());
        return ResponseEntity.status(HttpStatus.BAD_REQUEST).body(problem);
    }

    @ExceptionHandler(ConflictException.class)
    public ResponseEntity<ProblemDetail> handleConflictException(ConflictException ex) {
        log.warn("Idempotency conflict: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, ex.getMessage());
        problem.setTitle("Conflict");
        problem.setType(URI.create("https://fip.com/errors/conflict"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(ForbiddenException.class)
    public ResponseEntity<ProblemDetail> handleForbiddenException(ForbiddenException ex) {
        log.warn("Forbidden operation: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN, ex.getMessage());
        problem.setTitle("Forbidden");
        problem.setType(URI.create("https://fip.com/errors/forbidden"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(ResourceNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleResourceNotFoundException(ResourceNotFoundException ex) {
        log.warn("Resource not found: {}", ex.getMessage());
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
        problem.setTitle("Not Found");
        problem.setType(URI.create("https://fip.com/errors/not_found"));
        problem.setProperty("timestamp", Instant.now());
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }
}
