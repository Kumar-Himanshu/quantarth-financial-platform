package com.fip.shared.model.financial.primitives;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fip.shared.model.financial.validation.FinancialValidationException;

import java.io.Serializable;
import java.util.regex.Pattern;

/**
 * Immutable reference to an external transaction or artifact (UPI RRN, Bank UTR, Invoice No, etc.).
 * Guarantees explicit source namespace tracking and prevents sensitive cardholder data leakage.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record FinancialReference(
        FinancialReferenceType type,
        String namespace,
        String value
) implements Serializable {

    private static final long serialVersionUID = 1L;
    private static final Pattern UNMASKED_CARD_PATTERN = Pattern.compile("^\\d{13,19}$");

    public FinancialReference {
        if (type == null) {
            throw new FinancialValidationException("FinancialReference type cannot be null");
        }
        if (namespace == null || namespace.isBlank()) {
            throw new FinancialValidationException("FinancialReference namespace cannot be null or blank");
        }
        if (value == null || value.isBlank()) {
            throw new FinancialValidationException("FinancialReference value cannot be null or blank");
        }
        String trimmed = value.trim();
        if (type != FinancialReferenceType.TAX_CHALLAN_CIN && UNMASKED_CARD_PATTERN.matcher(trimmed).matches()) {
            throw new FinancialValidationException(
                    "Security violation: Storing raw unmasked payment card numbers in FinancialReference is strictly prohibited.");
        }
        namespace = namespace.trim();
        value = trimmed;
    }

    public static FinancialReference upi(String rrn) {
        return new FinancialReference(FinancialReferenceType.UPI_RRNO, "NPCI_UPI", rrn);
    }

    public static FinancialReference bankUtr(String utr) {
        return new FinancialReference(FinancialReferenceType.BANK_UTR, "RBI_NEFT_RTGS", utr);
    }

    public static FinancialReference imps(String ref) {
        return new FinancialReference(FinancialReferenceType.IMPS_REF, "NPCI_IMPS", ref);
    }

    public static FinancialReference invoice(String invoiceNumber, String issuerNamespace) {
        return new FinancialReference(FinancialReferenceType.INVOICE_NUMBER, issuerNamespace, invoiceNumber);
    }

    public static FinancialReference refundArn(String arn) {
        return new FinancialReference(FinancialReferenceType.REFUND_ARN, "BANK_NETWORK", arn);
    }

    public static FinancialReference paymentGateway(String gatewayId, String provider) {
        return new FinancialReference(FinancialReferenceType.PAYMENT_GATEWAY_ID, provider, gatewayId);
    }

    public static FinancialReference loanAccount(String loanAccountNumber, String lenderNamespace) {
        return new FinancialReference(FinancialReferenceType.LOAN_ACCOUNT_NUMBER, lenderNamespace, loanAccountNumber);
    }

    public static FinancialReference taxCin(String cin) {
        return new FinancialReference(FinancialReferenceType.TAX_CHALLAN_CIN, "INCOME_TAX_DEPT", cin);
    }

    public static FinancialReference gstin(String gstin) {
        return new FinancialReference(FinancialReferenceType.GSTIN, "GSTN_INDIA", gstin);
    }

    @Override
    public String toString() {
        String safeValue = (value != null && value.length() > 4)
                ? "***" + value.substring(value.length() - 4)
                : "***";
        return String.format("FinancialReference[type=%s, namespace=%s, value=%s]", type, namespace, safeValue);
    }
}
