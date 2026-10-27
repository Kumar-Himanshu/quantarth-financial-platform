package com.fip.shared.model.financial.primitives;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.fip.shared.model.financial.validation.FinancialValidationException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FinancialPrimitivesTest {

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @Test
    @DisplayName("FinancialParty validates required fields and prohibits raw PII identifiers")
    void testFinancialPartyValidationAndPiiProtection() {
        FinancialParty valid = FinancialParty.individual("usr-100", "Aditi Sharma", "XXXXXX1234F");
        assertThat(valid.partyId()).isEqualTo("usr-100");
        assertThat(valid.partyType()).isEqualTo(PartyType.INDIVIDUAL);
        assertThat(valid.maskedIdentifier()).isEqualTo("XXXXXX1234F");

        // Null / blank partyId
        assertThatThrownBy(() -> new FinancialParty(null, PartyType.INDIVIDUAL, "Aditi", null))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("partyId cannot be null or blank");

        // Null partyType
        assertThatThrownBy(() -> new FinancialParty("p-1", null, "Name", null))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("partyType cannot be null");

        // Prohibit raw unmasked 16-digit card numbers
        assertThatThrownBy(() -> FinancialParty.individual("usr-100", "Aditi", "4111222233334444"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Raw unmasked card numbers are prohibited");

        // Prohibit raw unmasked 12-digit Aadhaar
        assertThatThrownBy(() -> FinancialParty.individual("usr-100", "Aditi", "123456789012"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Raw 12-digit Aadhaar numbers are prohibited");

        // Prohibit raw unmasked PAN
        assertThatThrownBy(() -> FinancialParty.individual("usr-100", "Aditi", "ABCDE1234F"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Raw unmasked PAN is prohibited");
    }

    @Test
    @DisplayName("AccountReference protects against raw PAN numbers and defaults countryCode to IN")
    void testAccountReferenceProtection() {
        AccountReference acc = AccountReference.savings("acc-99", "HDFC", "XXXX-XXXX-1234", "HDFC0001234");
        assertThat(acc.accountId()).isEqualTo("acc-99");
        assertThat(acc.accountType()).isEqualTo(AccountType.SAVINGS);
        assertThat(acc.institutionId()).isEqualTo("HDFC");
        assertThat(acc.routingCode()).isEqualTo("HDFC0001234");
        assertThat(acc.countryCode()).isEqualTo("IN");

        // Reject raw card PAN
        assertThatThrownBy(() -> new AccountReference("acc-1", AccountType.CREDIT_CARD, "SBI", null, "4111222233334444", null, "IN"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Storing raw unmasked primary account numbers (PAN) is strictly prohibited");

        // Blank accountId
        assertThatThrownBy(() -> new AccountReference("", AccountType.SAVINGS, "SBI", null, null, null, "IN"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("accountId cannot be null or blank");
    }

    @Test
    @DisplayName("FinancialReference validates type, namespace, and value strictly")
    void testFinancialReferenceValidation() {
        FinancialReference upi = FinancialReference.upi("429812345678");
        assertThat(upi.type()).isEqualTo(FinancialReferenceType.UPI_RRNO);
        assertThat(upi.namespace()).isEqualTo("NPCI_UPI");
        assertThat(upi.value()).isEqualTo("429812345678");

        FinancialReference utr = FinancialReference.bankUtr("HDFCR5202610270001");
        assertThat(utr.type()).isEqualTo(FinancialReferenceType.BANK_UTR);
        assertThat(utr.namespace()).isEqualTo("RBI_NEFT_RTGS");

        FinancialReference gstin = FinancialReference.gstin("27AABCU9603R1ZM");
        assertThat(gstin.type()).isEqualTo(FinancialReferenceType.GSTIN);
        assertThat(gstin.namespace()).isEqualTo("GSTN_INDIA");

        assertThatThrownBy(() -> new FinancialReference(null, "NPCI", "123"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("type cannot be null");

        assertThatThrownBy(() -> new FinancialReference(FinancialReferenceType.UPI_RRNO, " ", "123"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("namespace cannot be null or blank");

        assertThatThrownBy(() -> new FinancialReference(FinancialReferenceType.UPI_RRNO, "NPCI", ""))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("value cannot be null or blank");
    }

    @Test
    @DisplayName("FinancialTime handles UTC normalization and Indian Standard Time (IST) derivation correctly")
    void testFinancialTimeHandling() {
        Instant instant = Instant.parse("2026-10-27T02:00:00Z"); // 07:30 AM IST
        LocalDate valueDate = LocalDate.of(2026, 10, 27);

        FinancialTime ft = FinancialTime.of(instant, valueDate);
        assertThat(ft.occurredAt()).isEqualTo(instant);
        assertThat(ft.valueDate()).isEqualTo(valueDate);

        // Derive IST
        ZonedDateTime ist = ft.toIst();
        assertThat(ist.getZone()).isEqualTo(ZoneId.of("Asia/Kolkata"));
        assertThat(ist.getHour()).isEqualTo(7);
        assertThat(ist.getMinute()).isEqualTo(30);

        // UTC conversion
        ZonedDateTime utc = ft.toUtc();
        assertThat(utc.getHour()).isEqualTo(2);

        // Null occurredAt rejection
        assertThatThrownBy(() -> new FinancialTime(null, valueDate, instant, instant))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("occurredAt cannot be null");
    }

    @Test
    @DisplayName("Financial primitives round-trip serialize to JSON without information loss")
    void testJacksonRoundTrip() throws Exception {
        FinancialParty party = FinancialParty.organization("org-tata", "Tata Consultancy Services Ltd", "XXXXXX9876T");
        String partyJson = objectMapper.writeValueAsString(party);
        FinancialParty deserializedParty = objectMapper.readValue(partyJson, FinancialParty.class);
        assertThat(deserializedParty).isEqualTo(party);

        FinancialReference ref = FinancialReference.upi("330198765432");
        String refJson = objectMapper.writeValueAsString(ref);
        FinancialReference deserializedRef = objectMapper.readValue(refJson, FinancialReference.class);
        assertThat(deserializedRef).isEqualTo(ref);
    }

    @Test
    @DisplayName("Review 5: FinancialReference strictly rejects raw 13-19 digit card numbers")
    void testFinancialReferenceRejectsCardNumbers() {
        assertThatThrownBy(() -> FinancialReference.paymentGateway("4111222233334444", "STRIPE"))
                .isInstanceOf(FinancialValidationException.class)
                .hasMessageContaining("Storing raw unmasked payment card numbers");
    }

    @Test
    @DisplayName("Review 5: Log-safe toString prevents leakage of sensitive accounts and identifiers")
    void testLogSafeToStringMasking() {
        AccountReference acc = AccountReference.savings("secret-acc-998877", "HDFC", "XXXX-XXXX-1234", "HDFC0001234");
        String accStr = acc.toString();
        assertThat(accStr).doesNotContain("secret-acc-998877");
        assertThat(accStr).contains("accountId=***");

        FinancialReference ref = FinancialReference.paymentGateway("pg-trans-secret-value", "RAZORPAY");
        String refStr = ref.toString();
        assertThat(refStr).doesNotContain("pg-trans-secret-value");
        assertThat(refStr).contains("value=***");

        FinancialParty party = FinancialParty.individual("usr-1", "Aadhaar Cardholder", "XXXXXXXX1234");
        String partyStr = party.toString();
        assertThat(partyStr).doesNotContain("XXXXXXXX1234");
        assertThat(partyStr).contains("maskedIdentifier=***");
    }
}
