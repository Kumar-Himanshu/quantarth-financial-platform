package com.fip.ingestion.mapping;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.ingestion.domain.model.FieldMapping;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.SourceRecord;
import com.fip.ingestion.domain.model.SourceType;
import com.fip.ingestion.parsing.CsvStreamAdapter;
import com.fip.ingestion.util.CsvExportSanitizer;
import com.fip.shared.model.financial.domain.Payment;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Milestone 4B Section 5: Financial Data Integrity Verification Tests.
 * <p>
 * Verifies:
 * - Exact monetary parsing without IEEE 754 floating point precision corruption
 * - Exact Banker's Rounding (HALF_EVEN) to currency scale
 * - Zero or negative amounts fail validation cleanly rather than silently fabricating synthetic money (1.00)
 * - Correct debit/credit interpretation across SPLIT_DR_CR and SIGNED_AMOUNT strategies
 * - Currency handling and validation
 * - Date and timezone handling (preserves exact point in time across timezones)
 * - Preservation of source references
 * - No mutation of original descriptions for CSV formula protection on ingestion
 * - Formula-injection escaping applied exclusively at export boundary
 */
@DisplayName("Financial Data Integrity Verification Tests")
class FinancialDataIntegrityTest {

    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final DeclarativeMappingEngine engine = new DeclarativeMappingEngine(objectMapper);
    private final CsvStreamAdapter csvAdapter = new CsvStreamAdapter();

    @Test
    @DisplayName("5.1 Exact monetary parsing: Parses large, fractional monetary values without float rounding errors")
    void testExactMonetaryParsing() throws Exception {
        Map<String, String> row = Map.of(
                "id", "TXN-PRECISION-1",
                "amount", "123456789.875"
        );
        SourceRecord record = new SourceRecord(1, "TXN-PRECISION-1", row, "");

        DeclarativeMappingEngine.NormalizationResult result = engine.normalize(
                record, null, "tenant-precision", "GATEWAY");

        Payment payment = objectMapper.readValue(result.canonicalEvent().payload(), Payment.class);

        // Banker's Rounding: .875 with HALF_EVEN rounds to .88 (odd 7 rounds up to 8)
        assertThat(payment.amount().amount()).isEqualTo(new BigDecimal("123456789.88"));
        assertThat(payment.amount().currency()).isEqualTo("INR");
    }

    @Test
    @DisplayName("5.2 Zero amount rejection: Ingestion does NOT silently fabricate 1.00 placeholder; fails validation")
    void testZeroAmountRejectionWithoutSilentModification() {
        Map<String, String> row = Map.of(
                "id", "TXN-ZERO-1",
                "amount", "0.00"
        );
        SourceRecord record = new SourceRecord(1, "TXN-ZERO-1", row, "");

        // When amount is 0.00, FinancialModelValidator must reject it
        assertThatThrownBy(() -> engine.normalize(record, null, "tenant-zero", "GATEWAY"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Payment amount must be strictly positive");
    }

    @Test
    @DisplayName("5.3 Debit/Credit split interpretation: Correctly picks credit amount or debit amount")
    void testSplitDebitCreditInterpretation() throws Exception {
        Map<String, FieldMapping> fieldMap = new HashMap<>();
        fieldMap.put("transactionId", FieldMapping.column("TxnId"));
        fieldMap.put("amount", FieldMapping.splitDrCr("Withdrawal", "Deposit", 2));

        MappingProfile profile = new MappingProfile(
                UUID.randomUUID(), "tenant-drcr", "DR_CR_PROFILE", SourceType.CSV, "PAYMENT",
                ",", true, "yyyy-MM-dd", "UTC", "INR", fieldMap, 1, true);

        // Case A: Credit/Deposit of 5000.00
        Map<String, String> creditRow = Map.of("TxnId", "TXN-CR-1", "Withdrawal", "", "Deposit", "5000.00");
        DeclarativeMappingEngine.NormalizationResult resCr = engine.normalize(
                new SourceRecord(1, "TXN-CR-1", creditRow, ""), profile, "tenant-drcr", "BANK");

        Payment payCr = objectMapper.readValue(resCr.canonicalEvent().payload(), Payment.class);
        assertThat(payCr.amount().amount()).isEqualTo(new BigDecimal("5000.00"));

        // Case B: Debit/Withdrawal of 2500.50
        Map<String, String> debitRow = Map.of("TxnId", "TXN-DR-1", "Withdrawal", "2500.50", "Deposit", "");
        DeclarativeMappingEngine.NormalizationResult resDr = engine.normalize(
                new SourceRecord(2, "TXN-DR-1", debitRow, ""), profile, "tenant-drcr", "BANK");

        Payment payDr = objectMapper.readValue(resDr.canonicalEvent().payload(), Payment.class);
        assertThat(payDr.amount().amount()).isEqualTo(new BigDecimal("2500.50"));
    }

    @Test
    @DisplayName("5.4 Date and timezone handling: Converts Asia/Kolkata date to exact UTC Instant")
    void testDateTimezoneConversion() throws Exception {
        Map<String, FieldMapping> fieldMap = new HashMap<>();
        fieldMap.put("transactionId", FieldMapping.column("TxnId"));
        fieldMap.put("amount", FieldMapping.column("Amount"));
        fieldMap.put("occurredAt", FieldMapping.column("Date"));

        MappingProfile profile = new MappingProfile(
                UUID.randomUUID(), "tenant-tz", "TZ_PROFILE", SourceType.CSV, "PAYMENT",
                ",", true, "dd/MM/yyyy HH:mm:ss", "Asia/Kolkata", "INR", fieldMap, 1, true);

        // 2026-10-25 10:30:00 IST -> 2026-10-25 05:00:00 UTC (IST is UTC+05:30)
        Map<String, String> row = Map.of(
                "TxnId", "TXN-TIME-1",
                "Amount", "100.00",
                "Date", "25/10/2026 10:30:00"
        );
        SourceRecord record = new SourceRecord(1, "TXN-TIME-1", row, "");

        DeclarativeMappingEngine.NormalizationResult result = engine.normalize(
                record, profile, "tenant-tz", "CORE");

        Instant occurredAt = result.canonicalEvent().occurredAt();
        ZonedDateTime utcTime = occurredAt.atZone(ZoneId.of("UTC"));

        assertThat(utcTime.getHour()).isEqualTo(5);
        assertThat(utcTime.getMinute()).isEqualTo(0);
        assertThat(utcTime.getDayOfMonth()).isEqualTo(25);
    }

    @Test
    @DisplayName("5.5 Preservation of source references: Original source record ID and system are preserved in Payment references")
    void testPreservationOfSourceReferences() throws Exception {
        Map<String, String> row = Map.of(
                "id", "ORIG_PAY_SYS_REF_998877",
                "amount", "750.00"
        );
        SourceRecord record = new SourceRecord(1, "ORIG_PAY_SYS_REF_998877", row, "");

        DeclarativeMappingEngine.NormalizationResult result = engine.normalize(
                record, null, "tenant-ref", "RAZORPAY");

        Payment payment = objectMapper.readValue(result.canonicalEvent().payload(), Payment.class);
        assertThat(payment.references()).isNotEmpty();
        assertThat(payment.references().get(0).value()).isEqualTo("ORIG_PAY_SYS_REF_998877");
        assertThat(payment.references().get(0).namespace()).isEqualTo("RAZORPAY");
    }

    @Test
    @DisplayName("5.6 No mutation of imported descriptions: Formula characters are NOT modified during ingestion")
    void testNoMutationOfImportedDescriptions() throws Exception {
        String csv = """
                id,narration,amount
                ROW-1,=2+3,100.00
                ROW-2,+919876543210,200.00
                ROW-3,-REFUND-ADJUSTMENT,300.00
                ROW-4,@SUPPORT_DESK,400.00
                """;

        InputStream stream = new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
        List<SourceRecord> records = new ArrayList<>();
        csvAdapter.parseStream(stream, ",", true, 0, records::add);

        assertThat(records).hasSize(4);
        assertThat(records.get(0).getValue("narration")).isEqualTo("=2+3");
        assertThat(records.get(1).getValue("narration")).isEqualTo("+919876543210");
        assertThat(records.get(2).getValue("narration")).isEqualTo("-REFUND-ADJUSTMENT");
        assertThat(records.get(3).getValue("narration")).isEqualTo("@SUPPORT_DESK");

        // Verify that escaping is applied only when invoking CsvExportSanitizer
        assertThat(CsvExportSanitizer.sanitizeForExport(records.get(0).getValue("narration"))).isEqualTo("'=2+3");
        assertThat(CsvExportSanitizer.sanitizeForExport(records.get(1).getValue("narration"))).isEqualTo("'+919876543210");
        assertThat(CsvExportSanitizer.sanitizeForExport(records.get(2).getValue("narration"))).isEqualTo("'-REFUND-ADJUSTMENT");
        assertThat(CsvExportSanitizer.sanitizeForExport(records.get(3).getValue("narration"))).isEqualTo("'@SUPPORT_DESK");
    }
}
