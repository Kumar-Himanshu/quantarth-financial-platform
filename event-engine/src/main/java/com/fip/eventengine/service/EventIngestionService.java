package com.fip.eventengine.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.exception.BadRequestException;
import com.fip.common.exception.ConflictException;
import com.fip.common.exception.ForbiddenException;
import com.fip.common.exception.ResourceNotFoundException;
import com.fip.common.tenant.TenantContext;
import com.fip.eventengine.domain.model.FinancialEvent;
import com.fip.eventengine.ports.in.EventIngestionUseCase;
import com.fip.eventengine.ports.out.EventStorePort;
import com.fip.eventengine.ports.out.OutboxPort;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.events.EntityType;
import com.fip.shared.model.financial.domain.*;
import com.fip.shared.model.financial.validation.FinancialModelValidator;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import com.fip.shared.security.RequestHmacSigner;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.*;

@Service
@Transactional
public class EventIngestionService implements EventIngestionUseCase {

    private static final Logger log = LoggerFactory.getLogger(EventIngestionService.class);

    private static final Set<String> SUPPORTED_SCHEMA_VERSIONS = Set.of("1.0", "1.0.0");

    private final EventStorePort eventStorePort;
    private final OutboxPort outboxPort;
    private final ObjectMapper objectMapper;
    private final RefundIntegrityValidator refundIntegrityValidator;

    public EventIngestionService(EventStorePort eventStorePort,
                                 OutboxPort outboxPort,
                                 ObjectMapper objectMapper,
                                 RefundIntegrityValidator refundIntegrityValidator) {
        this.eventStorePort = eventStorePort;
        this.outboxPort = outboxPort;
        this.objectMapper = objectMapper;
        this.refundIntegrityValidator = refundIntegrityValidator;
    }

    @Override
    public CanonicalEvent ingestCanonical(CanonicalEvent incoming) {
        return processCanonicalIngestion(incoming, true);
    }

    private CanonicalEvent processCanonicalIngestion(CanonicalEvent incoming, boolean strictValidation) {
        // Step 1 & 2: Authenticated Tenant Context derivation & Verification
        String authenticatedTenant = TenantContext.getCurrentTenant();
        if (authenticatedTenant == null || authenticatedTenant.isBlank()) {
            authenticatedTenant = "default";
        }

        // SECURITY: Never trust client-supplied tenant ID; reject cross-tenant overrides
        if (incoming.tenantId() != null && !incoming.tenantId().isBlank()
                && !"default".equalsIgnoreCase(incoming.tenantId())
                && !authenticatedTenant.equalsIgnoreCase(incoming.tenantId())) {
            log.warn("SECURITY ALERT: Caller in tenant [{}] attempted to write event for tenant [{}]",
                    authenticatedTenant, incoming.tenantId());
            throw new ForbiddenException(String.format(
                    "Cross-tenant write rejected: payload tenantId [%s] does not match authenticated tenant [%s]",
                    incoming.tenantId(), authenticatedTenant));
        }

        // Step 3: Schema version validation
        String schemaVer = incoming.schemaVersion() != null ? incoming.schemaVersion() : CanonicalEvent.CURRENT_VERSION;
        if (!SUPPORTED_SCHEMA_VERSIONS.contains(schemaVer)) {
            throw new BadRequestException(String.format(
                    "Unsupported schema version: [%s]. Supported versions: %s",
                    schemaVer, SUPPORTED_SCHEMA_VERSIONS));
        }

        if (incoming.eventType() == null || incoming.eventType().isBlank()) {
            throw new FinancialValidationException("eventType is required");
        }
        if (incoming.payload() == null || incoming.payload().isBlank()) {
            throw new FinancialValidationException("payload is required");
        }

        // Step 4: Resolve supported financial entity type
        String resolvedEntityTypeStr = incoming.entityType() != null && !incoming.entityType().isBlank()
                ? incoming.entityType()
                : CanonicalEvent.inferEntityType(incoming.eventType());
        EntityType entityType = EntityType.fromName(resolvedEntityTypeStr);

        // Step 5 & 6: Deserialize typed financial payload and execute domain validation
        String entityId = incoming.entityId();
        entityId = validateAndExtractEntityId(authenticatedTenant, entityType, incoming.payload(), entityId, strictValidation);

        // Normalize source system
        String sourceSystem = incoming.sourceSystem() != null && !incoming.sourceSystem().isBlank()
                ? incoming.sourceSystem()
                : "default-system";

        // Step 8: Apply Idempotency rules
        String idempotencyKey = incoming.idempotencyKey();
        String payloadHash = RequestHmacSigner.computeSha256Hex(incoming.payload().getBytes(StandardCharsets.UTF_8));

        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            Optional<FinancialEvent> existing = eventStorePort.findByIdempotencyKey(authenticatedTenant, sourceSystem, idempotencyKey);
            if (existing.isPresent()) {
                FinancialEvent existingEvent = existing.get();
                // Compare payload hash / content
                if (payloadHash.equalsIgnoreCase(existingEvent.getPayloadHash())
                        || incoming.payload().trim().equals(existingEvent.getPayload().trim())) {
                    log.info("IDEMPOTENCY: Duplicate request with key [{}] detected for tenant [{}]. Returning existing event [{}]",
                            idempotencyKey, authenticatedTenant, existingEvent.getEventId());
                    return existingEvent.toCanonicalEvent();
                } else {
                    log.warn("IDEMPOTENCY CONFLICT: Key [{}] reused with different payload for tenant [{}]",
                            idempotencyKey, authenticatedTenant);
                    throw new ConflictException(String.format(
                            "Idempotency conflict: key '%s' was already used with a different payload", idempotencyKey));
                }
            }
        }

        // Reconstruct canonical event with guaranteed tenant context and resolved entity metadata
        UUID eventId = incoming.eventId() != null ? incoming.eventId() : UUID.randomUUID();
        Instant occurredAt = incoming.occurredAt() != null ? incoming.occurredAt() : Instant.now();
        Instant recordedAt = Instant.now();
        String correlationId = incoming.correlationId() != null ? incoming.correlationId() : UUID.randomUUID().toString();
        String causationId = incoming.causationId();

        if ((causationId == null || causationId.isBlank()) && entityType == EntityType.REFUND) {
            try {
                Refund refund = objectMapper.readValue(incoming.payload(), Refund.class);
                if (refund.originalPaymentReference() != null && refund.originalPaymentReference().value() != null) {
                    causationId = refund.originalPaymentReference().value();
                }
            } catch (Exception ignored) {
            }
        }

        CanonicalEvent canonicalToPersist = new CanonicalEvent(
                eventId,
                authenticatedTenant,
                schemaVer,
                incoming.eventType(),
                entityType.name(),
                entityId,
                sourceSystem,
                occurredAt,
                recordedAt,
                correlationId,
                causationId,
                idempotencyKey,
                incoming.metadata(),
                incoming.payload()
        );

        // Step 9: Persist the canonical financial event
        FinancialEvent domain = FinancialEvent.fromCanonicalEvent(canonicalToPersist, payloadHash);
        FinancialEvent saved;
        try {
            saved = eventStorePort.save(domain);
        } catch (DataIntegrityViolationException dive) {
            // Concurrent duplicate insertion race condition caught by PostgreSQL unique constraint
            if (idempotencyKey != null && !idempotencyKey.isBlank()) {
                Optional<FinancialEvent> raced = eventStorePort.findByIdempotencyKey(authenticatedTenant, sourceSystem, idempotencyKey);
                if (raced.isPresent()) {
                    FinancialEvent racedEvent = raced.get();
                    if (payloadHash.equalsIgnoreCase(racedEvent.getPayloadHash())
                            || incoming.payload().trim().equals(racedEvent.getPayload().trim())) {
                        log.info("IDEMPOTENCY: Concurrent duplicate delivery caught via database constraint for key [{}]. Returning existing event [{}]",
                                idempotencyKey, racedEvent.getEventId());
                        return racedEvent.toCanonicalEvent();
                    } else {
                        throw new ConflictException(String.format(
                                "Idempotency conflict: key '%s' was already used with a different payload", idempotencyKey));
                    }
                }
            }
            throw dive;
        }

        log.info("Persisted canonical financial event [{}] entityType [{}] entityId [{}] tenant [{}]",
                saved.getEventId(), saved.getEntityType(), saved.getEntityId(), saved.getTenantId());

        // Step 10: Atomic Transactional Outbox persistence with normalized canonical routing key
        String routingKey = EventRoutingHelper.resolveRoutingKey(saved.getEntityType(), saved.getEventType());
        try {
            String outboxPayloadJson = objectMapper.writeValueAsString(canonicalToPersist);
            outboxPort.save(saved.getEventId(), saved.getEventType(), routingKey, outboxPayloadJson);
            log.info("Atomically enqueued canonical event [{}] to outbox with routingKey [{}]",
                    saved.getEventId(), routingKey);
        } catch (Exception ex) {
            log.error("Failed to serialize canonical event for outbox [{}]", saved.getEventId(), ex);
            throw new RuntimeException("Failed to enqueue event to transactional outbox", ex);
        }

        return canonicalToPersist;
    }

    private String validateAndExtractEntityId(String tenantId, EntityType entityType, String payloadJson,
                                              String currentEntityId, boolean strictValidation) {
        try {
            switch (entityType) {
                case PAYMENT -> {
                    Payment payment = objectMapper.readValue(payloadJson, Payment.class);
                    FinancialModelValidator.validate(payment);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : payment.paymentId();
                }
                case INVOICE -> {
                    Invoice invoice = objectMapper.readValue(payloadJson, Invoice.class);
                    FinancialModelValidator.validate(invoice);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : invoice.invoiceId();
                }
                case TRANSFER -> {
                    Transfer transfer = objectMapper.readValue(payloadJson, Transfer.class);
                    FinancialModelValidator.validate(transfer);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : transfer.transferId();
                }
                case SALARY -> {
                    Salary salary = objectMapper.readValue(payloadJson, Salary.class);
                    FinancialModelValidator.validate(salary);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : salary.salaryId();
                }
                case REFUND -> {
                    Refund refund = objectMapper.readValue(payloadJson, Refund.class);
                    // Phase 8: Comprehensive refund integrity & concurrency validation
                    refundIntegrityValidator.validateRefund(tenantId, refund);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : refund.refundId();
                }
                case LOAN -> {
                    Loan loan = objectMapper.readValue(payloadJson, Loan.class);
                    FinancialModelValidator.validate(loan);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : loan.loanId();
                }
                case LOAN_REPAYMENT -> {
                    LoanRepayment repayment = objectMapper.readValue(payloadJson, LoanRepayment.class);
                    FinancialModelValidator.validate(repayment);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : repayment.repaymentId();
                }
                case SUBSCRIPTION -> {
                    Subscription subscription = objectMapper.readValue(payloadJson, Subscription.class);
                    FinancialModelValidator.validate(subscription);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : subscription.subscriptionId();
                }
                case TAX -> {
                    Tax tax = objectMapper.readValue(payloadJson, Tax.class);
                    FinancialModelValidator.validate(tax);
                    return (currentEntityId != null && !currentEntityId.isBlank()) ? currentEntityId : tax.taxRecordId();
                }
                default -> {
                    // Other/system events: verify valid JSON
                    objectMapper.readTree(payloadJson);
                    return currentEntityId;
                }
            }
        } catch (FinancialValidationException fve) {
            if (strictValidation) {
                throw fve;
            }
            log.info("Legacy event payload detected for eventType [{}] - proceeding with legacy compatibility mode: {}",
                    entityType, fve.getMessage());
            return currentEntityId;
        } catch (JsonProcessingException jpe) {
            if (strictValidation) {
                throw new FinancialValidationException("Malformed or invalid JSON payload for " + entityType + ": " + jpe.getMessage(), jpe);
            }
            try {
                objectMapper.readTree(payloadJson);
                return currentEntityId;
            } catch (Exception e) {
                throw new FinancialValidationException("Payload is not valid JSON: " + jpe.getMessage(), jpe);
            }
        } catch (Exception e) {
            if (strictValidation) {
                throw new FinancialValidationException("Validation error for " + entityType + ": " + e.getMessage(), e);
            }
            log.info("Legacy payload for [{}], skipping strict domain model validation: {}", entityType, e.getMessage());
            return currentEntityId;
        }
    }

    @Override
    public FinancialEvent ingestAndPublish(Command command) {
        String authenticatedTenant = TenantContext.getCurrentTenant();
        String sourceSystem = command.sourceSystem() != null ? command.sourceSystem() : command.eventSource();
        String traceId = command.traceId() != null ? command.traceId() : UUID.randomUUID().toString();
        String version = command.version() != null ? command.version() : CanonicalEvent.CURRENT_VERSION;
        boolean strictValidation = command.entityType() != null && !command.entityType().isBlank();

        CanonicalEvent canonical = new CanonicalEvent(
                UUID.randomUUID(),
                authenticatedTenant,
                version,
                command.eventType(),
                command.entityType(),
                command.entityId(),
                sourceSystem,
                Instant.now(),
                Instant.now(),
                traceId,
                null,
                command.idempotencyKey(),
                command.metadata(),
                command.payload()
        );

        CanonicalEvent ingested = processCanonicalIngestion(canonical, strictValidation);
        String payloadHash = RequestHmacSigner.computeSha256Hex(ingested.payload().getBytes(StandardCharsets.UTF_8));
        return FinancialEvent.fromCanonicalEvent(ingested, payloadHash);
    }

    @Override
    public List<FinancialEvent> ingestBatch(List<Command> commands) {
        List<FinancialEvent> savedList = new ArrayList<>();
        for (Command command : commands) {
            savedList.add(ingestAndPublish(command));
        }
        return savedList;
    }

    @Override
    public List<CanonicalEvent> ingestCanonicalBatch(List<CanonicalEvent> events) {
        List<CanonicalEvent> result = new ArrayList<>();
        for (CanonicalEvent event : events) {
            result.add(ingestCanonical(event));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public FinancialEvent getEventById(UUID eventId) {
        String currentTenant = TenantContext.getCurrentTenant();
        // SECURITY: Strict multi-tenant isolation. Cross-tenant access returns 404.
        return eventStorePort.findByEventIdAndTenantId(eventId, currentTenant)
                .orElseThrow(() -> new ResourceNotFoundException("Financial Event not found: " + eventId));
    }

    @Override
    @Transactional(readOnly = true)
    public List<FinancialEvent> searchEvents(String eventType, String eventSource) {
        String currentTenant = TenantContext.getCurrentTenant();
        return eventStorePort.search(eventType, eventSource, currentTenant);
    }

    @Override
    @Transactional(readOnly = true)
    public Page<CanonicalEvent> searchCanonical(String entityType, String entityId, String eventType,
                                                String sourceSystem, LocalDateTime fromDate, LocalDateTime toDate,
                                                Pageable pageable) {
        String currentTenant = TenantContext.getCurrentTenant();
        Page<FinancialEvent> page = eventStorePort.searchCanonical(
                currentTenant, entityType, entityId, eventType, sourceSystem, fromDate, toDate, pageable
        );
        return page.map(FinancialEvent::toCanonicalEvent);
    }
}
