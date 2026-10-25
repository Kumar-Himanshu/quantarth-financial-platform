# Financial Intelligence Platform (FIP)
### Production-Grade Enterprise Platform Architecture

[![CI/CD Pipeline](https://github.com/company/financial-platform/actions/workflows/ci.yml/badge.svg)](.github/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Spring Boot 3.3.1](https://img.shields.io/badge/Spring%20Boot-3.3.1-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![FastAPI](https://img.shields.io/badge/FastAPI-0.111-blue.svg)](https://fastapi.tiangolo.com/)
[![PostgreSQL](https://img.shields.io/badge/PostgreSQL-16-blue.svg)](https://www.postgresql.org/)
[![RabbitMQ](https://img.shields.io/badge/RabbitMQ-3.13-orange.svg)](https://www.rabbitmq.com/)

---

## 1. Executive Summary

The **Financial Intelligence Platform (FIP)** is a high-throughput, enterprise-grade distributed backend platform designed to serve as the foundational backbone for multiple high-value fintech products:

- **ArthQuant AI**: Algorithmic quant risk modeling and automated asset allocation.
- **Document AI SaaS**: Scalable invoice OCR, layout detection, and semantic entity extraction.
- **Risk APIs**: Transaction risk scoring, fraud detection, and dual-signoff authorization gates.
- **Merchant Intelligence APIs**: Vendor normalization, MCC categorization, and graph enrichment.
- **Future Enterprise AI Products**: Dynamic sandboxed plugin execution.

> **Zero Product Coupling Principle**: The platform core contains **zero product-specific logic**. Product suites consume platform capabilities purely through RESTful OpenAPI contracts and canonical RabbitMQ events.

---

## 2. Architectural Pillars

```
+-----------------------------------------------------------------------------------+
|                           CONSUMER PRODUCTS & WEB APPS                            |
|             (ArthQuant AI, Document AI SaaS, Risk APIs, Merchant APIs)            |
+-----------------------------------------------------------------------------------+
                                          |
                              HTTPS / REST & OpenAPI
                                          v
+-----------------------------------------------------------------------------------+
|                        SPRING CLOUD GATEWAY (Port 8080)                           |
|       - JWT Validation (Claims Injection)          - Sliding-Window Rate Limiting |
|       - Correlation ID Tracing (X-Correlation-ID)  - CORS & RFC 7807 Errors       |
+-----------------------------------------------------------------------------------+
       |                         |                         |               |
       v                         v                         v               v
+--------------+          +---------------+         +--------------+  +-------------+
| AUTH SERVICE |          | PLUGIN ENGINE |         | EVENT ENGINE |  |  AI SERVICE |
| (Port 8081)  |          |  (Port 8082)  |         | (Port 8083)  |  | (Port 8000) |
| - RBAC Roles |          | - Sandboxing  |         | - EventStore |  | - OCR / YOLO|
| - JWT Rotate |          | - Dynamic SDK |         | - Idempotency|  | - NER / MCC |
| - Flyway V1  |          | - Flyway V1   |         | - Flyway V1  |  | - Recommends|
+--------------+          +---------------+         +--------------+  +-------------+
       |                         |                         |                 |
 [fip_auth DB]            [fip_plugins DB]          [fip_events DB]          |
       \                         |                         /                 |
        \                        v                        /                  |
         +-----------------------------------------------+                   |
         |         RABBITMQ TOPIC & DEAD-LETTER EXCHANGES| <-----------------+
         |       (fip.financial.exchange / fip.dlx.exchange)                 |
         +-----------------------------------------------+
```

1. **Domain-Driven Design (DDD)**: Explicit aggregate boundaries (`User`, `Role`, `PluginMetadata`, `FinancialEvent`) with ubiquitous domain language.
2. **Hexagonal Architecture (Ports and Adapters)**: Pure domain core decoupled from infrastructure frameworks, databases, and messaging protocols.
3. **Strict Data Sovereignty (Database-per-Service)**: Each microservice owns its private PostgreSQL database (`fip_auth`, `fip_plugins`, `fip_events`). Direct cross-database access is prohibited.
4. **Event-Driven Architecture (EDA)**: Canonical event streaming via RabbitMQ with Dead Letter Exchanges (DLQ) and idempotency guards.
5. **Multi-Tenancy & Auditing**: Unified tenant partitioning (`tenant_id`), UUID primary keys, optimistic locking (`@Version`), and automated audit hooks (`created_at`, `updated_at`, `created_by`, `updated_by`, `soft_delete`).
6. **RFC 7807 Problem Details**: Standardized error responses across all Java and Python services.

---

## 3. Technology Stack

| Layer | Technologies |
|---|---|
| **Language & Runtime** | Java 21 LTS, Python 3.11 |
| **Frameworks** | Spring Boot 3.3.1, Spring Cloud Gateway, Spring Security, FastAPI, Pydantic v2 |
| **Data Persistence** | Spring Data JPA, PostgreSQL 16 Alpine, Flyway Database Migrations |
| **Messaging & Events** | RabbitMQ 3.13 (AMQP), Topic Exchanges, Dead-Letter Exchanges (DLQ) |
| **Security & Auth** | JWT (HMAC-SHA256), BCrypt Password Hashing, RBAC Roles & Fine-Grained Permissions |
| **API Documentation** | OpenAPI 3.0, Springdoc OpenAPI (Swagger UI), ReDoc |
| **Mapping & Utils** | MapStruct 1.5.5, Project Lombok |
| **Observability** | Prometheus, Grafana, OpenTelemetry, Micrometer, Structured MDC Logging |
| **Containerization** | Docker, Docker Compose (Multi-stage builds, non-root users) |
| **Testing** | JUnit 5, Mockito, AssertJ, Reactor Test, Pytest, Pytest-Asyncio |

---

## 4. Project Directory Structure

```
financial-platform/
├── api-gateway/                 # Spring Cloud Gateway (Port 8080)
│   ├── src/main/java/com/fip/gateway/filters/  # JwtValidationFilter, RateLimitingFilter, Tracing
│   └── src/main/resources/application.yml
├── auth-service/                # Authentication & RBAC Service (Port 8081)
│   ├── src/main/java/com/fip/auth/
│   │   ├── domain/model/        # User, Role, Permission, RefreshToken
│   │   ├── ports/in/ & out/     # Driving & Driven Ports
│   │   ├── adapters/in/web/     # AuthController (OpenAPI)
│   │   ├── adapters/out/        # Persistence & RabbitMQ Adapters, MapStruct
│   │   └── service/             # AuthService implementation
│   └── src/main/resources/db/migration/V1__init_auth.sql
├── plugin-framework/            # Dynamic Plugin Sandboxing & SDK (Port 8082)
│   ├── src/main/java/com/fip/plugin/
│   │   ├── domain/model/        # PluginMetadata, ExecutionLog
│   │   ├── ports/in/ & out/     # PluginLifecycleUseCase, PersistencePort
│   │   ├── adapters/            # PluginController, URLClassLoader Sandbox
│   │   └── service/             # PluginLifecycleService
│   └── src/main/resources/db/migration/V1__init_plugins.sql
├── event-engine/                # Event Sourcing & Audit Engine (Port 8083)
│   ├── src/main/java/com/fip/eventengine/
│   │   ├── domain/model/        # FinancialEvent
│   │   ├── ports/in/ & out/     # EventIngestionUseCase, EventStorePort
│   │   ├── adapters/            # RabbitMQEventListener, EventController
│   │   └── service/             # EventIngestionService
│   └── src/main/resources/db/migration/V1__init_events.sql
├── ai-service/                  # Python FastAPI Document & ML Service (Port 8000)
│   ├── app/
│   │   ├── api/v1/endpoints/    # OCR, YOLO, NER, Classify, Merchant, Recommendation
│   │   ├── schemas/             # Pydantic v2 typed models
│   │   └── services/            # Pure ML / Parsing engines
│   ├── tests/                   # Pytest test suite
│   ├── Dockerfile
│   └── requirements.txt
├── common-library/              # Reusable cross-cutting domain components
│   └── src/main/java/com/fip/common/  # StandardResponse, AuditModel, RFC 7807 Exceptions
├── shared-contracts/            # Shared SDK & canonical event models
│   └── src/main/java/com/fip/shared/  # CanonicalEvent, EventTopics, FipPlugin SDK
├── architecture/                # Mermaid C4 diagrams, Hexagonal deep-dives, sequence flows
├── docs/                        # ADRs (ADR-001 through ADR-010), OpenAPI 3.0 yaml contracts
├── deployment/
│   └── docker/                  # init-databases.sql, prometheus.yml, Grafana datasources
├── scripts/                     # build-all, run-local, run-tests (.sh and .ps1)
├── docker-compose.yml           # Unified orchestration stack
├── build.gradle & settings.gradle
└── README.md
```

---

## 5. Quick Start (Running Locally via Docker)

### Prerequisites
- Docker Engine 24+ & Docker Compose v2 (or local Java 21+ & Gradle)

### Step 0: Environment Configuration
Copy `.env.example` to `.env`:
```powershell
Copy-Item .env.example .env
```
*(On Linux/macOS: `cp .env.example .env`)*

All secrets, JWT keys, and database passwords are automatically loaded from `.env`.

### One-Command Startup
Execute the startup script:

**On Linux / macOS:**
```bash
./scripts/run-local.sh
```

**On Windows (PowerShell):**
```powershell
.\scripts\run-local.ps1
```

Or execute directly with Docker Compose:
```bash
docker compose up -d --build
```

### Local Build & Test Without Docker
```powershell
# Build all microservices
.\gradlew.bat build -x test

# Run all 51 automated unit, integration, and security tests across services
.\gradlew.bat test
```

### Active Endpoints & Dashboards

| Service | Address | Description |
|---|---|---|
| **API Gateway** | `http://localhost:8080` | Unified Edge Proxy, HMAC Signing & Routing |
| **RabbitMQ Management** | `http://localhost:15672` | AMQP Queue Monitor (`guest`/`guest`) |
| **Prometheus Telemetry** | `http://localhost:9090` | Time-Series Metrics & Scrapes |
| **Grafana Dashboards** | `http://localhost:3000` | Enterprise Monitoring (`admin`/`admin`) |

> **Security Note**: Downstream services (`auth-service`, `plugin-framework`, `event-engine`, `ai-service`) do not publish ports to the host in Docker Compose. All client ingress must pass through API Gateway with HMAC-SHA256 request verification.

---

## 6. Testing & Quality Assurance

Run the comprehensive unit, security, and integration test suites:

**On Linux / macOS:**
```bash
./scripts/run-tests.sh
```

**On Windows (PowerShell):**
```powershell
.\scripts\run-tests.ps1
```

### Test Coverage Highlights (51 Automated Java Tests + 8 FastAPI Tests)
- **Auth Service (12 tests)**: `AuthControllerTest`, `AuthServiceTest` covering public UUID tenant registration, tenant takeover attempt rejection, invitation token HMAC verification, token tampering rejection, concurrent tenant registration uniqueness, and JWT rotation.
- **API Gateway (4 tests)**: `JwtValidationFilterTest` verifying spoofed header stripping, JWT claim validation, and request-bound HMAC-SHA256 signature injection.
- **Shared Contracts (5 tests)**: `RequestHmacSignerTest` asserting HMAC canonical signing, parameter tampering rejection, sliding-window nonce replay rejection, clock skew enforcement, and key rotation.
- **Event Engine (26 tests)**: `VerticalSliceIntegrationTest`, `RabbitMQEventListenerTest`, `EventControllerTest`, `EventIngestionServiceTest` covering HMAC bypass rejection, forged signature rejection, tampered body rejection, expired timestamp rejection, replayed nonce rejection, multi-tenant isolation (404), RBAC enforcement (403), outbox broker ACK publisher confirms, broker NACK exponential backoff, abandoned claim recovery, operational recovery, and durable consumer idempotency.
- **Plugin Framework (4 tests)**: `PluginLifecycleServiceTest`, sandbox execution, status transitions (`ACTIVE`, `SUSPENDED`).
- **AI Service (8 tests)**: `test_ai_endpoints.py` covering all 8 FastAPI endpoints with Pytest.

---

## 7. Recommended Workspace Configuration

To optimize development inside Antigravity IDE, set the active workspace directory to:
```
C:\Users\Priya Singh\.gemini\antigravity-ide\scratch\financial-platform
```
