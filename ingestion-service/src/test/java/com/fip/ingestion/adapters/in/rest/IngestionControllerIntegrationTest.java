package com.fip.ingestion.adapters.in.rest;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.eventengine.EventEngineClient;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaRepository;
import com.fip.ingestion.application.service.JsonIngestionService;
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
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@DisplayName("Ingestion Controller End-to-End Integration Tests")
class IngestionControllerIntegrationTest {

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
    private IngestionJobRecordJpaRepository recordRepository;

    @MockBean
    private EventEngineClient eventEngineClient;

    private RequestHmacSigner signer;

    @BeforeEach
    void setUp() {
        TenantContext.clear();
        recordRepository.deleteAll();
        jobRepository.deleteAll();
        signer = new RequestHmacSigner(activeKeyId, Map.of(activeKeyId, gatewaySecret));

        // Default mock for event engine: acknowledge all forwarded events as persisted
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
            String tenantId,
            byte[] body) {

        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        String bodyDigest = RequestHmacSigner.computeSha256Hex(body);
        String subject = "user@tenant.com";
        String userId = "USR-99";
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
    @DisplayName("Direct call lacking HMAC gateway signature is rejected with 401 Unauthorized")
    void testDirectAccessWithoutHmacFails() throws Exception {
        mockMvc.perform(post("/api/v1/ingestion/json")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"records\":[]}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Synchronous JSON Ingestion: Processes valid financial payment records, returns 200 OK")
    void testSyncJsonIngestionSuccess() throws Exception {
        String tenantId = "tenant-finance-1";
        String payload = """
                {
                  "sourceSystem": "FINTECH_APP",
                  "records": [
                    {
                      "id": "TXN-101",
                      "amount": "1500.50",
                      "currency": "INR",
                      "payerName": "Priya Sharma",
                      "payeeName": "Acme Retail Pvt Ltd",
                      "paymentMethod": "UPI"
                    },
                    {
                      "id": "TXN-102",
                      "amount": "2750.00",
                      "currency": "INR",
                      "payerName": "Rohit Verma",
                      "payeeName": "QuickMart",
                      "paymentMethod": "BANK_TRANSFER"
                    }
                  ]
                }
                """;

        byte[] bodyBytes = payload.getBytes(StandardCharsets.UTF_8);

        MockHttpServletRequestBuilder request = post("/api/v1/ingestion/json")
                .queryParam("sync", "true")
                .contentType(MediaType.APPLICATION_JSON)
                .content(bodyBytes);

        MvcResult result = mockMvc.perform(withHmac(request, "POST", "/api/v1/ingestion/json", "sync=true", tenantId, bodyBytes))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").exists())
                .andExpect(jsonPath("$.data.totalRecords").value(2))
                .andExpect(jsonPath("$.data.acceptedRecords").value(2))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"))
                .andReturn();

        // Verify Job was persisted
        List<IngestionJobJpaEntity> jobs = jobRepository.findAll();
        assertEquals(1, jobs.size());
        assertEquals(tenantId, jobs.get(0).getTenantId());
        assertEquals(2, jobs.get(0).getAcceptedRecords());
    }

    @Test
    @DisplayName("CSV Multipart Ingestion: Uploads valid statement, creates Job, returns 202 Accepted")
    void testCsvMultipartIngestion() throws Exception {
        String tenantId = "tenant-csv-1";
        String csvContent = """
                id,amount,currency,payerName,payeeName,paymentMethod
                TXN-CSV-1,500.00,INR,Aarav Patel,Merchant Corp,UPI
                TXN-CSV-2,1200.00,INR,Isha Sen,Tech Services,CARD
                """;

        MockMultipartFile file = new MockMultipartFile(
                "file", "bank_statement.csv", "text/csv", csvContent.getBytes(StandardCharsets.UTF_8));

        // For multipart, the body digest for HMAC signing is calculated over empty byte array or boundary payload
        // In our GatewayAuthenticationFilter, it validates HMAC for incoming requests
        long timestamp = System.currentTimeMillis();
        String nonce = UUID.randomUUID().toString();
        byte[] emptyBody = new byte[0];
        String bodyDigest = RequestHmacSigner.computeSha256Hex(emptyBody);
        String signature = signer.sign(
                activeKeyId, "POST", "/api/v1/ingestion/csv", "",
                "user@tenant.com", "USR-99", tenantId, "ROLE_ADMIN,ROLE_ANALYST",
                timestamp, nonce, bodyDigest
        );

        mockMvc.perform(multipart("/api/v1/ingestion/csv")
                        .file(file)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_KEY_ID, activeKeyId)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_TIMESTAMP, String.valueOf(timestamp))
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_NONCE, nonce)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_SIGNATURE, signature)
                        .header(GatewaySecurityConstants.HEADER_GATEWAY_BODY_DIGEST, bodyDigest)
                        .header(GatewaySecurityConstants.HEADER_USER_SUBJECT, "user@tenant.com")
                        .header(GatewaySecurityConstants.HEADER_USER_ID, "USR-99")
                        .header(GatewaySecurityConstants.HEADER_TENANT_ID, tenantId)
                        .header(GatewaySecurityConstants.HEADER_USER_ROLES, "ROLE_ADMIN,ROLE_ANALYST"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.jobId").exists())
                .andExpect(jsonPath("$.data.status").value("RECEIVED"));

        // Verify Job was queued/started in database
        List<IngestionJobJpaEntity> jobs = jobRepository.findAll();
        assertEquals(1, jobs.size());
        assertEquals(tenantId, jobs.get(0).getTenantId());
    }

    @Test
    @DisplayName("GET /jobs/{jobId}: Returns 200 OK for tenant's job and 404 for non-existent or other tenant's job")
    void testGetJobTenantIsolation() throws Exception {
        String tenantA = "tenant-a";
        String tenantB = "tenant-b";

        // Seed a job for Tenant A
        IngestionJobJpaEntity jobA = new IngestionJobJpaEntity();
        jobA.setJobId(UUID.randomUUID());
        jobA.setTenantId(tenantA);
        jobA.setSourceType(com.fip.ingestion.domain.model.SourceType.JSON);
        jobA.setSourceSystem("CORE_APP");
        jobA.setStatus(com.fip.ingestion.domain.model.JobStatus.COMPLETED);
        jobA.setTotalRecords(10);
        jobA.setProcessedRecords(10);
        jobA.setAcceptedRecords(10);
        jobA.setRejectedRecords(0);
        jobA.setDuplicateRecords(0);
        jobA.setUnverifiedRecords(0);
        jobRepository.save(jobA);

        // Tenant A queries its own job -> 200 OK
        byte[] emptyBody = new byte[0];
        MockHttpServletRequestBuilder requestA = get("/api/v1/ingestion/jobs/" + jobA.getJobId());
        mockMvc.perform(withHmac(requestA, "GET", "/api/v1/ingestion/jobs/" + jobA.getJobId(), "", tenantA, emptyBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.jobId").value(jobA.getJobId().toString()))
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));

        // Tenant B queries Tenant A's job -> 404 Not Found (strict tenant isolation)
        MockHttpServletRequestBuilder requestB = get("/api/v1/ingestion/jobs/" + jobA.getJobId());
        mockMvc.perform(withHmac(requestB, "GET", "/api/v1/ingestion/jobs/" + jobA.getJobId(), "", tenantB, emptyBody))
                .andExpect(status().isNotFound());
    }
}
