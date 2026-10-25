package com.fip.shared.security;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class RequestHmacSignerTest {

    private RequestHmacSigner signer;
    private final String currentKey = "super-secret-hmac-key-for-internal-gateway-authentication-32chars";
    private final String previousKey = "old-secret-hmac-key-for-internal-gateway-authentication-32chars";

    @BeforeEach
    void setUp() {
        signer = new RequestHmacSigner(
                "v1",
                Map.of("v1", currentKey, "v0", previousKey),
                60_000L
        );
    }

    @Test
    @DisplayName("Should sign and verify a valid request")
    void shouldSignAndVerifyValidRequest() {
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-12345";
        String body = "{\"amount\":100.00}";
        String bodyDigest = RequestHmacSigner.computeBodyDigest(body.getBytes());

        String signature = signer.sign(
                "v1",
                "POST",
                "/api/v1/events",
                "filter=active",
                "user@example.com",
                "usr-001",
                "tenant-alpha",
                "ROLE_USER",
                timestamp,
                nonce,
                bodyDigest
        );

        boolean valid = signer.verify(
                "v1",
                timestamp,
                nonce,
                signature,
                "POST",
                "/api/v1/events",
                "filter=active",
                "user@example.com",
                "usr-001",
                "tenant-alpha",
                "ROLE_USER",
                bodyDigest
        );

        assertThat(valid).isTrue();
    }

    @Test
    @DisplayName("Should reject tampered canonical components (path, body, tenant, roles)")
    void shouldRejectTamperedComponents() {
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-tamper-test";
        String bodyDigest = RequestHmacSigner.computeBodyDigest("valid".getBytes());

        String signature = signer.sign(
                "v1", "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER",
                timestamp, nonce, bodyDigest
        );

        // Tampered path
        assertThat(signer.verify("v1", timestamp, nonce, signature,
                "GET", "/api/v1/admin-data", "", "alice", "u1", "tenant-1", "ROLE_USER", bodyDigest)).isFalse();

        // Tampered tenant
        assertThat(signer.verify("v1", timestamp, nonce, signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-2", "ROLE_USER", bodyDigest)).isFalse();

        // Tampered role escalation
        assertThat(signer.verify("v1", timestamp, nonce, signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_ADMIN", bodyDigest)).isFalse();

        // Tampered body digest
        assertThat(signer.verify("v1", timestamp, nonce, signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER", "tampered-digest")).isFalse();
    }

    @Test
    @DisplayName("Should reject replay attack using identical nonce")
    void shouldRejectReplayAttack() {
        long timestamp = System.currentTimeMillis();
        String nonce = "replay-nonce-unique";
        String bodyDigest = RequestHmacSigner.computeBodyDigest("".getBytes());

        String signature = signer.sign(
                "v1", "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER",
                timestamp, nonce, bodyDigest
        );

        // First verification succeeds
        boolean first = signer.verify("v1", timestamp, nonce, signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER", bodyDigest);
        assertThat(first).isTrue();

        // Second verification with identical nonce must fail (replay protection)
        boolean second = signer.verify("v1", timestamp, nonce, signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER", bodyDigest);
        assertThat(second).isFalse();
    }

    @Test
    @DisplayName("Should reject expired request beyond clock skew window")
    void shouldRejectExpiredTimestamp() {
        long expiredTimestamp = System.currentTimeMillis() - 65_000L; // 65 seconds ago
        String nonce = "nonce-expired";
        String bodyDigest = RequestHmacSigner.computeBodyDigest("".getBytes());

        String signature = signer.sign(
                "v1", "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER",
                expiredTimestamp, nonce, bodyDigest
        );

        boolean valid = signer.verify("v1", expiredTimestamp, nonce, signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER", bodyDigest);
        assertThat(valid).isFalse();
    }

    @Test
    @DisplayName("Should support key rotation across active key ring")
    void shouldSupportKeyRotation() {
        long timestamp = System.currentTimeMillis();
        String nonce = "nonce-rotation";
        String bodyDigest = RequestHmacSigner.computeBodyDigest("".getBytes());

        // Signed with old key v0
        String signature = signer.sign(
                "v0", "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER",
                timestamp, nonce, bodyDigest
        );

        // Verification using key ID v0 succeeds even though default/active key is v1
        boolean valid = signer.verify("v0", timestamp, nonce, signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER", bodyDigest);
        assertThat(valid).isTrue();

        // Verification fails if key ID is unknown
        boolean unknownKey = signer.verify("v99", timestamp, "nonce-rot-2", signature,
                "GET", "/api/v1/data", "", "alice", "u1", "tenant-1", "ROLE_USER", bodyDigest);
        assertThat(unknownKey).isFalse();
    }
}
