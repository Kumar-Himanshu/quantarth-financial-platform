# QuantArth FIP — Milestone 2B: CI/CD & Real Infrastructure Verification Plan

**Platform**: QuantArth Financial Intelligence Platform (FIP)  
**Role**: Principal DevOps Engineer, Java Platform Architect, Distributed Systems Reliability Engineer  
**Date**: October 2026  
**Status**: APPROVED DESIGN & IMPLEMENTATION PLAN  

---

## 1. Repository Inspection & Architecture Inventory

A comprehensive pre-implementation inspection of the repository was conducted across build systems, language runtimes, test frameworks, database configurations, and messaging infrastructure.

### 1.1 Multi-Module Gradle Configuration
- **Root Project**: `financial-platform`
- **Gradle Version**: 9.2.0 wrapper (`gradlew`, `gradlew.bat`)
- **Modules (7 Total)**:
  1. `common-library`: Shared domain models (`AuditModel`), exception hierarchy, RFC 7807 handler, `TenantContext`, and correlation tracing.
  2. `shared-contracts`: Canonical events (`CanonicalEvent`), `EventTopics`, SDK interfaces, and `RequestHmacSigner`.
  3. `api-gateway`: Spring Cloud Gateway (reactive WebFlux) with request-bound HMAC-SHA256 signing, rate limiting, and JWT claims extraction.
  4. `auth-service`: Authentication, user management, BCrypt hashing, JWT generation/rotation, and cryptographic invitation token workflows.
  5. `plugin-framework`: Dynamic classloader plugin lifecycle manager and sandboxed execution engine.
  6. `event-engine`: High-throughput financial event store and high-reliability Transactional Outbox with RabbitMQ publisher confirms.
  7. `ai-service`: Python FastAPI microservice for document intelligence, OCR, YOLO layout analysis, NER, and merchant categorization.

### 1.2 Java & Python Runtime Requirements
- **Java**: Java 21 LTS (`sourceCompatibility = JavaVersion.VERSION_21`, `targetCompatibility = JavaVersion.VERSION_21`) across all Gradle subprojects. Compiler configured with `-parameters` for Spring MVC/WebFlux reflection.
- **Python**: Python 3.11 for `ai-service` utilizing FastAPI 0.111.0, Pydantic v2, and Pytest.

### 1.3 Test Frameworks & Dependencies
- **Java**: JUnit Jupiter 5.10.2, Mockito 5.14.2, AssertJ 3.25.3, Spring Boot Test 3.3.1, Spring Security Test, Project Reactor Test.
- **Existing Test Counts**: 51 automated Java tests passing against in-memory H2 / Mockito mocks.
- **Python**: Pytest 8.2.2, Pytest-Asyncio 0.23.7, HTTPX 0.27.0 TestClient. 9 test functions in `ai-service/tests/test_ai_endpoints.py`.
- **Testcontainers Status**: **NOT PREVIOUSLY CONFIGURED**. No Testcontainers dependencies or containerized integration tests were present.

### 1.4 Flyway Database Migrations
- Flyway 10.x enabled in `auth-service`, `plugin-framework`, and `event-engine`.
- `event-engine` migrations:
  - `V1__init_events.sql`: Creates `financial_events` table with unique constraint `uq_events_event_id` and tenant partitioning indexes.
  - `V2__create_event_outbox.sql`: Creates `event_outbox` table.
  - `V3__enhance_event_outbox.sql`: Adds `locked_by`, `locked_at`, and `next_attempt_at` columns for concurrent worker leasing and abandoned claim recovery.

### 1.5 PostgreSQL Configuration
- Target Production Engine: PostgreSQL 16 Alpine.
- In-memory unit tests currently run against H2 with `MODE=PostgreSQL`.
- Database per service: `fip_auth`, `fip_plugins`, `fip_events`.

### 1.6 RabbitMQ Configuration
- Target Broker: RabbitMQ 3.13 Alpine with management plugin.
- Exchange topology: Topic Exchange `fip.financial.exchange` and Dead-Letter Exchange `fip.dlx.exchange`.
- Publisher confirms configured: `publisher-confirm-type: correlated`, `publisher-returns: true`, `template.mandatory: true`.

### 1.7 Transactional Outbox Implementation
- Outbox entity: `EventOutboxJpaEntity`.
- Worker leasing: records claimed via `status = 'PROCESSING'`, `locked_by = workerId`, `locked_at = now`.
- Abandoned claim recovery: records in `PROCESSING` longer than 60 seconds are reclaimed.
- Exponential backoff: `1000ms * 2^(retry-1)` up to 60s; transitions to `FAILED` after 5 attempts.
- Broker confirmations: `correlationData.getFuture().get(timeout)` verifies broker ACK before transitioning status to `SENT`.
- Consumer idempotency: guarded by `existsByEventId()` and catching `DataIntegrityViolationException` on concurrent duplicate arrivals.

### 1.8 Existing Docker Compose Configuration
- `docker-compose.yml` configures `postgres:16-alpine`, `rabbitmq:3.13-management-alpine`, `prometheus`, `grafana`, and microservices.
- Downstream microservices do not publish ports to the host; only `api-gateway` (8080) is exposed.

---

## 2. CI/CD Architecture & GitHub Actions Strategy

### 2.1 Workflow Pipeline (`.github/workflows/ci.yml`)
The workflow operates under GitHub Actions on `ubuntu-latest` with three dedicated parallel and sequential phases:

```
+-------------------------------------------------------------------------------+
|                            GITHUB ACTIONS PIPELINE                            |
+-------------------------------------------------------------------------------+
       |                                                 |
       v                                                 v
+-----------------------------+           +-----------------------------+
|     Job 1: Java Build &     |           |     Job 2: Python FastAPI   |
|         Unit Tests          |           |            Tests            |
| - JDK 21 (Temurin)          |           | - Python 3.11               |
| - Gradle Wrapper + Cache    |           | - Pip dependency install    |
| - ./gradlew test            |           | - pytest -v tests/          |
| - Upload JUnit XML Reports  |           |                             |
+-----------------------------+           +-----------------------------+
       |
       v
+-------------------------------------------------------------------------------+
|             Job 3: Infrastructure Integration Tests (Testcontainers)          |
| - Docker Engine active on ubuntu-latest                                       |
| - Real PostgreSQL 16 Alpine Container                                         |
| - Real RabbitMQ 3.13 Alpine Container                                         |
| - ./gradlew integrationTest                                                   |
| - Tests: Flyway, Concurrency, Publisher Confirms, Unroutable, DLX, Idempotency|
+-------------------------------------------------------------------------------+
```

### 2.2 Security & Permissions
- Minimal workflow permissions: `permissions: contents: read`.
- Concurrency controls: `group: ${{ github.workflow }}-${{ github.ref }}`, `cancel-in-progress: true`.
- Zero hardcoded production secrets; CI uses disposable in-memory or ephemeral test container credentials.
- No artifacts deployed from CI.

---

## 3. Test Execution & Task Separation Strategy

To ensure developer productivity and CI reliability, test tasks are explicitly separated in Gradle:
1. **`test`**: Fast unit tests and mock slice tests (`excludeTags 'integration'`). Requires no Docker daemon. Executable on all developer workstations and in CI.
2. **`integrationTest`**: Infrastructure-backed integration tests (`includeTags 'integration'`). Spins up real PostgreSQL 16 and RabbitMQ 3.13 containers via Testcontainers. Executed automatically in GitHub Actions where Docker is running.
3. **`check`**: Executes both `test` and `integrationTest`.

---

## 4. Testcontainers Integration Scenarios to Implement

### 4.1 PostgreSQL 16 Verification
1. Flyway migration execution from clean database state (`V1`, `V2`, `V3`).
2. Event persistence and multi-tenant schema scoping.
3. Database constraints (`uq_events_event_id`, foreign keys, not-null constraints).
4. Atomicity of event store and outbox persistence within a single local transaction.
5. Transaction rollback verification: intentional exception rolls back outbox record with zero orphan persistence.
6. Concurrent worker coordination: two worker threads attempting to lease the same pending records must acquire disjoint sets with zero duplicate processing.

### 4.2 RabbitMQ 3.13 Verification
1. Verification of real exchange, queue, and DLX topology declarations.
2. Verified broker acceptance: message publication completes `CorrelationData` future with `ack=true` and sets status `SENT`.
3. Unroutable message detection: messages with invalid routing keys are routed to return callback or dropped without marking `SENT`.
4. Dead-Letter Exchange routing: poisoned payloads route to DLQ without re-queueing.
5. Durable consumer idempotency: publishing identical `eventId` multiple times to a real AMQP queue results in single persistence without throwing unhandled exceptions.

### 4.3 Security & Gateway Bypass Verification
1. Direct downstream requests lacking HMAC signature return RFC 7807 401.
2. Direct downstream requests with forged signature or tampered body return RFC 7807 401.
3. Cross-tenant access attempts against real PostgreSQL return RFC 7807 404.

### 4.4 Reliability & Outage Recovery
1. Broker outage resilience: when RabbitMQ container is stopped, event ingestion persists to database and outbox with status `PENDING`.
2. Recovery upon restart: when RabbitMQ container restarts, the outbox publisher automatically delivers pending events, receives broker ACKs, and marks records `SENT`.
3. Operational recovery: re-queuing failed outbox messages via `recoverFailedRecords()`.
