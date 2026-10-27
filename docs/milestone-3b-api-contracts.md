# QuantArth Financial Intelligence Platform (FIP)
## Milestone 3B API Contracts: Canonical Financial Events

### 1. Ingestion Endpoints

#### 1.1 Ingest Canonical Financial Event
- **Method**: `POST`
- **Path**: `/api/v1/events/canonical`
- **Security**: Requires HMAC request verification headers (`X-Signature`, `X-Timestamp`, `X-Nonce`, `X-Key-Id`) or internal service gateway headers (`X-Internal-Token`, `X-Tenant-Id`, `X-User-Roles`).
- **Headers**:
  - `Content-Type`: `application/json`
  - `X-Tenant-Id`: Authenticated tenant identifier (e.g. `tenant-enterprise-1`)
  - `Idempotency-Key` *(optional)*: Unique request key for deduplication
- **Request Body**:
  ```json
  {
    "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
    "eventType": "payment.initiated",
    "entityType": "PAYMENT",
    "entityId": "PAY-2026-00123",
    "schemaVersion": "1.0.0",
    "sourceSystem": "core-banking",
    "tenantId": "tenant-enterprise-1",
    "occurredAt": "2026-10-27T10:15:30Z",
    "recordedAt": "2026-10-27T10:15:31Z",
    "correlationId": "corr-889900",
    "causationId": null,
    "idempotencyKey": "idem-pay-00123",
    "payload": {
      "paymentId": "PAY-2026-00123",
      "amount": {
        "amount": "150000.00",
        "currency": "INR"
      },
      "payer": {
        "partyId": "USER-101",
        "name": "Arjun Sharma",
        "type": "INDIVIDUAL"
      },
      "payee": {
        "partyId": "MERCH-505",
        "name": "Tata Power",
        "type": "CORPORATE"
      },
      "paymentMethod": "UPI",
      "status": "INITIATED",
      "externalReference": "UPI-REF-998877665544",
      "initiatedAt": "2026-10-27T10:15:30Z"
    }
  }
  ```
- **Responses**:
  - **`201 Created`**:
    ```json
    {
      "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
      "eventType": "payment.initiated",
      "entityType": "PAYMENT",
      "entityId": "PAY-2026-00123",
      "schemaVersion": "1.0.0",
      "sourceSystem": "core-banking",
      "tenantId": "tenant-enterprise-1",
      "occurredAt": "2026-10-27T10:15:30Z",
      "recordedAt": "2026-10-27T10:15:31Z",
      "correlationId": "corr-889900",
      "causationId": null,
      "idempotencyKey": "idem-pay-00123",
      "payload": { ... }
    }
    ```
  - **`400 Bad Request`** (Financial Domain Validation Failure - RFC 7807):
    ```json
    {
      "type": "https://api.quantarth.fip/errors/financial-validation-error",
      "title": "Financial Validation Error",
      "status": 400,
      "detail": "Individual refund amount [INR 20000.00] exceeds captured payment amount [INR 15000.00]",
      "instance": "/api/v1/events/canonical"
    }
    ```
  - **`403 Forbidden`** (Cross-Tenant Tampering):
    ```json
    {
      "type": "https://api.quantarth.fip/errors/forbidden",
      "title": "Forbidden",
      "status": 403,
      "detail": "Forbidden: Provided tenantId does not match authenticated TenantContext",
      "instance": "/api/v1/events/canonical"
    }
    ```
  - **`409 Conflict`** (Idempotency Key Reuse with Altered Payload):
    ```json
    {
      "type": "https://api.quantarth.fip/errors/conflict",
      "title": "Conflict",
      "status": 409,
      "detail": "Idempotency key [idem-pay-00123] was already used with a different request payload",
      "instance": "/api/v1/events/canonical"
    }
    ```

---

#### 1.2 Ingest Canonical Financial Events Batch
- **Method**: `POST`
- **Path**: `/api/v1/events/canonical/batch`
- **Request Body**: Array of `CanonicalEvent` objects (maximum 100 per request).
- **Responses**:
  - `201 Created`: Array of persisted `CanonicalEvent` objects.
  - `400 Bad Request`: Validation failure on any event in the batch.

---

### 2. Query & Search Endpoints

#### 2.1 Search Financial Events (Paginated & Filtered)
- **Method**: `GET`
- **Path**: `/api/v1/events`
- **Query Parameters**:
  - `entityType` *(optional, string)*: Filter by entity type (e.g. `PAYMENT`, `REFUND`, `INVOICE`)
  - `entityId` *(optional, string)*: Filter by specific business entity ID
  - `from` *(optional, ISO-8601)*: Start timestamp (`occurred_at >= from`)
  - `to` *(optional, ISO-8601)*: End timestamp (`occurred_at <= to`)
  - `page` *(optional, integer, default: 0)*: Zero-based page index
  - `size` *(optional, integer, default: 20, max: 100)*: Bounded page size
- **Responses**:
  - **`200 OK`**:
    ```json
    {
      "content": [
        {
          "eventId": "3fa85f64-5717-4562-b3fc-2c963f66afa6",
          "eventType": "payment.initiated",
          "entityType": "PAYMENT",
          "entityId": "PAY-2026-00123",
          "schemaVersion": "1.0.0",
          "sourceSystem": "core-banking",
          "tenantId": "tenant-enterprise-1",
          "occurredAt": "2026-10-27T10:15:30Z",
          "recordedAt": "2026-10-27T10:15:31Z",
          "correlationId": "corr-889900",
          "causationId": null,
          "idempotencyKey": "idem-pay-00123",
          "payload": { ... }
        }
      ],
      "page": 0,
      "size": 20,
      "totalElements": 1,
      "totalPages": 1,
      "first": true,
      "last": true
    }
    ```

#### 2.2 Get Event by ID
- **Method**: `GET`
- **Path**: `/api/v1/events/{eventId}`
- **Responses**:
  - `200 OK`: `CanonicalEvent` representation.
  - `404 Not Found`: Event does not exist or belongs to another tenant.
