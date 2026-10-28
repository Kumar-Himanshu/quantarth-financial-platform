package com.fip.ingestion.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.ingestion.domain.model.FieldMapping;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.SourceRecord;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.PaymentMethod;
import com.fip.shared.model.financial.domain.PaymentStatus;
import com.fip.shared.model.financial.domain.Refund;
import com.fip.shared.model.financial.domain.RefundStatus;
import com.fip.shared.model.financial.primitives.FinancialParty;
import com.fip.shared.model.financial.primitives.FinancialReference;
import com.fip.shared.model.financial.primitives.FinancialReferenceType;
import com.fip.shared.model.financial.primitives.FinancialTime;
import com.fip.shared.model.financial.primitives.Money;
import com.fip.shared.model.financial.primitives.PartyType;
import com.fip.shared.model.financial.validation.FinancialModelValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Component
public class DeclarativeMappingEngine {

    private static final Logger log = LoggerFactory.getLogger(DeclarativeMappingEngine.class);
    private final ObjectMapper objectMapper;

    @FunctionalInterface
    public interface PaymentExistenceChecker {
        boolean paymentExists(String tenantId, String paymentId);
    }

    public record NormalizationResult(
            CanonicalEvent canonicalEvent,
            boolean isExternalRefundWithoutLocalPayment,
            String entityType,
            String entityId
    ) {}

    public DeclarativeMappingEngine(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public NormalizationResult normalize(
            SourceRecord record,
            MappingProfile profile,
            String tenantId,
            String sourceSystem) throws Exception {
        return normalize(record, profile, tenantId, sourceSystem, null);
    }

    public NormalizationResult normalize(
            SourceRecord record,
            MappingProfile profile,
            String tenantId,
            String sourceSystem,
            PaymentExistenceChecker paymentChecker) throws Exception {

        String targetEntityType = null;
        if (record != null) {
            targetEntityType = record.getValue("targetEntityType");
        }
        if (targetEntityType == null || targetEntityType.isBlank()) {
            targetEntityType = (profile != null && profile.targetEntityType() != null)
                    ? profile.targetEntityType()
                    : "PAYMENT";
        }
        targetEntityType = targetEntityType.toUpperCase();

        Map<String, FieldMapping> mappings = (profile != null && profile.fieldMappings() != null)
                ? profile.fieldMappings()
                : java.util.Collections.emptyMap();

        // 1. Resolve Entity ID
        String entityId = resolveField("entityId", record, mappings);
        if (entityId == null || entityId.isBlank()) {
            entityId = record.sourceRecordId();
        }
        if (entityId == null || entityId.isBlank()) {
            entityId = UUID.randomUUID().toString();
        }

        // 2. Resolve Occurred Timestamp
        Instant occurredAt = resolveTimestamp(record, mappings, profile);

        // 3. Resolve Amount & Currency
        Money money = resolveMoney(record, mappings, profile);

        // 4. Build Domain Model and Payload
        String payloadJson;
        boolean isExternalRefund = false;

        switch (targetEntityType) {
            case "REFUND" -> {
                String origPayRef = resolveField("originalPaymentReference", record, mappings);
                if (origPayRef == null || origPayRef.isBlank()) {
                    origPayRef = resolveField("originalPaymentId", record, mappings);
                }
                if (origPayRef == null || origPayRef.isBlank()) {
                    origPayRef = "EXT-PAY-UNKNOWN";
                }

                String origPayAmtStr = resolveField("originalPaymentAmount", record, mappings);
                Money origPayAmt = null;
                if (origPayAmtStr != null && !origPayAmtStr.isBlank()) {
                    try {
                        BigDecimal parsed = new BigDecimal(origPayAmtStr.replaceAll("[^0-9.]", ""));
                        origPayAmt = Money.of(parsed.setScale(2, RoundingMode.HALF_EVEN), money.currency());
                    } catch (Exception ignored) {}
                }

                String reason = resolveField("reason", record, mappings);
                if (reason == null) reason = "External source refund";

                boolean paymentExistsLocally = false;
                if (paymentChecker != null && !"EXT-PAY-UNKNOWN".equalsIgnoreCase(origPayRef)) {
                    try {
                        paymentExistsLocally = paymentChecker.paymentExists(tenantId, origPayRef);
                    } catch (Exception ex) {
                        log.debug("Payment existence check failed for paymentId [{}]: {}", origPayRef, ex.getMessage());
                        paymentExistsLocally = false;
                    }
                }

                Refund refund = Refund.builder()
                        .refundId(entityId)
                        .originalPaymentReference(FinancialReference.paymentGateway(origPayRef, "EXTERNAL_GATEWAY"))
                        .refundAmount(money)
                        .originalPaymentAmount(origPayAmt != null ? origPayAmt : money)
                        .originalCapturedAmount(origPayAmt != null ? origPayAmt : money)
                        .currency(money.currency())
                        .status(RefundStatus.INITIATED)
                        .timestamp(FinancialTime.at(occurredAt))
                        .reason(reason)
                        .build();

                FinancialModelValidator.validate(refund);
                payloadJson = objectMapper.writeValueAsString(refund);
                isExternalRefund = !paymentExistsLocally;
            }
            default -> { // PAYMENT
                targetEntityType = "PAYMENT";
                String payerName = resolveField("payerName", record, mappings);
                if (payerName == null || payerName.isBlank()) payerName = "Individual Customer";
                String payeeName = resolveField("payeeName", record, mappings);
                if (payeeName == null || payeeName.isBlank()) payeeName = "Platform Merchant";

                String methodStr = resolveField("paymentMethod", record, mappings);
                PaymentMethod method = PaymentMethod.BANK_TRANSFER;
                if (methodStr != null) {
                    try {
                        method = PaymentMethod.valueOf(methodStr.toUpperCase());
                    } catch (Exception ignored) {}
                }

                Payment payment = Payment.builder()
                        .paymentId(entityId)
                        .amount(money)
                        .payer(FinancialParty.individual("CUST-" + Math.abs(payerName.hashCode() % 10000), payerName))
                        .payee(FinancialParty.organization("MERCH-" + Math.abs(payeeName.hashCode() % 10000), payeeName))
                        .paymentMethod(method)
                        .status(PaymentStatus.COMPLETED)
                        .timestamp(FinancialTime.at(occurredAt))
                        .addReference(FinancialReference.paymentGateway(record.sourceRecordId(), sourceSystem))
                        .build();

                FinancialModelValidator.validate(payment);
                payloadJson = objectMapper.writeValueAsString(payment);
            }
        }

        // 5. Wrap into CanonicalEvent
        CanonicalEvent canonicalEvent = CanonicalEvent.builder()
                .eventId(UUID.randomUUID())
                .tenantId(tenantId)
                .schemaVersion("1.0.0")
                .eventType(targetEntityType + "_INGESTED")
                .entityType(targetEntityType)
                .entityId(entityId)
                .sourceSystem(sourceSystem)
                .occurredAt(occurredAt)
                .recordedAt(Instant.now())
                .correlationId(UUID.randomUUID().toString())
                .idempotencyKey(sourceSystem + ":" + entityId)
                .payload(payloadJson)
                .build();

        return new NormalizationResult(canonicalEvent, isExternalRefund, targetEntityType, entityId);
    }

    private String resolveField(String fieldName, SourceRecord record, Map<String, FieldMapping> mappings) {
        if (mappings != null && mappings.containsKey(fieldName)) {
            FieldMapping mapping = mappings.get(fieldName);
            if (mapping.staticValue() != null && !mapping.staticValue().isBlank()) {
                return mapping.staticValue();
            }
            if (mapping.sourceColumn() != null) {
                String val = record.getValue(mapping.sourceColumn());
                if (val != null && mapping.regex() != null && !mapping.regex().isBlank()) {
                    Pattern pattern = Pattern.compile(mapping.regex());
                    Matcher matcher = pattern.matcher(val);
                    if (matcher.find()) {
                        return matcher.groupCount() >= 1 ? matcher.group(1) : matcher.group();
                    }
                }
                return val;
            }
        }
        return record.getValue(fieldName);
    }

    private Instant resolveTimestamp(SourceRecord record, Map<String, FieldMapping> mappings, MappingProfile profile) {
        String dateStr = resolveField("occurredAt", record, mappings);
        if (dateStr == null || dateStr.isBlank()) {
            dateStr = resolveField("Date", record, mappings);
        }
        if (dateStr == null || dateStr.isBlank()) {
            dateStr = resolveField("transactionDate", record, mappings);
        }
        if (dateStr == null || dateStr.isBlank()) {
            return Instant.now();
        }

        ZoneId zone = ZoneId.of(profile.timezone());
        String format = profile.dateFormat();

        try {
            // Check ISO Instant format
            if (dateStr.endsWith("Z") || dateStr.contains("T")) {
                return Instant.parse(dateStr);
            }
            DateTimeFormatter formatter = DateTimeFormatter.ofPattern(format);
            if (format.contains("HH") || format.contains("hh")) {
                LocalDateTime ldt = LocalDateTime.parse(dateStr, formatter);
                return ldt.atZone(zone).toInstant();
            } else {
                LocalDate ld = LocalDate.parse(dateStr, formatter);
                return ld.atStartOfDay(zone).toInstant();
            }
        } catch (Exception e) {
            log.debug("Falling back to standard date parsing for [{}]: {}", dateStr, e.getMessage());
            try {
                LocalDate ld = LocalDate.parse(dateStr);
                return ld.atStartOfDay(zone).toInstant();
            } catch (Exception ex) {
                return Instant.now();
            }
        }
    }

    private Money resolveMoney(SourceRecord record, Map<String, FieldMapping> mappings, MappingProfile profile) {
        String currency = resolveField("currency", record, mappings);
        if (currency == null || currency.isBlank()) {
            currency = (profile != null && profile.currencyDefault() != null) ? profile.currencyDefault() : "INR";
        }

        FieldMapping amountMapping = mappings != null ? mappings.get("amount") : null;
        BigDecimal amount = BigDecimal.ZERO;
        int scale = (amountMapping != null && amountMapping.scale() != null) ? amountMapping.scale() : 2;

        if (amountMapping != null && "SPLIT_DR_CR".equalsIgnoreCase(amountMapping.strategy())) {
            String debitStr = record.getValue(amountMapping.debitColumn());
            String creditStr = record.getValue(amountMapping.creditColumn());

            if (creditStr != null && !creditStr.trim().isEmpty()) {
                amount = parseDecimal(creditStr);
            } else if (debitStr != null && !debitStr.trim().isEmpty()) {
                amount = parseDecimal(debitStr);
            }
        } else if (amountMapping != null && "SIGNED_AMOUNT".equalsIgnoreCase(amountMapping.strategy())) {
            String amtStr = record.getValue(amountMapping.amountColumn());
            amount = parseDecimal(amtStr);
        } else {
            String amtStr = resolveField("amount", record, mappings);
            if (amtStr == null || amtStr.isBlank()) {
                amtStr = resolveField("txn_amount", record, mappings);
            }
            amount = parseDecimal(amtStr);
        }

        if (amount.compareTo(BigDecimal.ZERO) == 0) {
            String creditStr = resolveField("creditAmount", record, mappings);
            String debitStr = resolveField("debitAmount", record, mappings);
            if (creditStr != null && !creditStr.trim().isEmpty()) {
                amount = parseDecimal(creditStr);
            } else if (debitStr != null && !debitStr.trim().isEmpty()) {
                amount = parseDecimal(debitStr);
            }
        }

        // Banker's Rounding (HALF_EVEN)
        BigDecimal normalizedAmount = amount.abs().setScale(scale, RoundingMode.HALF_EVEN);

        return Money.of(normalizedAmount, currency.toUpperCase());
    }

    private BigDecimal parseDecimal(String str) {
        if (str == null || str.isBlank()) {
            return BigDecimal.ZERO;
        }
        // Remove currency symbols, commas, and extraneous characters
        String cleaned = str.replaceAll("[^0-9.-]", "").trim();
        if (cleaned.isEmpty() || cleaned.equals("-") || cleaned.equals(".")) {
            return BigDecimal.ZERO;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (Exception e) {
            log.warn("Could not parse decimal [{}], defaulting to zero", str);
            return BigDecimal.ZERO;
        }
    }
}
