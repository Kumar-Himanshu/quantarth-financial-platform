package com.fip.ingestion.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.ingestion.domain.model.FieldMapping;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.SourceRecord;
import com.fip.ingestion.domain.model.SourceType;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.domain.Refund;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("Declarative Mapping Engine Unit Tests")
class DeclarativeMappingEngineTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());
    private final DeclarativeMappingEngine engine = new DeclarativeMappingEngine(objectMapper);

    @Test
    @DisplayName("HDFC Bank statement profile: maps separate withdrawal/deposit columns with dd/MM/yyyy")
    void testHdfcMappingProfile() throws Exception {
        Map<String, FieldMapping> fieldMap = new HashMap<>();
        fieldMap.put("transactionId", FieldMapping.column("Txn Ref"));
        fieldMap.put("transactionDate", FieldMapping.column("Value Date"));
        fieldMap.put("debitAmount", FieldMapping.column("Debit"));
        fieldMap.put("creditAmount", FieldMapping.column("Credit"));
        fieldMap.put("currency", FieldMapping.constant("INR"));
        fieldMap.put("payerName", FieldMapping.column("Party"));

        MappingProfile profile = new MappingProfile(
                UUID.randomUUID(),
                "tenant-test",
                "HDFC_CSV_PROFILE",
                SourceType.CSV,
                "PAYMENT",
                ",",
                true,
                "dd/MM/yyyy",
                "UTC",
                "INR",
                fieldMap,
                1,
                true
        );

        // Test Credit (Deposit -> Payment)
        Map<String, String> rowFields = new HashMap<>();
        rowFields.put("Txn Ref", "HDFC99881122");
        rowFields.put("Value Date", "25/10/2026");
        rowFields.put("Debit", "");
        rowFields.put("Credit", "45,000.50");
        rowFields.put("Party", "Reliance Industries");

        SourceRecord record = new SourceRecord(1, "HDFC99881122", rowFields, "");

        DeclarativeMappingEngine.NormalizationResult result = engine.normalize(
                record, profile, "tenant-test", "HDFC_BANK");

        assertNotNull(result);
        assertFalse(result.isExternalRefundWithoutLocalPayment());
        assertEquals("PAYMENT", result.entityType());
        assertEquals("HDFC99881122", result.entityId());

        CanonicalEvent event = result.canonicalEvent();
        assertEquals("PAYMENT_INGESTED", event.eventType());
        assertEquals("tenant-test", event.tenantId());
        assertEquals("HDFC_BANK:HDFC99881122", event.idempotencyKey());

        Payment payment = objectMapper.readValue(event.payload(), Payment.class);
        assertEquals(new BigDecimal("45000.50"), payment.amount().amount());
        assertEquals("INR", payment.amount().currency());
        assertEquals("Reliance Industries", payment.payer().displayName());
    }

    @Test
    @DisplayName("External Refund Mapping: identifies refund transaction and sets isExternalRefundWithoutLocalPayment = true")
    void testExternalRefundMapping() throws Exception {
        Map<String, FieldMapping> fieldMap = new HashMap<>();
        fieldMap.put("transactionId", FieldMapping.column("RefundId"));
        fieldMap.put("transactionDate", FieldMapping.column("Date"));
        fieldMap.put("amount", FieldMapping.column("Amount"));
        fieldMap.put("currency", FieldMapping.constant("INR"));
        fieldMap.put("transactionType", FieldMapping.column("Type"));
        fieldMap.put("originalPaymentReference", FieldMapping.column("OrigTxnRef"));

        MappingProfile profile = new MappingProfile(
                UUID.randomUUID(),
                "tenant-test",
                "STRIPE_REFUNDS",
                SourceType.JSON,
                "REFUND",
                ",",
                true,
                "yyyy-MM-dd",
                "UTC",
                "INR",
                fieldMap,
                1,
                true
        );

        Map<String, String> rowFields = new HashMap<>();
        rowFields.put("RefundId", "re_9921345678");
        rowFields.put("Date", "2026-10-22");
        rowFields.put("Amount", "1250.00");
        rowFields.put("Type", "REFUND");
        rowFields.put("OrigTxnRef", "pi_1122334455");

        SourceRecord record = new SourceRecord(1, "re_9921345678", rowFields, "");

        DeclarativeMappingEngine.NormalizationResult result = engine.normalize(
                record, profile, "tenant-test", "STRIPE");

        assertTrue(result.isExternalRefundWithoutLocalPayment(), "Refund without local payment must be tagged as external refund");
        assertEquals("REFUND", result.entityType());
        assertEquals("re_9921345678", result.entityId());

        Refund refund = objectMapper.readValue(result.canonicalEvent().payload(), Refund.class);
        assertEquals("re_9921345678", refund.refundId());
        assertEquals(new BigDecimal("1250.00"), refund.refundAmount().amount());
        assertEquals("pi_1122334455", refund.originalPaymentReference().value());
    }

    @Test
    @DisplayName("Monetary Precision: enforces 2 decimal places and Banker's Rounding (HALF_EVEN)")
    void testBankersRoundingPrecision() throws Exception {
        Map<String, String> rowFields = new HashMap<>();
        rowFields.put("id", "TXN-ROUND-1");
        rowFields.put("amount", "100.555"); // should round to 100.56 (HALF_EVEN)

        SourceRecord record = new SourceRecord(1, "TXN-ROUND-1", rowFields, "");

        DeclarativeMappingEngine.NormalizationResult result = engine.normalize(
                record, null, "tenant-test", "GENERIC_CSV");

        Payment payment = objectMapper.readValue(result.canonicalEvent().payload(), Payment.class);
        assertEquals(new BigDecimal("100.56"), payment.amount().amount());
    }
}
