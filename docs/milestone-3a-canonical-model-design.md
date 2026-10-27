# QuantArth Financial Intelligence Platform (FIP)
## Milestone 3A — Canonical Financial Data Model Design Document

**Document Version:** 1.0.0  
**Status:** Approved for Implementation  
**Date:** 2026-10-27  
**Module Target:** `shared-contracts`  
**Authors:** Principal Fintech Domain Architect, Java 21 Engineer  

---

## 1. Executive Summary & Context

The QuantArth Financial Intelligence Platform (FIP) has successfully completed:
- **Milestone 1 (Foundation MVP):** Established multi-module Gradle layout, Hexagonal Architecture, basic event ingestion, and RabbitMQ topic exchange topology.
- **Milestone 2A (Security Hardening):** Implemented request-bound HMAC-SHA256 service-to-service authentication, strict tenant context validation, and sliding-window replay protection.
- **Milestone 2B (Reliability Hardening & CI):** Hardened the Transactional Outbox pattern with correlated publisher confirms, distributed database worker leasing, dead-letter exchange (DLX) routing, durable consumer idempotency, and full GitHub Actions CI matrix with Testcontainers.

**Milestone 3A** introduces the **Canonical Financial Data Model**. The canonical model serves as the single source of truth (SSOT) for all financial representations across FIP microservices (`event-engine`, `plugin-framework`, `auth-service`, `api-gateway`, and external integrations). It provides strongly-typed, immutable, validated domain value objects and entities representing payments, transfers, payroll, refunds, lending, repayments, invoicing, recurring subscriptions, and tax obligations.

---

## 2. Architecture Inspection & Reusability Assessment

Before designing new abstractions, an exhaustive inspection of the existing codebase was conducted:

| Existing Component | Current Role | Milestone 3A Reusability & Strategy |
| :--- | :--- | :--- |
| `shared-contracts` | Common library providing `CanonicalEvent`, `EventTopics`, `TenantDto`, `RequestHmacSigner`. | **Primary Target**. All canonical domain types, value objects, validation logic, and enhanced canonical event envelopes will reside here. |
| `common-library` | Enterprise cross-cutting utilities (`TenantContext`, `StandardResponse`, `AuditModel`, `GlobalExceptionHandler`). | **Reused without alteration**. Multi-tenant isolation and standard API envelopes remain untouched. |
| `event-engine` | Event persistence, transactional outbox (`event_outbox`), AMQP listeners, and REST ingestion. | **Preserved**. `FinancialEvent` domain model and `FinancialEventJpaEntity` continue to store canonical event payloads as verified JSON strings. |
| `CanonicalEvent` | Current record: `(eventId, eventType, eventSource, timestamp, payload, version, traceId, tenantId, metadata)`. | **Extended compatibly**. Preserves all 9 legacy constructor parameters, accessor methods, and factory methods while adding first-class canonical metadata fields (`entityType`, `entityId`, `recordedAt`, `causationId`, `idempotencyKey`). |
| JSON Serialization | Jackson 2.17.1 with `JavaTimeModule` and ISO-8601 UTC timestamps. | **Maintained**. Ensure `BigDecimal` financial precision is preserved (never converted to floating-point) and date-time instances are normalized to UTC. |
| Flyway Migrations | `V1__init_events.sql`, `V2__create_event_outbox.sql`, `V3__enhance_event_outbox.sql`. | **Zero database schema changes in M3A**. Payloads remain serialized in `financial_events.payload` and `event_outbox.payload` columns. |
| Tenant Isolation | `TenantContext` ThreadLocal populated at gateway and verified via HMAC signatures. | **Preserved**. Event envelopes enforce immutable tenant boundaries; tenant identifiers are never accepted blindly from untrusted external sources. |

---

## 3. Proposed Package Structure

All canonical financial types reside in `shared-contracts` under package `com.fip.shared.model.financial`:

```
shared-contracts/src/main/java/com/fip/shared/
├── events/
│   ├── CanonicalEvent.java               # Enhanced backwards-compatible canonical event record
│   ├── CanonicalFinancialEvent.java      # Generic strongly-typed financial event envelope
│   ├── EntityType.java                   # Enum defining canonical financial entity categories
│   └── EventTopics.java                  # Existing RabbitMQ topic & exchange constants
├── model/
│   └── financial/
│       ├── primitives/
│       │   ├── Money.java                # Immutable BigDecimal + ISO 4217 Currency value object
│       │   ├── CurrencyMismatchException.java # Exception thrown on cross-currency arithmetic
│       │   ├── FinancialParty.java       # Participant value object (Payer, Payee, Employer, etc.)
│       │   ├── PartyType.java            # Enum (INDIVIDUAL, ORGANIZATION, MERCHANT, etc.)
│       │   ├── AccountReference.java     # Account reference value object (masked, type, IFSC/routing)
│       │   ├── AccountType.java          # Enum (SAVINGS, CURRENT, CREDIT_CARD, LOAN, etc.)
│       │   ├── FinancialReference.java   # External tracking identifier (UPI RRN, UTR, Invoice No)
│       │   ├── FinancialReferenceType.java # Enum of reference categories
│       │   └── FinancialTime.java        # Temporal tracking (occurredAt, valueDate, processedAt, recordedAt)
│       ├── domain/
│       │   ├── Payment.java              # Canonical Payment domain model & state transitions
│       │   ├── PaymentStatus.java        # State machine enum (INITIATED, PENDING, COMPLETED, FAILED, CANCELLED)
│       │   ├── PaymentMethod.java        # Enum (UPI, BANK_TRANSFER, CARD, NET_BANKING, etc.)
│       │   ├── Transfer.java             # Movement of funds between accounts
│       │   ├── TransferStatus.java       # Enum (INITIATED, PENDING, COMPLETED, FAILED, CANCELLED, REVERSED)
│       │   ├── Salary.java               # Payroll credit model (gross, net, deductions, pay period)
│       │   ├── PayPeriod.java            # Pay period date interval value object
│       │   ├── SalaryDeduction.java      # Itemized deduction record
│       │   ├── Refund.java               # Refund associated with prior payment (partial refund support)
│       │   ├── RefundStatus.java         # Enum (INITIATED, PENDING, COMPLETED, FAILED, REJECTED)
│       │   ├── Loan.java                 # Loan account / facility agreement model
│       │   ├── LoanStatus.java           # Enum (APPLIED, APPROVED, DISBURSED, ACTIVE, DELINQUENT, etc.)
│       │   ├── InterestTerms.java        # Fixed/Floating interest terms value object
│       │   ├── LoanRepayment.java        # Loan installment / repayment model (principal, interest, fees)
│       │   ├── RepaymentStatus.java      # Enum (SCHEDULED, PENDING, COMPLETED, FAILED, OVERDUE, etc.)
│       │   ├── Invoice.java              # Commercial invoice model (subtotal, tax, total, paid amount)
│       │   ├── InvoicePaymentStatus.java # Enum (ISSUED, PARTIALLY_PAID, PAID, OVERDUE, VOID, DISPUTED)
│       │   ├── Subscription.java         # Recurring subscription model (billing frequency, status)
│       │   ├── SubscriptionStatus.java   # Enum (TRIALING, ACTIVE, PAUSED, CANCELLED, EXPIRED)
│       │   ├── BillingFrequency.java     # Enum (DAILY, WEEKLY, MONTHLY, QUARTERLY, ANNUALLY, etc.)
│       │   ├── Tax.java                  # Tax obligation model (GST, TDS, TCS, jurisdiction, period)
│       │   ├── TaxType.java              # Enum (GST, TDS, TCS, VAT, INCOME_TAX, etc.)
│       │   └── TaxPaymentStatus.java     # Enum (ASSESSED, DUE, PAID, REFUND_DUE, REFUNDED, EXEMPT)
│       └── validation/
│           ├── FinancialValidationException.java # Rich validation exception with validation errors
│           └── FinancialModelValidator.java      # Centralized validation rules & invariant checks
```

---

## 4. Core Financial Primitives

### 4.1 `Money`
- **Fields**: `BigDecimal amount`, `Currency currency`.
- **Invariants**:
  - `amount` must never be `null`.
  - `currency` must be a valid ISO 4217 currency (e.g., `INR`, `USD`, `EUR`, `GBP`).
  - Scale is preserved up to 6 decimal digits. Scale cannot exceed 6.
  - Never uses IEEE 754 floating-point numbers (`float` or `double`).
  - Rounding operations explicitly use Banker's Rounding (`RoundingMode.HALF_EVEN`).
  - Strict cross-currency checking: Operations like `add()` and `subtract()` between disparate currencies fail with `CurrencyMismatchException`.
  - Sign semantics: Supports positive, negative, and zero values. Financial entities define their own sign requirements (e.g. `Payment` requires positive amounts; `Tax` allows negative balances for tax credits/refunds).

### 4.2 `FinancialParty`
- **Fields**: `String partyId`, `PartyType partyType`, `String displayName`, `String maskedIdentifier`.
- **Invariants**:
  - `partyId` and `partyType` are mandatory.
  - Zero raw PII: Identity numbers (PAN, Aadhaar, Social Security, passport numbers) must be masked (e.g., `XXXXXX1234F`).

### 4.3 `AccountReference`
- **Fields**: `String accountId`, `AccountType accountType`, `String institutionId`, `String institutionName`, `String maskedAccountNumber`, `String routingCode` (e.g. IFSC/SWIFT/BIC), `String countryCode` (ISO 3166-1 alpha-2, default `"IN"`).
- **Invariants**:
  - Zero sensitive authentication data: Full 16-digit PANs, CVVs, and ATM PINs are strictly prohibited.
  - Only masked identifiers (e.g. `XXXXXX5678`) are retained for display and audit.

### 4.4 `FinancialReference`
- **Fields**: `FinancialReferenceType type`, `String namespace`, `String value`.
- **Invariants**:
  - Supports external tracking identifiers: UPI Reference Numbers (RRN), Bank Unique Transaction References (UTR), IMPS/NEFT/RTGS IDs, Invoice Numbers, Loan Account Numbers (LAN), Payment Gateway IDs (Razorpay/Stripe), Challan Identification Numbers (CIN), and GSTIN references.
  - All three components are strictly non-blank.

### 4.5 `FinancialTime`
- **Fields**: `Instant occurredAt`, `LocalDate valueDate`, `Instant processedAt`, `Instant recordedAt`.
- **Invariants**:
  - Instants are serialized as ISO-8601 UTC.
  - Preserves business `valueDate` for interest accrual, statement cutoff, and general ledger posting without timezone shifting.
  - Utility conversion methods allow rendering in Indian Standard Time (IST, `Asia/Kolkata`) or any customer timezone on demand.

---

## 5. Canonical Financial Domain Types

### 5.1 Payment
- Represents commercial payments, peer-to-peer transfers, and settlements.
- **State Machine**:
  - `INITIATED` → `PENDING`, `COMPLETED`, `FAILED`, `CANCELLED`
  - `PENDING` → `COMPLETED`, `FAILED`, `CANCELLED`
  - `COMPLETED`, `FAILED`, `CANCELLED` are terminal states.
- Invariants: Amount must be strictly positive (`> 0`).

### 5.2 Transfer
- Represents intra-institution or inter-account liquidity movements.
- Invariants: `sourceAccount` and `destinationAccount` must not be identical. Amount must be positive. Clearly distinguished from commercial payments.

### 5.3 Salary
- Represents payroll credits.
- Invariants: `employer` and `employee` are required. At least one of `grossAmount` or `netAmount` must be provided. Gross salary is **never** inferred from net salary. If both gross and net amounts are provided, `netAmount` cannot exceed `grossAmount`. Deductions sum cannot exceed gross salary.

### 5.4 Refund
- Represents full or partial reimbursement against a prior payment.
- Invariants: `originalPaymentReference` is mandatory. `refundAmount` must be strictly positive. If the original payment amount is known, `refundAmount` cannot exceed it. Partial refunds are first-class citizens.

### 5.5 Loan
- Represents a lending facility or debt agreement.
- Invariants: `principalAmount` must be positive. `outstandingBalance`, where known, cannot be negative and must not exceed principal + accrued interest. `maturityDate`, where known, must be on or after `disbursementDate`. Fixed or floating interest rates must be non-negative.

### 5.6 LoanRepayment
- Represents loan service payments (regular EMIs, prepayments, bullet payments, interest-only payments).
- Invariants: Does not assume repayments are standard EMIs. If itemized components (`principalComponent`, `interestComponent`, `feeComponent`) are present, they must share the repayment currency and their sum cannot exceed the total repayment `amount`.

### 5.7 Invoice
- Represents commercial billing documents.
- Invariants: `issueDate` must be on or before `dueDate`. All amounts must share the same currency. If `taxAmount` is present, `totalAmount` must equal `subtotal.add(taxAmount)`. Supports partial payments via `paidAmount` (`0 <= paidAmount <= totalAmount`).

### 5.8 Subscription
- Represents recurring automated billing agreements.
- Invariants: `recurringAmount` must be positive. `billingFrequency` must be specified. If `endDate` is present, it must be on or after `startDate`.

### 5.9 Tax
- Represents statutory tax liabilities, TDS/TCS withholdings, GST assessments, or tax refunds.
- Invariants: Accommodates India-specific concepts (GSTIN, Challan CIN, TAN, PAN, Financial Year) while maintaining international jurisdictional flexibility. Negative amounts are permitted to represent tax refunds or credit balances.

---

## 6. Backward Compatibility & Evolution Strategy

### 6.1 `CanonicalEvent` Evolution
The existing `CanonicalEvent` record is enhanced with aliases and secondary constructors:
1. **Source Compatibility**: Existing code calling `new CanonicalEvent(eventId, eventType, eventSource, timestamp, payload, version, traceId, tenantId, metadata)` continues to compile and execute without modifications.
2. **Binary Compatibility**: Legacy accessor methods `eventSource()`, `timestamp()`, `traceId()`, and `version()` are retained as alias accessors.
3. **JSON Wire Compatibility**: Jackson annotations `@JsonProperty` and `@JsonAlias` ensure that both legacy JSON payloads (`eventSource`, `timestamp`, `version`, `traceId`) and new canonical envelopes (`sourceSystem`, `occurredAt`, `schemaVersion`, `correlationId`) deserialize transparently.
4. **Unknown Field Tolerance**: `@JsonIgnoreProperties(ignoreUnknown = true)` guarantees forward compatibility when new optional fields are added in minor schema increments.

---

## 7. Next Steps

Upon architectural acceptance of this design:
1. Implement core primitives in `shared-contracts`.
2. Implement 9 canonical financial models.
3. Enhance `CanonicalEvent` and add typed `CanonicalFinancialEvent<T>`.
4. Implement validation engine in `FinancialModelValidator`.
5. Implement exhaustive test suite across all primitives, domain types, edge cases, and backward compatibility scenarios.
6. Generate verification reports and schema documentation.
