package com.fip.ingestion.adapters.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.persistence.SourceMappingProfileJpaRepository;
import com.fip.ingestion.domain.model.FieldMapping;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.SourceType;
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
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Mapping Profile Controller Integration Tests")
class MappingProfileControllerIntegrationTest {

    @Value("${gateway.internal.active-key-id:v1}")
    private String activeKeyId;

    @Value("${gateway.internal.secret:${GATEWAY_INTERNAL_SECRET:" + GatewaySecurityConstants.DEFAULT_GATEWAY_TOKEN + "}}")
    private String gatewaySecret;

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SourceMappingProfileJpaRepository profileRepository;

    private RequestHmacSigner signer;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        profileRepository.deleteAll();
        signer = new RequestHmacSigner(activeKeyId, Map.of(activeKeyId, gatewaySecret));
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
            String tenantId,
            byte[] body) {

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(body);
        String subject = "user@tenant.com";
        String userId = "USR-101";
        String roles = "ROLE_ADMIN,ROLE_ANALYST";

        String signature = signer.sign(
                activeKeyId, method, path, query != null ? query : "",
                subject, userId, tenantId, roles,
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
    @DisplayName("Create Mapping Profile: Registers tenant-scoped profile, returns 201 Created")
    void testCreateProfile() throws Exception {
        String tenantId = "tenant-xyz";

        Map<String, FieldMapping> fieldMap = new HashMap<>();
        fieldMap.put("transactionId", FieldMapping.column("Txn Ref"));
        fieldMap.put("amount", FieldMapping.column("Amount"));
        fieldMap.put("currency", FieldMapping.constant("INR"));

        MappingProfile profile = new MappingProfile(
                null,
                tenantId,
                "ICICI_STATEMENT_PROFILE",
                SourceType.CSV,
                "PAYMENT",
                ",",
                true,
                "dd-MM-yyyy",
                "UTC",
                "INR",
                fieldMap,
                1,
                true
        );

        byte[] bodyBytes = objectMapper.writeValueAsBytes(profile);

        MockHttpServletRequestBuilder request = post("/api/v1/ingestion/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyBytes);

        MvcResult result = mockMvc.perform(withHmac(request, "POST", "/api/v1/ingestion/mappings", "", tenantId, bodyBytes))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.name").value("ICICI_STATEMENT_PROFILE"))
                .andExpect(jsonPath("$.data.profileId").exists())
                .andReturn();

        assertEquals(1, profileRepository.findAll().size());
    }

    @Test
    @DisplayName("Tenant Isolation: Tenant B cannot retrieve Tenant A's mapping profile")
    void testTenantIsolationOnProfiles() throws Exception {
        String tenantA = "tenant-a";
        String tenantB = "tenant-b";

        Map<String, FieldMapping> fieldMap = new HashMap<>();
        fieldMap.put("amount", FieldMapping.column("Amt"));

        MappingProfile profile = new MappingProfile(
                null,
                tenantA,
                "TENANT_A_PROFILE",
                SourceType.JSON,
                "PAYMENT",
                ",",
                true,
                "yyyy-MM-dd",
                "UTC",
                "USD",
                fieldMap,
                1,
                true
        );

        byte[] bodyBytes = objectMapper.writeValueAsBytes(profile);

        // Tenant A creates profile
        MockHttpServletRequestBuilder postReq = post("/api/v1/ingestion/mappings")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyBytes);

        MvcResult postRes = mockMvc.perform(withHmac(postReq, "POST", "/api/v1/ingestion/mappings", "", tenantA, bodyBytes))
                .andExpect(status().isCreated())
                .andReturn();

        String profileIdStr = objectMapper.readTree(postRes.getResponse().getContentAsString())
                .path("data").path("profileId").asText();

        // Tenant B attempts to fetch Tenant A's profile -> 404 Not Found
        byte[] emptyBody = new byte[0];
        MockHttpServletRequestBuilder getReq = get("/api/v1/ingestion/mappings/" + profileIdStr);

        mockMvc.perform(withHmac(getReq, "GET", "/api/v1/ingestion/mappings/" + profileIdStr, "", tenantB, emptyBody))
                .andExpect(status().isNotFound());
    }
}
