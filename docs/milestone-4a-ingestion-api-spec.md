# QuantArth Financial Intelligence Platform (FIP)
## Milestone 4A API Contracts: Financial Data Ingestion Specification (Refined)

### 1. Architectural Overview & Ingress Conventions

- **Base URL**: `https://api.quantarth.fip/api/v1/ingestion`
- **Gateway Routing**: Routed through Spring Cloud Gateway (`:8080`) to `ingestion-service` (`:8084`).
- **Security & Multi-Tenancy**:
  - Requires valid JWT bearer token in `Authorization: Bearer <token>`.
  - API Gateway validates JWT, strips client-supplied `X-Tenant-Id`, injects verified tenant context from JWT claims, and signs the request using HMAC-SHA256 (`X-Gateway-Key-Id`, `X-Gateway-Timestamp`, `X-Gateway-Nonce`, `X-Gateway-Signature`).
  - `ingestion-service` enforces authenticated tenant isolation; any cross-tenant request is rejected with RFC 7807 `403 Forbidden`.
- **Content Types**:
  - JSON payloads: `application/json`
  - File uploads: `multipart/form-data`
  - Problem Details: `application/problem+json`

---

### 2. Ingestion Endpoints

#### 2.1 Ingest JSON Financial Stream
Accepts an array of structured financial records from external systems (payment gateway webhooks, ERP synchronizations, loan management systems).

- **Method**: `POST`
- **Path**: `/api/v1/ingestion/json`
- **Query Parameters**:
  - `sync` *(boolean, optional, default: false)*: If `true` and payload has $\le 50$ records, executes synchronously and returns immediate outcome. If payload has $> 50$ records or `sync=false`, returns `202 Accepted` with asynchronous `jobId`.
- **Headers**:
  - `Idempotency-Key` *(string, optional)*: UUID for request-level deduplication (Tier 1). Caches and replays response within a 24-hour window.
  - `X-Source-System` *(string, required)*: Originating system identifier (e.g. `razorpay-webhook`, `tally-erp`, `zoho-books`).
- **Request Body**:
  ```json
  {
    "sourceSystem": "razorpay-webhook",
    "records": [
      {
        "sourceRecordId": "pay_O7s8K9L0M1N2P3",
        "entityType": "PAYMENT",
        "occurredAt": "2026-10-28T09:15:00Z",
        "amount": {
          "amount": "4500.00",
          "currency": "INR"
        },
        "payer": {
          "partyId": "CUST-9921",
          "name": "Rohan Verma",
          "type": "INDIVIDUAL"
        },
        "payee": {
          "partyId": "MERCH-8822",
          "name": "CloudMart Retail Pvt Ltd",
          "type": "CORPORATE"
        },
        "paymentMethod": "UPI",
        "status": "COMPLETED",
        "externalReference": "UPI-RRN-20261028123456",
        "metadata": {
          "vpa": "rohan@okhdfcbank",
          "terminalId": "T-102"
        }
      },
      {
        "sourceRecordId": "rfnd_P1Q2R3S4T5U6V7",
        "entityType": "REFUND",
        "occurredAt": "2026-10-28T09:30:00Z",
        "amount": {
          "amount": "1500.00",
          "currency": "INR"
        },
        "originalPaymentReference": {
          "type": "PAYMENT_GATEWAY",
          "namespace": "STRIPE",
          "value": "EXT-CHG-998811"
        },
        "originalPaymentAmount": {
          "amount": "4500.00",
          "currency": "INR"
        },
        "status": "INITIATED",
        "reason": "Customer cancellation within 2 hours"
      }
    ]
  }
  ```
- **Responses**:
  - **`202 Accepted` (Asynchronous Mode)**:
    ```json
    {
      "jobId": "f7b1c2d3-e4f5-4a6b-8c9d-0e1f2a3b4c5d",
      "status": "RECEIVED",
      "sourceType": "JSON",
      "sourceSystem": "razorpay-webhook",
      "totalRecords": 2,
      "receivedAt": "2026-10-28T09:31:00Z",
      "links": {
        "self": "/api/v1/ingestion/jobs/f7b1c2d3-e4f5-4a6b-8c9d-0e1f2a3b4c5d",
        "records": "/api/v1/ingestion/jobs/f7b1c2d3-e4f5-4a6b-8c9d-0e1f2a3b4c5d/records"
      }
    }
    ```
  - **`200 OK` (Synchronous Mode)**:
    ```json
    {
      "jobId": "f7b1c2d3-e4f5-4a6b-8c9d-0e1f2a3b4c5d",
      "status": "COMPLETED",
      "totalRecords": 2,
      "acceptedRecords": 1,
      "unverifiedRecords": 1,
      "rejectedRecords": 0,
      "duplicateRecords": 0,
      "completedAt": "2026-10-28T09:31:01Z"
    }
    ```

---

#### 2.2 Ingest CSV File Upload
Accepts an RFC 4180 CSV export (bank statement, gateway settlement report, invoice ledger) with a declared mapping profile. Files are streamed directly to bounded local staging (`/var/data/ingestion/staging`) for resumable background processing.

- **Method**: `POST`
- **Path**: `/api/v1/ingestion/csv`
- **Content-Type**: `multipart/form-data`
- **Form Parameters**:
  - `file` *(binary file, required)*: CSV file (maximum 25 MB).
  - `mappingProfileId` *(string, required)*: UUID or slug of tenant mapping profile (e.g. `prof-hdfc-stmt-v1`).
  - `sourceSystem` *(string, required)*: External system namespace (e.g. `hdfc-corporate-banking`).
  - `force` *(boolean, optional, default: false)*: If `true`, bypasses advisory file-hash duplicate checks, allowing intentional re-processing of identical file contents (e.g., after updating mapping rules).
- **Headers**:
  - `Idempotency-Key` *(string, optional)*: Request-level idempotency header.
- **Responses**:
  - **`202 Accepted`**:
    ```json
    {
      "jobId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5e",
      "status": "RECEIVED",
      "sourceType": "CSV",
      "sourceName": "HDFC_Statement_Oct2026.csv",
      "mappingProfileId": "prof-hdfc-stmt-v1",
      "fileHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "receivedAt": "2026-10-28T09:35:00Z",
      "links": {
        "self": "/api/v1/ingestion/jobs/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5e",
        "records": "/api/v1/ingestion/jobs/a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5e/records"
      }
    }
    ```
  - **`409 Conflict` (Duplicate File Upload Detected)**:
    Returned when an identical file hash has already been successfully `COMPLETED` under the same mapping profile and `force=false`.
    ```json
    {
      "type": "https://api.quantarth.fip/errors/duplicate-file",
      "title": "Duplicate File Upload",
      "status": 409,
      "detail": "An identical file with SHA-256 hash [e3b0c44...] was already completed in Job [job-old-991]. To reprocess, resubmit with query parameter force=true or select a different mapping version.",
      "instance": "/api/v1/ingestion/csv"
    }
    ```

---

### 3. Job Status & Audit Endpoints

#### 3.1 Get Ingestion Job Details
Polls the execution state, counters, and metrics of an ingestion job.

- **Method**: `GET`
- **Path**: `/api/v1/ingestion/jobs/{jobId}`
- **Responses**:
  - **`200 OK`**:
    ```json
    {
      "jobId": "a1b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5e",
      "tenantId": "tenant-corp-india",
      "status": "COMPLETED",
      "sourceType": "CSV",
      "sourceSystem": "hdfc-corporate-banking",
      "sourceName": "HDFC_Statement_Oct2026.csv",
      "fileHash": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
      "mappingProfileId": "prof-hdfc-stmt-v1",
      "mappingProfileVersion": 2,
      "totalRecords": 1000,
      "processedRecords": 1000,
      "acceptedRecords": 985,
      "unverifiedRecords": 10,
      "rejectedRecords": 3,
      "duplicateRecords": 2,
      "startedAt": "2026-10-28T09:35:05Z",
      "completedAt": "2026-10-28T09:35:25Z",
      "durationMs": 20000,
      "errorSummary": {
        "INVALID_CURRENCY": 2,
        "DATE_PARSE_FAILURE": 1
      }
    }
    ```

---

#### 3.2 Get Ingestion Job Itemized Records
Retrieves itemized audit records, quarantined errors, or unverified external refunds for a specific job with pagination.

- **Method**: `GET`
- **Path**: `/api/v1/ingestion/jobs/{jobId}/records`
- **Query Parameters**:
  - `status` *(string, optional)*: Filter by record status (`ACCEPTED`, `UNVERIFIED_EXTERNAL`, `REJECTED`, `DUPLICATE`).
  - `reviewStatus` *(string, optional)*: Filter by review state (`NONE`, `PENDING_RECONCILIATION`, `RECONCILED`, `REJECTED`).
  - `page` *(int, default: 0)*
  - `size` *(int, default: 50, max: 200)*
- **Responses**:
  - **`200 OK`**:
    ```json
    {
      "content": [
        {
          "recordId": "rec-001-uuid",
          "recordIndex": 42,
          "sourceRecordId": "TXN-998811",
          "entityType": "REFUND",
          "status": "UNVERIFIED_EXTERNAL",
          "reviewStatus": "PENDING_RECONCILIATION",
          "canonicalEventId": null,
          "externalPaymentReference": "EXT-CHG-998811",
          "refundAmount": {
            "amount": "1500.00",
            "currency": "INR"
          },
          "declaredPaymentCeiling": {
            "amount": "4500.00",
            "currency": "INR"
          },
          "reason": "ORIGINAL_PAYMENT_NOT_IN_LEDGER",
          "rawSnippet": {
            "txn_id": "TXN-998811",
            "ref_id": "EXT-CHG-998811",
            "amount": "1500.00",
            "type": "REFUND",
            "card_masked": "XXXXXX1234"
          },
          "createdAt": "2026-10-28T09:35:10Z"
        },
        {
          "recordId": "rec-002-uuid",
          "recordIndex": 88,
          "sourceRecordId": "TXN-ERR-99",
          "entityType": "PAYMENT",
          "status": "REJECTED",
          "reviewStatus": "NONE",
          "errorCode": "INVALID_CURRENCY",
          "errorMessage": "Declared currency [XYZ] is not a valid ISO 4217 currency code",
          "rawSnippet": {
            "row": 88,
            "raw": "TXN-ERR-99,2026-10-28,XYZ,-5000"
          },
          "createdAt": "2026-10-28T09:35:15Z"
        }
      ],
      "page": {
        "pageNumber": 0,
        "pageSize": 50,
        "totalElements": 2,
        "totalPages": 1
      }
    }
    ```

---

### 4. Reconciliation Endpoints for External Refunds

#### 4.1 Approve Unverified External Refund
Allows an authorized operator (`ROLE_FINANCE_ADMIN`) to review and reconcile an `UNVERIFIED_EXTERNAL` refund. Upon approval, the refund transitions to `RECONCILED_VERIFIED` and is securely forwarded to `event-engine` for canonical event persistence.

- **Method**: `POST`
- **Path**: `/api/v1/ingestion/reconciliation/refunds/{recordId}/approve`
- **Request Body**:
  ```json
  {
    "approvalReason": "HISTORICAL_EXTERNAL_PRE_QUANTARTH",
    "notes": "Verified against physical bank statement export for October 2026",
    "overrideCeilingValidation": false
  }
  ```
- **Responses**:
  - **`200 OK`**:
    ```json
    {
      "recordId": "rec-001-uuid",
      "previousStatus": "UNVERIFIED_EXTERNAL",
      "newStatus": "RECONCILED_VERIFIED",
      "canonicalEventId": "e9b2c3d4-e5f6-4a7b-8c9d-0e1f2a3b4c5f",
      "approvedBy": "admin@cloudmart.com",
      "approvedAt": "2026-10-28T10:15:00Z"
    }
    ```

---

### 5. Source Mapping Profile Management Endpoints

#### 5.1 Create Mapping Profile
- **Method**: `POST`
- **Path**: `/api/v1/ingestion/mappings`
- **Request Body**:
  ```json
  {
    "name": "ICICI Corporate Bank Statement",
    "sourceFormat": "CSV",
    "targetEntityType": "PAYMENT",
    "delimiter": ",",
    "hasHeader": true,
    "dateFormat": "dd-MM-yyyy",
    "timezone": "Asia/Kolkata",
    "currencyDefault": "INR",
    "mappingRules": {
      "entityId": { "sourceColumn": "Transaction ID" },
      "occurredAt": { "sourceColumn": "Value Date", "transform": "DATE_PARSE" },
      "amount": {
        "strategy": "SIGNED_AMOUNT",
        "amountColumn": "Transaction Amount(INR)",
        "typeColumn": "Cr/Dr",
        "creditIndicator": "CR",
        "debitIndicator": "DR"
      },
      "payerName": { "sourceColumn": "Description" },
      "status": { "staticValue": "COMPLETED" },
      "paymentMethod": { "staticValue": "BANK_TRANSFER" }
    }
  }
  ```
- **Responses**:
  - **`201 Created`**: Returns created profile with generated `profileId` and `version: 1`.

#### 5.2 Get Mapping Profile by ID
- **Method**: `GET`
- **Path**: `/api/v1/ingestion/mappings/{profileId}`
- **Responses**:
  - **`200 OK`**: Returns mapping profile JSON definition.
