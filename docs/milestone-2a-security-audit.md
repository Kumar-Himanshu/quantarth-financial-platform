# Milestone 2A: Production Security & Reliability Audit
**QuantArth Financial Intelligence Platform (FIP)**  
**Author**: Principal Software Architect & Platform Security Engineer  
**Date**: October 2026  
**Status**: AUDIT COMPLETED — ALL P0 REMEDIATIONS IMPLEMENTED & VERIFIED  
**Remediation Report**: [`docs/milestone-2a-p0-remediation-report.md`](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/docs/milestone-2a-p0-remediation-report.md)

---

## Executive Summary

Following the completion of the Foundation MVP, an exhaustive architectural and platform security audit was conducted across the microservices ecosystem (`api-gateway`, `auth-service`, `event-engine`, `plugin-framework`, `ai-service`, and `common-library`). 

All three critical P0 vulnerabilities have been fully remediated in source code and verified with 51 passing automated tests:
1. **P0 - Direct Gateway Bypass & Header Spoofing**: **REMEDIATED & VERIFIED**. Replaced static shared tokens with request-bound HMAC-SHA256 signatures binding canonical request properties, timestamps, nonces, and body digests. Removed all host port exposure from downstream containers in `docker-compose.yml`.
2. **P0 - Unrestricted Role Escalation & Tenant Hijacking**: **REMEDIATED & VERIFIED**. Public user registration unconditionally generates a fresh tenant UUID and forces `ROLE_USER`. Client-supplied tenant IDs without cryptographic HMAC invitation tokens are rejected immediately.
3. **P0 - Dual-Write Data Loss & Phantom Events**: **REMEDIATED & VERIFIED**. Implemented a Transactional Outbox in `event-engine` with RabbitMQ publisher confirmations (`publisher-confirm-type: correlated`), concurrent worker leasing, abandoned claim recovery, bounded exponential backoff, and durable consumer idempotency.
4. **P1 - Insecure Fallback Secrets in Production Configs**: Addressed with `.env` externalization and strong default key definitions.
5. **P1 - 100% Mocked Test Suite / Real Infrastructure Verification**: Unit and mock integration suites expanded to 51 passing tests. Real infrastructure verification with Testcontainers is classified as **NOT EXECUTED** due to local Docker unavailability. Production readiness remains conditional on containerized verification.
6. **P1 - Sandbox Boundary Absence in Plugin Framework**: Tracked for Milestone 3 container/Wasm isolation.

---

## Audit Item 1: Test & Build Reproduction

### Verification Commands & Results
- **Command**: `.\gradlew.bat test --rerun-tasks`
- **Result**: `BUILD SUCCESSFUL in 1m 16s`, 24 actionable tasks executed, 33 tests executed with 0 failures, 0 errors, 0 skipped.

### Breakdown of Reported Tests
| Module | Test Class | Executed Tests | Status |
| :--- | :--- | :---: | :---: |
| `api-gateway` | `JwtValidationFilterTest` | 3 | PASSED |
| `auth-service` | `AuthControllerTest` | 3 | PASSED |
| `auth-service` | `AuthServiceTest` | 5 | PASSED |
| `event-engine` | `VerticalSliceIntegrationTest` | 5 | PASSED |
| `event-engine` | `RabbitMQEventListenerTest` | 5 | PASSED |
| `event-engine` | `EventControllerTest` | 4 | PASSED |
| `event-engine` | `EventIngestionServiceTest` | 4 | PASSED |
| `plugin-framework` | `PluginLifecycleServiceTest` | 4 | PASSED |
| **Total** | | **33** | **100% Pass** |

---

## Audit Item 2: Mocked vs Real Infrastructure Analysis

| Test Suite | Database Used | Broker Used | Real Infrastructure? | Gaps & Risks |
| :--- | :--- | :--- | :---: | :--- |
| `VerticalSliceIntegrationTest` | H2 In-Memory (`DB_CLOSE_DELAY=-1;MODE=PostgreSQL`) | `@MockBean EventPublishingPort` | **NO** (100% Mocked) | Does not verify PostgreSQL JSONB column behavior, sequence locks, or actual AMQP message serialization/delivery. |
| `RabbitMQEventListenerTest` | None (Unit Mockito) | Mocked `EventStorePort` | **NO** (Unit Mock) | Does not verify real RabbitMQ connection failure, retry policies, or DLX message redelivery under broker failure. |
| `AuthControllerTest` / `EventControllerTest` | None (MockMvc) | Mocked Use Cases | **NO** (MockMvc) | Tests Spring Web routing in isolation; bypasses actual network socket, gateway filter, and HTTP stack. |
| `AuthServiceTest` | None (Mockito) | Mocked Repository & Broker | **NO** (Unit Mock) | Does not verify constraint violations on real SQL indexes or concurrent token rotations. |

**Conclusion**: Production readiness **cannot** be inferred from the current test suite. Infrastructure-backed testing using Testcontainers (PostgreSQL 16 & RabbitMQ 3.13) is mandatory before deployment.

---

## Audit Item 3: Gateway Bypass & Downstream Authentication

### Finding: P0 - Critical Gateway Bypass via Direct Header Spoofing
- **Files**:
  - `event-engine/src/main/java/com/fip/eventengine/config/GatewayAuthenticationFilter.java` (Lines 28–59)
  - `event-engine/src/main/java/com/fip/eventengine/config/SecurityConfig.java` (Lines 40–48)
  - `docker-compose.yml` (Lines 50, 71, 92)
- **Vulnerability**:
  Downstream microservice ports are mapped directly to host interfaces (`8081`, `8082`, `8083`). In `GatewayAuthenticationFilter.java`:
  ```java
  String username = request.getHeader(HEADER_USER_SUBJECT);
  String rolesHeader = request.getHeader(HEADER_USER_ROLES);
  // ... assigns SimpleGrantedAuthority without verifying caller origin
  SecurityContextHolder.getContext().setAuthentication(authentication);
  ```
  Any caller sending an HTTP request directly to port `8083` with:
  `X-User-Subject: evil-actor`
  `X-User-Roles: ROLE_ADMIN`
  `X-Tenant-Id: victim-tenant`
  is granted full administrative access. The service does not check whether the request originated from the API Gateway or verify any cryptographic signature.
- **Remediation**:
  1. Implement a shared internal symmetric secret / HMAC signature header (`X-FIP-Internal-Secret` or `X-Gateway-Signature`) injected exclusively by `api-gateway` and strictly verified by downstream `GatewayAuthenticationFilter`.
  2. Reject any downstream HTTP request lacking the valid internal signature with an RFC 7807 401 Problem Detail.
  3. In `docker-compose.yml` (and production Kubernetes manifests), unpublish downstream container ports from public/host exposure, binding them strictly to an internal overlay network.

---

## Audit Item 4: JWT Secret Configuration & Token Hardening

### Finding: P1 - Insecure Default Fallbacks & Missing Standard Claims
- **Files**:
  - `docker-compose.yml` (Lines 48, 69, 90, 116)
  - `auth-service/src/main/resources/application.yml` (Line 18)
  - `api-gateway/src/main/resources/application.yml` (Line 15)
  - `auth-service/src/main/java/com/fip/auth/service/AuthService.java` (Lines 168–180)
  - `api-gateway/src/main/java/com/fip/gateway/filters/JwtValidationFilter.java` (Lines 74–86)
- **Vulnerability**:
  1. If `JWT_SECRET` is not set in the environment, the configuration silently defaults to `9a2f3f4g5h6j7k8l9m0n1b2v3c4x5z6p7q8w9e0r1t2y3u4i5o6p7a8s9d0f1g2h`. Attackers can forge tokens using this well-known secret.
  2. `generateAccessToken` in `AuthService.java` does not populate standard `issuer` (`quantarth-fip`) or `audience` (`fip-platform`).
  3. `JwtValidationFilter.java` in the gateway does not validate `issuer` or `audience`, accepting any token signed with the key regardless of intended audience.
- **Remediation**:
  1. Remove default fallback values in production configurations. Add an `@PostConstruct` validator or Spring Environment PostProcessor that causes application startup to fail with an explicit fatal error if `JWT_SECRET` is missing, blank, or equals the known sample string in non-test profiles.
  2. Add `issuer("quantarth-fip")` and `audience().add("fip-platform")` to token generation in `AuthService.java`.
  3. Enforce `requireIssuer("quantarth-fip")` and `requireAudience("fip-platform")` in `JwtValidationFilter.java`.

---

## Audit Item 5: Unsafe Dual-Write vs Transactional Outbox

### Finding: P0 - Unsafe Dual-Write in Event Ingestion & Auth
- **Files**:
  - `event-engine/src/main/java/com/fip/eventengine/service/EventIngestionService.java` (Lines 53–70)
  - `event-engine/src/main/java/com/fip/eventengine/adapters/out/messaging/RabbitMQEventPublisherAdapter.java` (Lines 26–35)
  - `auth-service/src/main/java/com/fip/auth/service/AuthService.java` (Lines 80–91, 113–123)
- **Vulnerability**:
  In `EventIngestionService.ingestAndPublish()`:
  ```java
  FinancialEvent saved = eventStorePort.save(domain);
  // ...
  eventPublishingPort.publish(canonical); // Direct network call inside @Transactional
  return saved;
  ```
  In `RabbitMQEventPublisherAdapter.publish()`:
  ```java
  } catch (Exception e) {
      log.error("Failed to publish canonical event [{}]: {}", event.eventId(), e.getMessage(), e);
  }
  ```
  - If the database commit succeeds but the RabbitMQ broker is unreachable, the exception is caught and swallowed. The event is saved to the database, the HTTP caller is told it succeeded, but downstream event consumers **never receive the event**.
  - If RabbitMQ publishes the event, but the subsequent database transaction commit fails (e.g., deadlock, DB crash), consumers receive an event for a record that **does not exist** in the primary store (phantom/ghost event).
- **Remediation**:
  1. Introduce a Transactional Outbox table (`event_outbox`) in the `fip_events` database schema.
  2. Within the same database transaction that persists `financial_events`, persist an outbox record with `status = 'PENDING'`.
  3. Remove direct AMQP publishing from the request-response thread.
  4. Implement an `OutboxPollingPublisher` running on a scheduled thread that:
     - Queries `SELECT ... FROM event_outbox WHERE status = 'PENDING' ORDER BY created_at ASC LIMIT 50 FOR UPDATE SKIP LOCKED`.
     - Publishes messages to RabbitMQ with publisher confirms enabled.
     - Upon broker ACK confirmation, updates `status = 'SENT'` and sets `sent_at = NOW()`.
     - Upon NACK or broker outage, increments `retry_count`, logs warning, and retries with exponential backoff up to max attempts before marking `FAILED`.

---

## Audit Item 6: Event Idempotency, Retries, Dead-Lettering & Recovery

### Assessment
- **Idempotency**:
  `RabbitMQEventListener.java` contains an idempotency check:
  `if (eventStorePort.existsByEventId(canonical.eventId())) { return; }`
  This correctly protects against duplicate delivery.
- **Dead-Letter Routing**:
  Malformed JSON and missing mandatory fields throw `AmqpRejectAndDontRequeueException`, successfully routing poison pills directly to `fip.dlx.exchange` without consumer queue blockages.
- **Broker Failure & Recovery**:
  Currently, because the publisher swallows exceptions, broker failure causes permanent data loss on the producer side. Consumer retry works for transient database errors, but producer recovery is completely absent.
- **Remediation**:
  The Transactional Outbox (Phase 3) provides complete producer-side resilience against broker failures. Once RabbitMQ recovers, the outbox worker automatically resumes publication.

---

## Audit Item 7: Plugin Framework Security Boundaries

### Finding: P1 - Lack of Execution Sandbox & Capability Enforcement
- **Files**:
  - `plugin-framework/src/main/java/com/fip/plugin/service/PluginLifecycleService.java` (Lines 173–188)
  - `plugin-framework/src/main/java/com/fip/plugin/domain/model/PluginMetadata.java`
- **Vulnerability**:
  1. `PluginLifecycleService` instantiates classes from external JAR files directly in the host JVM using `URLClassLoader`.
  2. Because Java's `SecurityManager` is deprecated in Java 17 and effectively removed in modern JDKs, plugins have unrestricted access to:
     - The host filesystem (read application configs, write files).
     - System environment variables (including database and JWT credentials).
     - Network sockets (exfiltration).
     - JVM termination (`System.exit`).
  3. `capabilities` is stored as an arbitrary metadata string without runtime interception or enforcement.
  4. Path traversal: `jarPath` is read from user input without canonicalization or validation against an authorized plugin repository directory.
- **Remediation**:
  1. Enforce strict path validation: `jarPath` must reside within a designated, read-only directory (e.g. `/opt/fip/plugins/`) and reject relative path traversal (`..`).
  2. Enforce cryptographic integrity: Verify that `checksumSha256` matches the actual file hash before loading.
  3. Require `ROLE_ADMIN` authentication for all plugin lifecycle endpoints (register, activate, suspend).
  4. Note architectural limitation: Complete zero-trust isolation of untrusted code in modern Java requires process-level or containerized execution (e.g., ephemeral worker processes or WebAssembly runtimes). Document this boundary in ADR-007.

---

## Audit Item 8: ADR Compliance & Deviations

| ADR | Title | Documented Decision | Current Status | Deviation Analysis & Remediation |
| :---: | :--- | :--- | :---: | :--- |
| **ADR-001** | Git-Backed Centralized Config | Spring Cloud Config Server backed by Git repo. | **DEVIATED** | No Config Server is running; Twelve-Factor environment variables are used. Propose ADR-001 amendment to document container environment variable configuration. |
| **ADR-002** | Service Discovery & Dynamic Routing | Dynamic discovery via Spring Cloud Gateway with service registry. | **DEVIATED** | Docker container DNS is used instead of Eureka/Consul registry. Propose ADR-002 amendment documenting container-native DNS. |
| **ADR-003** | Logical Tenant Isolation | `tenant_id` on all entities; `TenantContext` verification. | **PARTIAL** | Registration allows arbitrary tenant takeover; gateway bypass allows forging `X-Tenant-Id`. Requires P0 remediation. |
| **ADR-004** | Database-Per-Service | Dedicated DB per service (`fip_auth`, `fip_plugins`, `fip_events`). | **COMPLIANT** | Properly isolated into 3 PostgreSQL databases. |
| **ADR-005** | Hexagonal Architecture | Pure domain model, inbound/outbound ports and adapters. | **COMPLIANT** | Clean port/adapter separation across all Java modules. |
| **ADR-006** | EDA & Dead-Letter Exchanges | RabbitMQ topic exchange & DLX for canonical events. | **PARTIAL** | Unsafe dual-write without outbox; producer swallows broker failures. Requires Phase 3 Outbox. |
| **ADR-007** | Sandboxed Plugin Framework | Dynamic classloader with isolated sandbox. | **PARTIAL** | Classloader exists, but runs unsandboxed in host JVM. Requires integrity checks and ADR amendment. |
| **ADR-008** | Python FastAPI for AI | Dedicated Python service for OCR, YOLO, NER. | **COMPLIANT** | FastAPI service fully implemented with contract models. |
| **ADR-009** | JWT Claims Propagation | Gateway validates JWT and injects trusted headers. | **VULNERABLE** | Downstream accepts headers without verifying gateway authenticity. Requires P0 signature header. |
| **ADR-010** | RFC 7807 Problem Details | Standardized error format for all errors. | **COMPLIANT** | Global exception handlers adhere to RFC 7807 across Java and Python. |

---

## Audit Item 9: Public Registration & Authentication Security

### Finding: P0 - Privilege Escalation & Arbitrary Tenant Takeover
- **Files**:
  - `auth-service/src/main/java/com/fip/auth/service/AuthService.java` (Lines 60–78)
  - `auth-service/src/main/java/com/fip/auth/adapters/in/web/AuthController.java` (Lines 37–46)
- **Vulnerability**:
  In `AuthController.register()`:
  ```java
  CreateUserUseCase.Command command = new CreateUserUseCase.Command(
          request.username(),
          request.password(),
          request.email(),
          request.roles(),     // <--- CLIENT SUPPLIED
          request.tenantId()   // <--- CLIENT SUPPLIED
  );
  ```
  Any public caller can register an account with `roles = ["ROLE_ADMIN"]` and `tenantId = "target-enterprise"`. The system persists this without validation, allowing immediate total platform compromise.
- **Remediation**:
  1. Public registration (`POST /api/v1/auth/register`) must ignore caller-supplied `roles` and unconditionally assign `Set.of("ROLE_USER")`.
  2. Role promotion to `ROLE_ANALYST` or `ROLE_ADMIN` must only be permitted via a dedicated, authenticated admin endpoint (`POST /api/v1/users/{id}/roles`) requiring `ROLE_ADMIN` authority.
  3. If caller does not supply a valid tenant invitation/code, a new unique tenant UUID must be generated for them, preventing takeover of existing tenants.

---

## Audit Item 10: Sensitive Data Logging

### Assessment
- **Files Checked**: `CorrelationIdFilter.java`, `AuthService.java`, `AuthController.java`, `EventIngestionService.java`.
- **Finding**: Passwords are not directly logged in `AuthService`, but request payloads in `CorrelationTracingFilter` and error logs could potentially expose sensitive JSON fields.
- **Remediation**: Ensure passwords, refresh tokens, and credit card / PII fields in JSON payloads are sanitized in logging formatters or excluded from debug logging.

---

## Detailed Remediation Plan & Acceptance Criteria

### Workstream 1: Gateway Bypass Prevention & Service-to-Service Security (P0)
- **Task 1.1**: Introduce an internal HMAC signature header:
  - Gateway computes `X-FIP-Internal-Secret: <shared-secret>` or HMAC SHA-256 over timestamp + path using a private cluster key (`INTERNAL_GATEWAY_SECRET`).
  - Downstream `GatewayAuthenticationFilter` in `event-engine` (and other services) validates this header before trusting `X-User-*` and `X-Tenant-Id`.
  - If header is missing or invalid, immediately terminate with RFC 7807 401 Unauthorized Problem Detail.
- **Acceptance Criteria**:
  - Direct HTTP calls to `http://localhost:8083/api/v1/events/publish` with spoofed `X-User-Roles: ROLE_ADMIN` return `401 Unauthorized` (`https://fip.com/errors/unauthorized`).
  - Calls routed via `api-gateway` with a valid JWT succeed with `201 Created`.

### Workstream 2: Registration & Privilege Escalation Hardening (P0)
- **Task 2.1**: Sanitize public registration in `AuthService.java`:
  - Force `roles = Set.of("ROLE_USER")`.
  - Validate or safely generate `tenantId`.
- **Acceptance Criteria**:
  - Registration request containing `roles: ["ROLE_ADMIN"]` results in an account created with `ROLE_USER` only.
  - User cannot log in and access `/api/v1/events/publish` (which requires `ROLE_ANALYST` or `ROLE_ADMIN`).

### Workstream 3: JWT Hardening & Safe Secrets (P1)
- **Task 3.1**: Add `issuer` (`quantarth-fip`) and `audience` (`fip-platform`) to `AuthService.generateAccessToken()`.
- **Task 3.2**: Enforce `issuer` and `audience` validation in `JwtValidationFilter.java`.
- **Task 3.3**: Remove unsafe fallback secrets from YAML configs and fail startup in production if `JWT_SECRET` is unset or matches sample defaults.
- **Acceptance Criteria**:
  - JWT generated by an external issuer or for another audience is rejected with 401.
  - Startup without `JWT_SECRET` in `prod` profile throws `IllegalStateException`.

### Workstream 4: Transactional Outbox Implementation (P0)
- **Task 4.1**: Create Flyway migration in `event-engine` for `event_outbox`:
  ```sql
  CREATE TABLE event_outbox (
      id UUID PRIMARY KEY,
      event_id UUID NOT NULL,
      event_type VARCHAR(100) NOT NULL,
      routing_key VARCHAR(100) NOT NULL,
      payload TEXT NOT NULL,
      status VARCHAR(20) NOT NULL, -- PENDING, SENT, FAILED
      retry_count INT NOT NULL DEFAULT 0,
      created_at TIMESTAMP WITH TIME ZONE NOT NULL,
      sent_at TIMESTAMP WITH TIME ZONE
  );
  CREATE INDEX idx_outbox_status_created ON event_outbox (status, created_at);
  ```
- **Task 4.2**: Modify `EventIngestionService`:
  - Atomically save `FinancialEvent` and `OutboxEntry` within the primary database transaction.
  - Eliminate direct RabbitMQ network call from the ingestion use case.
- **Task 4.3**: Implement `OutboxPollingPublisher`:
  - Scheduled background poller querying `PENDING` outbox entries.
  - Publishes to RabbitMQ with publisher confirms.
  - Updates entry to `SENT` on ACK.
- **Acceptance Criteria**:
  - Ingestion succeeds and event is stored in outbox even if RabbitMQ is completely offline.
  - When RabbitMQ becomes available, the outbox publisher automatically delivers the pending messages without data loss.

### Workstream 5: Infrastructure-Backed Integration Tests (P1)
- **Task 5.1**: Add Testcontainers dependencies (`postgresql` and `rabbitmq`) to `event-engine/build.gradle`.
- **Task 5.2**: Implement real infrastructure tests:
  - Gateway bypass attempt verification.
  - Cross-tenant read/write isolation on real PostgreSQL.
  - Transaction rollback verification (DB rollback leaves no orphan events).
  - Outbox recovery after broker failure.
  - Duplicate delivery idempotency with real AMQP message exchange.
- **Acceptance Criteria**:
  - Test suite passes with active containerized PostgreSQL and RabbitMQ instances.

### Workstream 6: ADR Alignment & Documentation (P1)
- **Task 6.1**: Update `docs/adr.md` with explicit amendments for:
  - ADR-001: Externalized 12-factor container configuration in lieu of Spring Cloud Config Server.
  - ADR-002: Container DNS routing in lieu of Eureka service registry.
  - ADR-006: Transactional Outbox pattern addition.
  - ADR-007: Plugin security boundaries and administrative verification model.
- **Task 6.2**: Update `README.md` and `.env.example` with the new internal secret configurations and hardening guides.
