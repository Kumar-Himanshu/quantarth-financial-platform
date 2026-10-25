package com.fip.shared.security;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Robust HMAC-SHA256 Service-to-Service Request Authentication.
 * Binds signatures to HTTP Method, Canonical Path, Query, Caller Identity Context,
 * Timestamp, Nonce, and Request-Body Digest.
 * Supports Key Rotation and Replay Attack Protection.
 */
public class RequestHmacSigner {

    public static final String HEADER_KEY_ID = "X-Gateway-Key-Id";
    public static final String HEADER_TIMESTAMP = "X-Gateway-Timestamp";
    public static final String HEADER_NONCE = "X-Gateway-Nonce";
    public static final String HEADER_SIGNATURE = "X-Gateway-Signature";
    public static final String HEADER_BODY_DIGEST = "X-Gateway-Body-Digest";

    public static final long DEFAULT_MAX_SKEW_MS = 60_000L; // 60 seconds
    public static final String EMPTY_BODY_DIGEST = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final String activeKeyId;
    private final Map<String, String> keyRing; // Supports key rotation
    private final Map<String, Long> nonceCache = new ConcurrentHashMap<>();
    private final long maxSkewMs;

    public RequestHmacSigner(String activeKeyId, Map<String, String> keyRing) {
        this(activeKeyId, keyRing, DEFAULT_MAX_SKEW_MS);
    }

    public RequestHmacSigner(String activeKeyId, Map<String, String> keyRing, long maxSkewMs) {
        this.activeKeyId = activeKeyId;
        this.keyRing = new ConcurrentHashMap<>(keyRing);
        this.maxSkewMs = maxSkewMs;
    }

    public String getActiveKeyId() {
        return activeKeyId;
    }

    public static String computeSha256Hex(byte[] data) {
        if (data == null || data.length == 0) {
            return EMPTY_BODY_DIGEST;
        }
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("SHA-256 not available", e);
        }
    }

    public static String buildCanonicalString(String method, String path, String query,
                                             String subject, String userId, String tenantId,
                                             String roles, long timestamp, String nonce, String bodyDigest) {
        return (method != null ? method.toUpperCase() : "GET") + "\n" +
                (path != null ? path : "/") + "\n" +
                (query != null ? query : "") + "\n" +
                (subject != null ? subject : "") + "\n" +
                (userId != null ? userId : "") + "\n" +
                (tenantId != null ? tenantId : "") + "\n" +
                (roles != null ? roles : "") + "\n" +
                timestamp + "\n" +
                (nonce != null ? nonce : "") + "\n" +
                (bodyDigest != null ? bodyDigest : EMPTY_BODY_DIGEST);
    }

    public static String computeBodyDigest(byte[] data) {
        return computeSha256Hex(data);
    }

    public String sign(String method, String path, String query,
                       String subject, String userId, String tenantId,
                       String roles, long timestamp, String nonce, String bodyDigest) {
        return sign(activeKeyId, method, path, query, subject, userId, tenantId, roles, timestamp, nonce, bodyDigest);
    }

    public String sign(String keyId, String method, String path, String query,
                       String subject, String userId, String tenantId,
                       String roles, long timestamp, String nonce, String bodyDigest) {
        String secret = keyRing.get(keyId);
        if (secret == null) {
            throw new IllegalStateException("keyId not found in keyRing: " + keyId);
        }
        String canonical = buildCanonicalString(method, path, query, subject, userId, tenantId, roles, timestamp, nonce, bodyDigest);
        return computeHmacHex(canonical, secret);
    }

    public boolean verify(String keyId, long timestamp, String nonce, String signature,
                          String method, String path, String query,
                          String subject, String userId, String tenantId,
                          String roles, String bodyDigest) {
        if (keyId == null || signature == null || nonce == null) {
            return false;
        }

        // 1. Reject expired or skewed signatures
        long now = System.currentTimeMillis();
        if (Math.abs(now - timestamp) > maxSkewMs) {
            return false;
        }

        // 2. Reject replayed nonces
        cleanupStaleNonces(now);
        if (nonceCache.putIfAbsent(nonce, timestamp) != null) {
            return false; // Replay detected!
        }

        // 3. Lookup signing secret for keyId (supports key rotation)
        String secret = keyRing.get(keyId);
        if (secret == null) {
            return false;
        }

        // 4. Reconstruct canonical string and compute HMAC
        String canonical = buildCanonicalString(method, path, query, subject, userId, tenantId, roles, timestamp, nonce, bodyDigest);
        String expectedSignature = computeHmacHex(canonical, secret);

        // 5. Constant-time equality comparison to prevent timing side-channel attacks
        return MessageDigest.isEqual(
                signature.getBytes(StandardCharsets.UTF_8),
                expectedSignature.getBytes(StandardCharsets.UTF_8)
        );
    }

    private void cleanupStaleNonces(long now) {
        if (nonceCache.size() > 5000) {
            nonceCache.entrySet().removeIf(entry -> (now - entry.getValue()) > maxSkewMs);
        }
    }

    private String computeHmacHex(String data, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC-SHA256 computation failed", e);
        }
    }
}
