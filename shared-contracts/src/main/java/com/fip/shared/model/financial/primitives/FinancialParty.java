package com.fip.shared.model.financial.primitives;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * Immutable representation of a financial participant (Payer, Payee, Counterparty, etc.).
 * Protects against raw PII leakage by strictly rejecting unmasked personal identifiers.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinancialParty(
        String partyId,
        PartyType partyType,
        String displayName,
        String maskedIdentifier
) implements Serializable {

    private static final long serialVersionUID = 1L;

    private static final Pattern UNMASKED_CARD_PATTERN = Pattern.compile("^\\d{13,19}$");
    private static final Pattern UNMASKED_AADHAAR_PATTERN = Pattern.compile("^\\d{12}$");
    private static final Pattern UNMASKED_PAN_PATTERN = Pattern.compile("^[A-Z]{5}[0-9]{4}[A-Z]$");

    public FinancialParty {
        if (partyId == null || partyId.isBlank()) {
            throw new FinancialValidationException("partyId cannot be null or blank");
        }
        if (partyType == null) {
            throw new FinancialValidationException("partyType cannot be null");
        }

        if (maskedIdentifier != null && !maskedIdentifier.isBlank()) {
            String trimmed = maskedIdentifier.trim();
            if (UNMASKED_CARD_PATTERN.matcher(trimmed).matches()) {
                throw new FinancialValidationException("Security violation: Raw unmasked card numbers are prohibited. Mask identifier before submission.");
            }
            if (UNMASKED_AADHAAR_PATTERN.matcher(trimmed).matches()) {
                throw new FinancialValidationException("Security violation: Raw 12-digit Aadhaar numbers are prohibited. Mask identifier (e.g. XXXXXXXX1234) before submission.");
            }
            if (UNMASKED_PAN_PATTERN.matcher(trimmed).matches()) {
                throw new FinancialValidationException("Security violation: Raw unmasked PAN is prohibited. Mask identifier (e.g. XXXXXX1234F) before submission.");
            }
        }
    }

    public static FinancialParty individual(String partyId, String displayName) {
        return new FinancialParty(partyId, PartyType.INDIVIDUAL, displayName, null);
    }

    public static FinancialParty individual(String partyId, String displayName, String maskedIdentifier) {
        return new FinancialParty(partyId, PartyType.INDIVIDUAL, displayName, maskedIdentifier);
    }

    public static FinancialParty organization(String partyId, String displayName) {
        return new FinancialParty(partyId, PartyType.ORGANIZATION, displayName, null);
    }

    public static FinancialParty organization(String partyId, String displayName, String maskedIdentifier) {
        return new FinancialParty(partyId, PartyType.ORGANIZATION, displayName, maskedIdentifier);
    }

    public static FinancialParty merchant(String partyId, String displayName) {
        return new FinancialParty(partyId, PartyType.MERCHANT, displayName, null);
    }

    public static FinancialParty financialInstitution(String partyId, String displayName) {
        return new FinancialParty(partyId, PartyType.FINANCIAL_INSTITUTION, displayName, null);
    }

    public static FinancialParty government(String partyId, String displayName) {
        return new FinancialParty(partyId, PartyType.GOVERNMENT, displayName, null);
    }

    @Override
    public String toString() {
        return String.format("FinancialParty[partyId=%s, partyType=%s, displayName=%s, maskedIdentifier=%s]",
                partyId, partyType, displayName != null ? displayName : "NONE",
                maskedIdentifier != null ? "***" : "NONE");
    }
}
