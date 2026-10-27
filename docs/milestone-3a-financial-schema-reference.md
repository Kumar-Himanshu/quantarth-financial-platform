# QuantArth Financial Intelligence Platform (FIP)
## Milestone 3A — Canonical Financial Schema Reference

**Document Version:** 1.0.0  
**Effective Schema Version:** `1.0.0`  
**Classification:** Fintech Enterprise Standard Specification  
**Package:** `com.fip.shared.model.financial` & `com.fip.shared.events`  

---

## 1. Overview & Architecture Standards

The QuantArth Financial Intelligence Platform (FIP) Canonical Financial Data Model establishes an immutable, precision-preserving, validated domain specification for all financial operations across microservices and external interfaces.

### Core Architecture Tenets:
1. **Zero Floating-Point Representation:** All financial monetary amounts use `BigDecimal` wrapped inside the `Money` value object. Floating-point types (`float`, `double`) are strictly prohibited to prevent IEEE 754 precision corruption.
2. **Explicit Rounding:** Arithmetic operations (division, scaling) default to Banker's Rounding (`RoundingMode.HALF_EVEN`).
3. **ISO 4217 Currency Strictness:** Currency codes are mandatory, validated against the official ISO 4217 standard, and normalized to uppercase 3-letter strings (e.g. `INR`, `USD`, `EUR`).
4. **Strict PII & Authentication Data Protection:** Plaintext PANs, Aadhaar numbers, raw credit card PANs, and sensitive authentication data (CVV, PIN) are rejected at the model boundary. Only masked references (e.g. `XXXXXX1234F`, `XXXX-XXXX-XXXX-1234`) are permitted.
5. **Decoupled Temporal Dimensions:** Financial events track event occurrence (`occurredAt`), accounting value date (`valueDate`), system processing timestamp (`processedAt`), and ledger recording time (`recordedAt`). Instants are UTC-normalized; business dates remain zone-invariant.
6. **Domain Sign Conventions:** Negative amounts are governed by domain semantics rather than rejected globally (e.g., payments and loan principals must be strictly positive; tax records permit negative amounts for refund receivables and tax credits).

---

## 2. Core Financial Primitives Reference

### 2.1 `Money`
Immutable value object for monetary amounts.

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `amount` | `BigDecimal` | Yes | Arbitrary precision numeric value. Maximum scale: 6. |
| `currency` | `String` | Yes | 3-letter uppercase ISO 4217 currency code. |

#### JSON Representation:
```json
{
  "amount": 25000.50,
  "currency": "INR"
}
```

#### Invariant Rules:
- `amount != null` and `currency != null`.
- Scale cannot exceed 6 (`scale <= 6`).
- Cross-currency operations throw `CurrencyMismatchException`.
- Numerical equality ignores trailing zeros (`100.00 == 100.0`).

---

### 2.2 `FinancialParty`
Represents counterparty participants in financial transactions.

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `partyId` | `String` | Yes | Internal unique tenant-scoped identifier. |
| `partyType` | `PartyType` | Yes | `INDIVIDUAL`, `ORGANIZATION`, `MERCHANT`, `FINANCIAL_INSTITUTION`, `GOVERNMENT`. |
| `displayName` | `String` | No | Human-readable name or business DBA. |
| `maskedIdentifier` | `String` | No | Masked tax/national ID (e.g., `XXXXXX1234F`). |

#### JSON Representation:
```json
{
  "partyId": "party-ind-8812",
  "partyType": "INDIVIDUAL",
  "displayName": "Vikram Malhotra",
  "maskedIdentifier": "XXXXXX9876K"
}
```

---

### 2.3 `AccountReference`
Represents bank accounts, wallets, or credit facilities.

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `accountId` | `String` | Yes | Internal account identifier. |
| `accountType` | `AccountType` | Yes | `SAVINGS`, `CURRENT`, `CHECKING`, `CREDIT_CARD`, `LOAN`, `WALLET`, `VIRTUAL`, `ESCROW`, `INVESTMENT`. |
| `institutionId` | `String` | No | Financial institution code (e.g. `HDFC`, `SBIN`). |
| `institutionName` | `String` | No | Name of financial institution. |
| `maskedAccountNumber` | `String` | No | Masked account number (e.g. `XXXX1234`). |
| `routingCode` | `String` | No | Clearing code (IFSC, SWIFT/BIC, ABA routing). |
| `countryCode` | `String` | No | ISO 3166-1 alpha-2 country code (default `"IN"`). |

#### JSON Representation:
```json
{
  "accountId": "acc-sbi-9901",
  "accountType": "SAVINGS",
  "institutionId": "SBIN",
  "institutionName": "State Bank of India",
  "maskedAccountNumber": "XXXX-XXXX-4567",
  "routingCode": "SBIN0001234",
  "countryCode": "IN"
}
```

---

### 2.4 `FinancialReference`
External identifiers for cross-system correlation.

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `type` | `FinancialReferenceType` | Yes | `UPI_RRNO`, `BANK_UTR`, `IMPS_REF`, `NEFT_REF`, `RTGS_REF`, `LOAN_ACCOUNT_NUMBER`, `INVOICE_NUMBER`, `REFUND_ARN`, `REFUND_ID`, `PAYMENT_GATEWAY_ID`, `TAX_CHALLAN_CIN`, `GSTIN`, `TAN`, `ORDER_ID`, `SUBSCRIPTION_ID`, `EXTERNAL_SYSTEM_REF`. |
| `namespace` | `String` | Yes | Authority or source system (e.g. `NPCI_UPI`, `RBI_NEFT_RTGS`, `GSTN_INDIA`). |
| `value` | `String` | Yes | Reference value string. |

#### JSON Representation:
```json
{
  "type": "UPI_RRNO",
  "namespace": "NPCI_UPI",
  "value": "429810987654"
}
```

---

### 2.5 `FinancialTime`
Temporal tracking of transactions across financial lifecycle phases.

| Field | Type | Required | Description |
| :--- | :--- | :--- | :--- |
| `occurredAt` | `Instant` | Yes | ISO-8601 UTC timestamp of occurrence. |
| `valueDate` | `LocalDate` | Yes | Business value date for general ledger posting. |
| `processedAt` | `Instant` | Yes | UTC timestamp of system processing. |
| `recordedAt` | `Instant` | Yes | UTC timestamp of immutable ledger recording. |

#### JSON Representation:
```json
{
  "occurredAt": "2026-10-27T02:00:00Z",
  "valueDate": "2026-10-27",
  "processedAt": "2026-10-27T02:00:00.125Z",
  "recordedAt": "2026-10-27T02:00:00.130Z"
}
```

---

## 3. Canonical Domain Models

### 3.1 `Payment`
Represents payments, peer-to-peer transfers, and settlements.

#### State Transitions:
- `INITIATED` → `PENDING`, `COMPLETED`, `FAILED`, `CANCELLED`
- `PENDING` → `COMPLETED`, `FAILED`, `CANCELLED`
- `COMPLETED`, `FAILED`, `CANCELLED` are terminal.

#### JSON Example (Realistic Indian UPI Merchant Payment):
```json
{
  "paymentId": "pay-upi-20261027-0091",
  "amount": {
    "amount": 1450.00,
    "currency": "INR"
  },
  "payer": {
    "partyId": "usr-amit-sharma",
    "partyType": "INDIVIDUAL",
    "displayName": "Amit Sharma",
    "maskedIdentifier": "XXXXXX4567A"
  },
  "payee": {
    "partyId": "mer-flipkart",
    "partyType": "MERCHANT",
    "displayName": "Flipkart Internet Private Limited"
  },
  "paymentMethod": "UPI",
  "status": "COMPLETED",
  "timestamp": {
    "occurredAt": "2026-10-27T07:30:00Z",
    "valueDate": "2026-10-27",
    "processedAt": "2026-10-27T07:30:00.250Z",
    "recordedAt": "2026-10-27T07:30:00.260Z"
  },
  "references": [
    {
      "type": "UPI_RRNO",
      "namespace": "NPCI_UPI",
      "value": "429810987654"
    },
    {
      "type": "ORDER_ID",
      "namespace": "FLIPKART_OMS",
      "value": "OD-2026-10-998811"
    }
  ],
  "payerAccount": {
    "accountId": "acc-sbi-3311",
    "accountType": "SAVINGS",
    "institutionId": "SBIN",
    "institutionName": "State Bank of India",
    "maskedAccountNumber": "XXXX-XXXX-9912",
    "routingCode": "SBIN0005678",
    "countryCode": "IN"
  },
  "payeeAccount": {
    "accountId": "acc-hdfc-corp",
    "accountType": "CURRENT",
    "institutionId": "HDFC",
    "institutionName": "HDFC Bank",
    "maskedAccountNumber": "XXXX-XXXX-0011",
    "routingCode": "HDFC0000001",
    "countryCode": "IN"
  }
}
```

---

### 3.2 `Transfer`
Movement of funds between accounts owned or managed within an organization or individual portfolio.

#### JSON Example (Inter-Bank Liquidity Sweep):
```json
{
  "transferId": "trf-sweep-2026-004",
  "sourceAccount": {
    "accountId": "acc-icici-treasury",
    "accountType": "CURRENT",
    "institutionId": "ICIC",
    "maskedAccountNumber": "XXXX-XXXX-7700",
    "routingCode": "ICIC0000002",
    "countryCode": "IN"
  },
  "destinationAccount": {
    "accountId": "acc-hdfc-escrow",
    "accountType": "ESCROW",
    "institutionId": "HDFC",
    "maskedAccountNumber": "XXXX-XXXX-8822",
    "routingCode": "HDFC0000001",
    "countryCode": "IN"
  },
  "amount": {
    "amount": 25000000.00,
    "currency": "INR"
  },
  "status": "COMPLETED",
  "timestamp": {
    "occurredAt": "2026-10-27T05:00:00Z",
    "valueDate": "2026-10-27"
  },
  "references": [
    {
      "type": "RTGS_REF",
      "namespace": "RBI_NEFT_RTGS",
      "value": "ICICR52026102700099"
    }
  ]
}
```

---

### 3.3 `Salary`
Payroll compensation disbursement with itemized deductions. Gross salary is never inferred from net salary.

#### JSON Example (Indian Corporate Monthly Payroll):
```json
{
  "salaryId": "sal-infosys-202610-8819",
  "employer": {
    "partyId": "org-infy",
    "partyType": "ORGANIZATION",
    "displayName": "Infosys Limited",
    "maskedIdentifier": "XXXXXX1234I"
  },
  "employee": {
    "partyId": "emp-8819",
    "partyType": "INDIVIDUAL",
    "displayName": "Pooja Deshmukh",
    "maskedIdentifier": "XXXXXX9876P"
  },
  "grossAmount": {
    "amount": 180000.00,
    "currency": "INR"
  },
  "netAmount": {
    "amount": 147800.00,
    "currency": "INR"
  },
  "payPeriod": {
    "startDate": "2026-10-01",
    "endDate": "2026-10-31"
  },
  "paymentDate": "2026-10-31",
  "deductions": [
    {
      "deductionType": "EPF",
      "amount": { "amount": 12000.00, "currency": "INR" },
      "description": "Employee Provident Fund (12% of basic)"
    },
    {
      "deductionType": "TDS",
      "amount": { "amount": 18000.00, "currency": "INR" },
      "description": "Tax Deducted at Source Sec 192"
    },
    {
      "deductionType": "PROFESSIONAL_TAX",
      "amount": { "amount": 200.00, "currency": "INR" },
      "description": "Maharashtra Professional Tax"
    },
    {
      "deductionType": "GROUP_MEDICLAIM",
      "amount": { "amount": 2000.00, "currency": "INR" },
      "description": "Family Health Insurance Premium"
    }
  ]
}
```

---

### 3.4 `Refund`
Reimbursement associated with a previous payment. Supports partial refunds.

#### JSON Example (Partial E-commerce Refund):
```json
{
  "refundId": "ref-rzp-001928",
  "originalPaymentReference": {
    "type": "PAYMENT_GATEWAY_ID",
    "namespace": "RAZORPAY",
    "value": "pay_Oa98X12938"
  },
  "originalPaymentAmount": {
    "amount": 8999.00,
    "currency": "INR"
  },
  "refundAmount": {
    "amount": 2499.00,
    "currency": "INR"
  },
  "currency": "INR",
  "status": "COMPLETED",
  "timestamp": {
    "occurredAt": "2026-10-27T06:15:00Z",
    "valueDate": "2026-10-27"
  },
  "reason": "Customer returned 1 of 3 items in order"
}
```

---

### 3.5 `Loan`
Credit facility or debt agreement state.

#### JSON Example (20-Year Floating Rate Home Loan):
```json
{
  "loanId": "loan-hdfc-hl-99201",
  "lender": {
    "partyId": "bank-hdfc",
    "partyType": "FINANCIAL_INSTITUTION",
    "displayName": "HDFC Bank Limited"
  },
  "borrower": {
    "partyId": "usr-vikram-malhotra",
    "partyType": "INDIVIDUAL",
    "displayName": "Vikram Malhotra",
    "maskedIdentifier": "XXXXXX9876K"
  },
  "principalAmount": {
    "amount": 6000000.00,
    "currency": "INR"
  },
  "outstandingBalance": {
    "amount": 5820000.00,
    "currency": "INR"
  },
  "interestTerms": {
    "annualPercentageRate": 8.45,
    "interestType": "FLOATING",
    "benchmarkIndex": "RBI_REPO_RATE_EBLR",
    "compoundingFrequency": "MONTHLY"
  },
  "disbursementDate": "2026-01-15",
  "maturityDate": "2046-01-15",
  "status": "ACTIVE"
}
```

---

### 3.6 `LoanRepayment`
Installment or debt service payment with itemized component breakdown.

#### JSON Example (Monthly Home Loan EMI):
```json
{
  "repaymentId": "repay-202610-091",
  "loanReference": {
    "type": "LOAN_ACCOUNT_NUMBER",
    "namespace": "HDFC_RETAIL_LOANS",
    "value": "HL-449911-002"
  },
  "amount": {
    "amount": 51920.00,
    "currency": "INR"
  },
  "principalComponent": {
    "amount": 12850.00,
    "currency": "INR"
  },
  "interestComponent": {
    "amount": 39070.00,
    "currency": "INR"
  },
  "feeComponent": {
    "amount": 0.00,
    "currency": "INR"
  },
  "dueDate": "2026-10-10",
  "paymentDate": "2026-10-09",
  "status": "COMPLETED"
}
```

---

### 3.7 `Invoice`
Commercial billing document with subtotal and tax integrity.

#### JSON Example (B2B SaaS Subscription Invoice with 18% GST):
```json
{
  "invoiceId": "inv-qa-2026-0089",
  "issuer": {
    "partyId": "org-qa-tech",
    "partyType": "ORGANIZATION",
    "displayName": "QuantArth Cloud Technologies Pvt Ltd",
    "maskedIdentifier": "XXXXXX9876Q"
  },
  "customer": {
    "partyId": "cust-zerodha",
    "partyType": "ORGANIZATION",
    "displayName": "Zerodha Broking Limited",
    "maskedIdentifier": "XXXXXX5432Z"
  },
  "invoiceNumber": "QA/2026-27/0089",
  "issueDate": "2026-10-01",
  "dueDate": "2026-10-31",
  "subtotal": {
    "amount": 100000.00,
    "currency": "INR"
  },
  "taxAmount": {
    "amount": 18000.00,
    "currency": "INR"
  },
  "totalAmount": {
    "amount": 118000.00,
    "currency": "INR"
  },
  "currency": "INR",
  "paymentStatus": "PAID",
  "paidAmount": {
    "amount": 118000.00,
    "currency": "INR"
  }
}
```

---

### 3.8 `Subscription`
Recurring billing agreement.

#### JSON Example (Annual Financial API Subscription):
```json
{
  "subscriptionId": "sub-marketdata-ann-01",
  "provider": {
    "partyId": "org-quantarth",
    "partyType": "ORGANIZATION",
    "displayName": "QuantArth Market Intelligence"
  },
  "customer": {
    "partyId": "cust-apex-fund",
    "partyType": "ORGANIZATION",
    "displayName": "Apex Quant Fund LLC"
  },
  "recurringAmount": {
    "amount": 3500.00,
    "currency": "USD"
  },
  "currency": "USD",
  "billingFrequency": "ANNUALLY",
  "startDate": "2026-01-01",
  "endDate": "2027-01-01",
  "status": "ACTIVE"
}
```

---

### 3.9 `Tax`
Statutory direct and indirect tax records. Supports India-specific GST/TDS and negative tax refund credits.

#### JSON Example (Advance Tax Challan 280):
```json
{
  "taxRecordId": "tax-challan-280-ay27-q2",
  "taxType": "INCOME_TAX",
  "jurisdiction": "IN-CENTRAL",
  "taxPeriod": "AY-2027-28",
  "amount": {
    "amount": 500000.00,
    "currency": "INR"
  },
  "currency": "INR",
  "dueDate": "2026-09-15",
  "paymentStatus": "PAID",
  "references": [
    {
      "type": "TAX_CHALLAN_CIN",
      "namespace": "INCOME_TAX_DEPT",
      "value": "0002801509202699881"
    },
    {
      "type": "TAN",
      "namespace": "INCOME_TAX_DEPT",
      "value": "MUMB12345F"
    }
  ]
}
```

---

## 4. Canonical Event Envelope (`CanonicalEvent`)

### Specification:
```json
{
  "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
  "tenantId": "tenant-corp-1",
  "schemaVersion": "1.0.0",
  "eventType": "PaymentInitiated",
  "entityType": "PAYMENT",
  "entityId": "pay-upi-20261027-0091",
  "sourceSystem": "payment-gateway",
  "occurredAt": "2026-10-27T07:30:00Z",
  "recordedAt": "2026-10-27T07:30:00.050Z",
  "correlationId": "corr-uuid-9901",
  "causationId": "cause-uuid-8802",
  "idempotencyKey": "idem-key-7703",
  "metadata": {
    "traceId": "trace-edge-100",
    "ipAddress": "203.0.113.195",
    "deviceType": "IOS"
  },
  "payload": "{\"paymentId\":\"pay-upi-20261027-0091\",\"amount\":{\"amount\":1450.00,\"currency\":\"INR\"},\"status\":\"INITIATED\"}"
}
```

---

## 5. Schema Evolution & Versioning Strategy

### 5.1 Backward-Compatible Changes (Minor / Patch: `1.x.x`)
- Adding an optional field with `@JsonInclude(NON_NULL)`.
- Adding new enum values (consumers must map unknown enums to fallback or ignore).
- Adding new reference types to `FinancialReferenceType`.
- Legacy alias mapping:
  - `eventSource` ↔ `sourceSystem`
  - `timestamp` ↔ `occurredAt`
  - `traceId` ↔ `correlationId`
  - `version` ↔ `schemaVersion`

### 5.2 Breaking Changes (Major: `2.0.0`)
- Removing or renaming an existing mandatory field without `@JsonAlias`.
- Changing the data type of an existing field.
- Changing semantic validation constraints (e.g. tightening scale limit from 6 to 2).

### 5.3 Deprecation Policy
- Deprecated fields must be maintained for at least two minor version cycles before decommissioning.
- Consumers must configure `@JsonIgnoreProperties(ignoreUnknown = true)` to ensure immunity to additive schema evolution.
