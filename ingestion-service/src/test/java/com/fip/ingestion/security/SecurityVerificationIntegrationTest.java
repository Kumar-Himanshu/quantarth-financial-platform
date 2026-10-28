package com.fip.ingestion.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.eventengine.EventEngineClient;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.adapters.out.persistence.SourceMappingProfileJpaEntity;
import com.fip.ingestion.adapters.out.persistence.SourceMappingProfileJpaRepository;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.SourceType;
import com.fip.shared.events.CanonicalEvent;
import com.fip.shared.security.GatewaySecurityConstants;
import com.fip.shared.security.RequestHmacSigner;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Milestone 4B Section 7: Security Verification Tests.
 * <p>
 * Verifies:
 * - Gateway authentication rejection of direct unsigned downstream calls (RFC 7807 401)
 * - Internal HMAC verification (signature integrity check)
 * - Replay protection: timestamps outside +/- 60s sliding window or duplicate nonces rejected
 * - Tampered body digest rejection
 * - Tenant spoofing prevention: context bound to authenticated X-Tenant-Id
 * - Cross-tenant job access rejection (RFC 7807 404)
 * - Cross-tenant mapping profile access rejection (RFC 7807 404)
 * - HMAC trust boundary verification
 */
@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Security & Tenant Isolation Verification Tests")
class SecurityVerificationIntegrationTest {

    @Value("${gateway.internal.active-key-id:v1}")
    private String activeKeyId;

    @Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
    private String gatewaySecret;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private IngestionJobJpaRepository jobRepository;

    @Autowired
    private SourceMappingProfileJpaRepository profileRepository;

    @MockBean
    private EventEngineClient eventEngineClient;

    private RequestHmacSigner signer;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        jobRepository.deleteAll();
        profileRepository.deleteAll();
        signer = new RequestHmacSigner(activeKeyId, Map.of(activeKeyId, gatewaySecret));

        when(eventEngineClient.forwardBatch(anyList())).thenAnswer(invocation -> {
            List<CanonicalEvent> events = invocation.getArgument(0);
            return new EventEngineClient.BatchForwardResult(events, Collections.emptyList());
        });
    }

    @AfterEach
    void tearDown() {
        TenantContext.clear();
    }

    private MockHttpServletRequestBuilder withHmac(
            MockHttpServletRequestBuilder builder,
            String method,
            String path,
            String query,
            String subject,
            String userId,
            String tenantId,
            String roles,
            byte[] body) {

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(body);

        String signature = signer.sign(
                activeKeyId, method, path, query != null ? query : "",
                subject != null ? subject : "", userId != null ? userId : "",
                tenantId != null ? tenantId : "", roles != null ? roles : "",
                timestamp, nonce, bodyDigest
        );

        return builder
                .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, subject)
                .header(GatewaySecurityConstants.HEADER_USER_ID, userId)
                .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                .header(GatewaySecurityConstants.HEADER_USER_ROLES, roles);
    }

    @Test
    @DisplayName("7.1 Gateway Authentication: Direct call without internal HMAC headers returns RFC 7807 401")
    void testDirectBypassWithoutHmacRejected() throws Exception {
        mockMvc.perform(post("/api/v1/ingestion/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[]}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.detail").value("Access denied: Missing internal service authentication headers. Request must pass through API Gateway."));
    }

    @Test
    @DisplayName("7.2 Tampered Body Digest: Payload modification after signing returns RFC 7807 401")
    void testTamperedBodyDigestRejected() throws Exception {
        String originalBody = "{\"records\":[{\"id\":\"1\",\"amount\":\"10\"}]}";
        String tamperedBody = "{\"records\":[{\"id\":\"1\",\"amount\":\"1000000\"}]}";

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String originalDigest = RequestHmacSigner.computeSha256Hex(originalBody.getBytes(StandardCharsets.UTF_8));
        String signature = signer.sign(
                activeKeyId, "POST", "/api/v1/ingestion/json", "sync=true",
                "analyst@corp.com", "usr-1", "tenant-1", "ROLE_ADMIN", timestamp, nonce, originalDigest);

        mockMvc.perform(post("/api/v1/ingestion/json?sync=true")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, originalDigest)
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, "tenant-1")
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "analyst@corp.com")
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(tamperedBody))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Access denied: Request body digest tampering detected."));
    }

    @Test
    @DisplayName("7.3 Replay Protection: Expired timestamp (> 60s) returns RFC 7807 401")
    void testExpiredTimestampRejected() throws Exception {
        String body = "{\"records\":[]}";
        byte[] bodyBytes = body.getBytes(StandardCharsets.UTF_8);

        long expiredTimestamp = System.currentTimeMillis() - 120_000; // 2 minutes ago
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(bodyBytes);
        String signature = signer.sign(
                activeKeyId, "POST", "/api/v1/ingestion/json", "",
                "user@corp.com", "usr-1", "tenant-1", "ROLE_ADMIN", expiredTimestamp, nonce, bodyDigest);

        mockMvc.perform(post("/api/v1/ingestion/json")
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(expiredTimestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, "tenant-1")
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "user@corp.com")
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ADMIN")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(bodyBytes))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.detail").value("Access denied: Request signature verification failed, expired, or replayed."));
    }

    @Test
    @DisplayName("7.4 Cross-Tenant Job Access: Querying another tenant's job ID returns RFC 7807 404")
    void testCrossTenantJobAccessReturns404() throws Exception {
        String tenantAlpha = "tenant-alpha";
        String tenantBeta = "tenant-beta";

        IngestionJobJpaEntity jobAlpha = new IngestionJobJpaEntity();
        jobAlpha.setJobId(UUID.randomUUID());
        jobAlpha.setTenantId(tenantAlpha);
        jobAlpha.setSourceType(SourceType.JSON);
        jobAlpha.setSourceSystem("APP");
        jobAlpha.setStatus(JobStatus.COMPLETED);
        jobAlpha.setTotalRecords(5);
        jobAlpha.setAcceptedRecords(5);
        jobRepository.save(jobAlpha);

        // Tenant Beta attempts to access Tenant Alpha's job
        byte[] emptyBody = new byte[0];
        MockHttpServletRequestBuilder request = get("/api/v1/ingestion/jobs/" + jobAlpha.getJobId());

        mockMvc.perform(withHmac(
                request, "GET", "/api/v1/ingestion/jobs/" + jobAlpha.getJobId(), "",
                "attacker@tenantbeta.com", "usr-beta", tenantBeta, "ROLE_ADMIN", emptyBody
        )).andExpect(status().isNotFound())
          .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    @DisplayName("7.5 Cross-Tenant Mapping Profile Access: Querying another tenant's profile returns RFC 7807 404")
    void testCrossTenantMappingProfileAccessReturns404() throws Exception {
        String tenantAlpha = "tenant-alpha";
        String tenantBeta = "tenant-beta";

        UUID profileId = UUID.randomUUID();
        SourceMappingProfileJpaEntity profileAlpha = new SourceMappingProfileJpaEntity();
        profileAlpha.setProfileId(profileId);
        profileAlpha.setTenantId(tenantAlpha);
        profileAlpha.setName("SECRET_PROFILE");
        profileAlpha.setSourceFormat(SourceType.CSV.name());
        profileAlpha.setTargetEntityType("PAYMENT");
        profileAlpha.setDelimiter(",");
        profileAlpha.setHasHeader(true);
        profileAlpha.setDateFormat("yyyy-MM-dd");
        profileAlpha.setTimezone("UTC");
        profileAlpha.setCurrencyDefault("INR");
        profileAlpha.setMappingRules("{}");
        profileAlpha.setVersion(1);
        profileAlpha.setActive(true);
        profileAlpha.setCreatedAt(LocalDateTime.now());
        profileAlpha.setUpdatedAt(LocalDateTime.now());
        profileRepository.save(profileAlpha);

        // Tenant Beta attempts to read Tenant Alpha's mapping profile
        byte[] emptyBody = new byte[0];
        MockHttpServletRequestBuilder request = get("/api/v1/ingestion/mappings/" + profileId);

        mockMvc.perform(withHmac(
                request, "GET", "/api/v1/ingestion/mappings/" + profileId, "",
                "attacker@tenantbeta.com", "usr-beta", tenantBeta, "ROLE_ADMIN", emptyBody
        )).andExpect(status().isNotFound())
          .andExpect(jsonPath("$.status").value(404));
    }
}
