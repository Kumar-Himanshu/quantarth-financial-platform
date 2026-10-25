# QuantArth FIP — Milestone 2B: Event Engine CI Test Failure Investigation & Remediation Report

**Date**: 2026-10-26  
**Status**: RESOLVED  
**Classification**: Milestone 2B CI Test Remediation  

---

## 1. Executive Summary

During GitHub Actions CI execution on Ubuntu runners with Java 21 and Gradle 9.2.0, the `java-unit-tests` job completed Java compilation across all modules and passed all 25 unit tests in `auth-service`, `api-gateway`, `shared-contracts`, and `plugin-framework`. However, 5 out of 26 tests in the `event-engine` module failed inside [VerticalSliceIntegrationTest.java](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/event-engine/src/test/java/com/fip/eventengine/VerticalSliceIntegrationTest.java).

All 5 failures stemmed from a single root cause: a configuration secret mismatch between how the production HMAC authentication filter loaded its internal mesh secret and how the integration test generated request signatures.

Following targeted remediation in the test setup, **26/26 tests in `event-engine`** and **51/51 unit tests platform-wide** pass deterministically with full HMAC security, tenant isolation, and transactional outbox assertions preserved.

---

## 2. Root Cause Analysis

### The Secret Mismatch
In [.github/workflows/ci.yml](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/.github/workflows/ci.yml#L20), the workflow defines CI environment variables:
```yaml
env:
  CI: "true"
  JWT_SECRET: "ci_test_jwt_secret_quantarth_fip_minimum_64_characters_super_secure"
  GATEWAY_INTERNAL_SECRET: "ci_test_internal_mesh_secret_2026_min_32_characters"
```

In [GatewayAuthenticationFilter.java](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/event-engine/src/main/java/com/fip/eventengine/config/GatewayAuthenticationFilter.java#L46-L47), the production filter dynamically injects this secret using Spring `@Value`:
```java
@Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
private String gatewayInternalSecret;
```
Because `GATEWAY_INTERNAL_SECRET` was set in the CI runner's process environment, Spring Boot injected `"ci_test_internal_mesh_secret_2026_min_32_characters"` into `GatewayAuthenticationFilter`.

However, inside `VerticalSliceIntegrationTest.java`, the test had hardcoded:
```java
private static final String GATEWAY_SECRET = GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN;
...
signer = new RequestHmacSigner(ACTIVE_KEY_ID, Map.of(ACTIVE_KEY_ID, GATEWAY_SECRET));
```
Consequently:
1. The test signed client requests using the development fallback token (`quantarth-fip-internal-mesh-secret-2026`).
2. The server filter (`GatewayAuthenticationFilter`) verified incoming HMAC signatures against the CI environment variable (`ci_test_internal_mesh_secret_2026_min_32_characters`).
3. Every test request that expected to be authenticated as a valid Gateway request failed signature verification and was immediately rejected by the filter with **HTTP 401 Unauthorized**:
   `"Access denied: Request signature verification failed, expired, or replayed."`

---

## 3. Failure Breakdown & Expected vs Actual Assertions

| # | Test Method | Failing Assertion | Expected Status | Actual Status | Root Cause & Failure Mechanism |
|---|---|---|---|---|---|
| 1 | `testRbacEnforcementReturns403ProblemDetail` | Line 368 | `403 Forbidden` | `401 Unauthorized` | Caller had `ROLE_USER` attempting to call an endpoint requiring `ROLE_ANALYST`. The test expected authorization failure (403), but the request was rejected earlier at the gateway authentication layer (401) due to secret mismatch. |
| 2 | `testReplayedRequestWithIdenticalNonceReturns401` | Line 235 | `201 Created` | `401 Unauthorized` | The first request in the replay test was supposed to succeed with 201 before replaying the same nonce. The initial request was rejected with 401 because signature verification failed against the CI secret. |
| 3 | `testEndToEndVerticalSliceSuccess` | Line 280 | `201 Created` | `401 Unauthorized` | Happy-path event ingestion was rejected at the authentication filter with 401 because the request signature could not be verified with the mismatched secret. |
| 4 | `testPublisherConfirmationsVerifiedBrokerAck` | Line 397 | `201 Created` | `401 Unauthorized` | Ingestion step before triggering outbox polling was rejected with 401; no outbox record was created. |
| 5 | `testCrossTenantIsolationReturns404ProblemDetail` | Line 326 | `201 Created` | `401 Unauthorized` | Step 1 creates Tenant Alpha's event before Tenant Beta attempts cross-tenant access. Creation failed with 401 at the authentication filter. |

---

## 4. Code Changes Implemented

### Production Code
**No production code was modified.** All security controls, HMAC signing algorithms, constant-time verification, role mapping, tenant isolation, and transactional outbox logic remain intact and strictly enforced.

### Test Code Remediations
1. [VerticalSliceIntegrationTest.java](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/event-engine/src/test/java/com/fip/eventengine/VerticalSliceIntegrationTest.java):
   - Replaced static constants with Spring Boot `@Value` properties matching `GatewayAuthenticationFilter`:
     ```java
     @Value("${gateway.internal.active-key-id:v1}")
     private String activeKeyId;

     @Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
     private String gatewaySecret;
     ```
   - In `setUp()`, initialized `RequestHmacSigner` with the dynamically injected secret:
     ```java
     signer = new RequestHmacSigner(activeKeyId, Map.of(activeKeyId, gatewaySecret));
     ```
   - Added `TenantContext.clear()` in `@BeforeEach` and `@AfterEach` lifecycle hooks to guarantee strict test isolation.
   - Updated `withHmac` and all manual test assertions to bind `activeKeyId` dynamically.
   - Fixed duplicate assertion block in `testReplayedRequestWithIdenticalNonceReturns401`.

2. [PostgresAndRabbitMQInfrastructureIntegrationTest.java](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/event-engine/src/test/java/com/fip/eventengine/infrastructure/PostgresAndRabbitMQInfrastructureIntegrationTest.java):
   - Applied identical `@Value` dynamic secret injection to ensure the Testcontainers-based infrastructure test suite runs seamlessly in CI when `GATEWAY_INTERNAL_SECRET` is present.

---

## 5. Verification Results

### 1. Isolated `event-engine` Execution (with CI Environment Secret)
```powershell
$env:GATEWAY_INTERNAL_SECRET="ci_test_internal_mesh_secret_2026_min_32_characters"
.\gradlew.bat :event-engine:test --rerun-tasks
```
**Result**:
```text
BUILD SUCCESSFUL in 1m 6s
9 actionable tasks: 9 executed
26 tests completed, 0 failed, 0 skipped
```

### 2. Platform-Wide Test Execution (with CI Environment Secret)
```powershell
$env:GATEWAY_INTERNAL_SECRET="ci_test_internal_mesh_secret_2026_min_32_characters"
.\gradlew.bat test --rerun-tasks
```
**Result**:
```text
BUILD SUCCESSFUL in 1m 14s
26 actionable tasks: 26 executed
```
- `auth-service`: 12/12 PASSED
- `shared-contracts`: 5/5 PASSED
- `api-gateway`: 4/4 PASSED
- `event-engine`: 26/26 PASSED
- `plugin-framework`: 4/4 PASSED
- **Total**: **51/51 PASSED** (0 failures, 0 errors, 0 skipped)

---

## 6. Files Modified

1. [event-engine/src/test/java/com/fip/eventengine/VerticalSliceIntegrationTest.java](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/event-engine/src/test/java/com/fip/eventengine/VerticalSliceIntegrationTest.java)
2. [event-engine/src/test/java/com/fip/eventengine/infrastructure/PostgresAndRabbitMQInfrastructureIntegrationTest.java](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/event-engine/src/test/java/com/fip/eventengine/infrastructure/PostgresAndRabbitMQInfrastructureIntegrationTest.java)
3. [docs/milestone-2b-event-engine-test-fix-report.md](file:///c:/Users/Priya%20Singh/.gemini/antigravity-ide/scratch/financial-platform/docs/milestone-2b-event-engine-test-fix-report.md)

---

## 7. Remaining CI Risks

- **Docker in GitHub Actions**: Unit tests run without Docker using in-memory H2 and Mockito. The `infrastructure-integration-tests` job requires a live Docker daemon for Testcontainers (PostgreSQL 16 and RabbitMQ 3.13), which is provided on GitHub's Ubuntu runner (`ubuntu-latest`).
- **Production Readiness**: The platform remains classified as **NOT PRODUCTION-READY** until the complete GitHub Actions workflow succeeds on GitHub.
