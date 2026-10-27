package com.fip.shared.model.financial.primitives;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * Immutable reference to a financial account.
 * Prohibits raw card PANs and sensitive authentication data.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record AccountReference(
        String accountId,
        AccountType accountType,
        String institutionId,
        String institutionName,
        String maskedAccountNumber,
        String routingCode,
        String countryCode
) implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Pattern UNMASKED_CARD_PATTERN = Pattern.compile("^\\d{13,19}$");

    public AccountReference {
        if (accountId == null || accountId.isBlank()) {
            throw new FinancialValidationException("accountId cannot be null or blank");
        }
        if (accountType == null) {
            throw new FinancialValidationException("accountType cannot be null");
        }

        if (maskedAccountNumber != null && !maskedAccountNumber.isBlank()) {
            String trimmed = maskedAccountNumber.trim();
            if (UNMASKED_CARD_PATTERN.matcher(trimmed).matches()) {
                throw new FinancialValidationException(
                        "Security violation: Storing raw unmasked primary account numbers (PAN) is strictly prohibited. Mask account number (e.g. XXXX-XXXX-XXXX-1234).");
            }
        }

        countryCode = (countryCode != null && !countryCode.isBlank()) ? countryCode.trim().toUpperCase() : "IN";
    }

    public static AccountReference savings(String accountId, String institutionId, String maskedAccountNumber, String routingCode) {
        return new AccountReference(accountId, AccountType.SAVINGS, institutionId, null, maskedAccountNumber, routingCode, "IN");
    }

    public static AccountReference current(String accountId, String institutionId, String maskedAccountNumber, String routingCode) {
        return new AccountReference(accountId, AccountType.CURRENT, institutionId, null, maskedAccountNumber, routingCode, "IN");
    }

    public static AccountReference virtual(String accountId, String institutionId) {
        return new AccountReference(accountId, AccountType.VIRTUAL, institutionId, null, null, null, "IN");
    }

    public static AccountReference wallet(String accountId, String provider) {
        return new AccountReference(accountId, AccountType.WALLET, provider, provider, null, null, "IN");
    }

    public static AccountReference loan(String accountId, String institutionId, String maskedAccountNumber) {
        return new AccountReference(accountId, AccountType.LOAN, institutionId, null, maskedAccountNumber, null, "IN");
    }

    @Override
    public String toString() {
        return String.format("AccountReference[accountId=***, accountType=%s, institutionId=%s, maskedAccountNumber=%s, countryCode=%s]",
                accountType, institutionId, maskedAccountNumber != null ? maskedAccountNumber : "NONE", countryCode);
    }
}
