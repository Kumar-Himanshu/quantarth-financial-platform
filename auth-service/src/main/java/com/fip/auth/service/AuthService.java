package com.fip.auth.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.auth.domain.model.RefreshToken;
import com.fip.auth.domain.model.User;
import com.fip.auth.ports.in.AuthenticateUserUseCase;
import com.fip.auth.ports.in.CreateUserUseCase;
import com.fip.auth.ports.in.RefreshTokenUseCase;
import com.fip.auth.ports.out.EventPublishingPort;
import com.fip.auth.ports.out.RefreshTokenPersistencePort;
import com.fip.auth.ports.out.UserPersistencePort;
import com.fip.common.exception.BadRequestException;
import com.fip.common.exception.UnauthorizedException;
import com.fip.shared.events.CanonicalEvent;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.crypto.Mac;
import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Date;
import java.util.HexFormat;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Service
@Transactional
public class AuthService implements CreateUserUseCase, AuthenticateUserUseCase, RefreshTokenUseCase {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final long ACCESS_TOKEN_VALIDITY_MS = 3600000L; // 1 hour
    private static final long REFRESH_TOKEN_VALIDITY_DAYS = 30L; // 30 days

    private final UserPersistencePort userPersistencePort;
    private final RefreshTokenPersistencePort refreshTokenPersistencePort;
    private final EventPublishingPort eventPublishingPort;
    private final PasswordEncoder passwordEncoder;
    private final ObjectMapper objectMapper;
    private final String jwtSecret;

    public AuthService(UserPersistencePort userPersistencePort,
                       RefreshTokenPersistencePort refreshTokenPersistencePort,
                       EventPublishingPort eventPublishingPort,
                       PasswordEncoder passwordEncoder,
                       ObjectMapper objectMapper,
                       @Value("${jwt.secret}") String jwtSecret) {
        this.userPersistencePort = userPersistencePort;
        this.refreshTokenPersistencePort = refreshTokenPersistencePort;
        this.eventPublishingPort = eventPublishingPort;
        this.passwordEncoder = passwordEncoder;
        this.objectMapper = objectMapper;
        this.jwtSecret = jwtSecret;
    }

    @jakarta.annotation.PostConstruct
    public void validateProductionSecrets() {
        if (jwtSecret == null || jwtSecret.length() < 32) {
            throw new IllegalStateException("FATAL: JWT_SECRET must be at least 256 bits (32 bytes) long.");
        }
        String activeProfile = System.getProperty("spring.profiles.active", System.getenv("SPRING_PROFILES_ACTIVE"));
        if (("prod".equalsIgnoreCase(activeProfile) || "production".equalsIgnoreCase(activeProfile))
                && jwtSecret.contains("9a2f3f4g5h6j7k8l9m0n1b2v3c4x5z6p7q8w9e0r1t2y3u4i5o6p7a8s9d0f1g2h")) {
            throw new IllegalStateException("FATAL: Insecure default sample JWT_SECRET is strictly forbidden in production!");
        }
    }

    @Override
    public User register(CreateUserUseCase.Command command) {
        if (userPersistencePort.findByUsername(command.username()).isPresent()) {
            throw new BadRequestException("Username already exists: " + command.username());
        }
        if (userPersistencePort.findByEmail(command.email()).isPresent()) {
            throw new BadRequestException("Email already exists: " + command.email());
        }

        // SECURITY (Priority 1): Strict Tenant Registration Hardening
        // 1. Public self-registration ALWAYS generates a fresh, unique tenant UUID.
        // 2. Client-specified tenant IDs are rejected unless verified through a cryptographically signed invitation token.
        String tenantId;
        if (command.tenantId() != null && !command.tenantId().trim().isEmpty()) {
            if (command.invitationToken() == null || command.invitationToken().trim().isEmpty()) {
                log.warn("TENANT TAKEOVER ATTEMPT: Public registration attempt with unverified tenant ID [{}] by [{}]",
                        command.tenantId(), command.username());
                throw new BadRequestException("Client-specified tenantId is prohibited during public registration. " +
                        "A dedicated tenant UUID is automatically assigned, or a verified invitation token must be provided.");
            }
            if (!validateInvitationToken(command.invitationToken(), command.tenantId().trim())) {
                log.warn("INVALID INVITATION TOKEN: Rejected registration into tenant [{}] with token [{}]",
                        command.tenantId(), command.invitationToken());
                throw new BadRequestException("Invalid or expired invitation token for tenant: " + command.tenantId());
            }
            tenantId = command.tenantId().trim();
        } else {
            // New tenant registration: Always generate a new tenant UUID
            tenantId = UUID.randomUUID().toString();
        }

        // SECURITY: Public registration unconditionally assigns ROLE_USER only
        Set<String> assignedRoles = Set.of("ROLE_USER");

        String hashedPassword = passwordEncoder.encode(command.password());
        User user = User.createNew(
                command.username(),
                hashedPassword,
                command.email(),
                assignedRoles,
                tenantId
        );
        User saved = userPersistencePort.save(user);
        log.info("Registered user [{}] with id [{}] under tenant [{}]", saved.getUsername(), saved.getId(), saved.getTenantId());

        // Publish UserRegistered event
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "userId", saved.getId(),
                    "username", saved.getUsername(),
                    "tenantId", saved.getTenantId(),
                    "roles", saved.getRoles()
            ));
            CanonicalEvent event = CanonicalEvent.create("UserRegistered", "auth-service", payload);
            eventPublishingPort.publish(event);
        } catch (Exception e) {
            log.error("Failed to publish UserRegistered event for user: {}", saved.getUsername(), e);
        }

        return saved;
    }

    @Override
    public String createTenantInvitationToken(String tenantId, long validityMs) {
        long expiry = System.currentTimeMillis() + validityMs;
        String raw = tenantId + ":" + expiry;
        String signature = computeHmacHex(raw, jwtSecret);
        return Base64.getUrlEncoder().withoutPadding().encodeToString((raw + ":" + signature).getBytes(StandardCharsets.UTF_8));
    }

    private boolean validateInvitationToken(String token, String expectedTenantId) {
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(token);
            String payload = new String(decoded, StandardCharsets.UTF_8);
            String[] parts = payload.split(":");
            if (parts.length != 3) {
                return false;
            }
            String tenantId = parts[0];
            long expiry = Long.parseLong(parts[1]);
            String signature = parts[2];

            if (!tenantId.equals(expectedTenantId)) {
                return false;
            }
            if (System.currentTimeMillis() > expiry) {
                return false;
            }
            String computedSig = computeHmacHex(tenantId + ":" + expiry, jwtSecret);
            return MessageDigest.isEqual(signature.getBytes(StandardCharsets.UTF_8), computedSig.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            return false;
        }
    }

    private String computeHmacHex(String data, String key) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] raw = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(raw);
        } catch (Exception e) {
            throw new RuntimeException("HMAC computation failed", e);
        }
    }

    @Override
    public AuthenticateUserUseCase.Response authenticate(AuthenticateUserUseCase.Command command) {
        User user = userPersistencePort.findByUsername(command.username())
                .orElseThrow(() -> new UnauthorizedException("Invalid username or password"));

        if (!passwordEncoder.matches(command.password(), user.getPassword())) {
            log.warn("Authentication failed for user: {}", command.username());
            throw new UnauthorizedException("Invalid username or password");
        }

        String accessToken = generateAccessToken(user);
        String refreshTokenString = UUID.randomUUID().toString();

        RefreshToken refreshToken = RefreshToken.create(user.getId(), refreshTokenString, REFRESH_TOKEN_VALIDITY_DAYS);
        refreshTokenPersistencePort.save(refreshToken);

        // Publish UserLoggedIn event
        try {
            String payload = objectMapper.writeValueAsString(Map.of(
                    "userId", user.getId(),
                    "username", user.getUsername(),
                    "tenantId", user.getTenantId()
            ));
            CanonicalEvent event = CanonicalEvent.create("UserLoggedIn", "auth-service", payload);
            eventPublishingPort.publish(event);
        } catch (Exception e) {
            log.error("Failed to publish UserLoggedIn event for user: {}", user.getUsername(), e);
        }

        return AuthenticateUserUseCase.Response.of(
                accessToken,
                refreshTokenString,
                ACCESS_TOKEN_VALIDITY_MS / 1000,
                user.getUsername(),
                user.getTenantId(),
                user.getRoles(),
                user.getPermissions()
        );
    }

    @Override
    public RefreshTokenUseCase.Response refreshToken(RefreshTokenUseCase.Command command) {
        RefreshToken token = refreshTokenPersistencePort.findByTokenHash(command.refreshToken())
                .orElseThrow(() -> new UnauthorizedException("Invalid or revoked refresh token"));

        if (token.isExpired()) {
            refreshTokenPersistencePort.revoke(token.getId());
            throw new UnauthorizedException("Refresh token has expired");
        }

        User user = userPersistencePort.findById(token.getUserId())
                .orElseThrow(() -> new UnauthorizedException("User associated with refresh token not found"));

        // Revoke the old refresh token (Token Rotation)
        refreshTokenPersistencePort.revoke(token.getId());

        // Issue new access and refresh tokens
        String newAccessToken = generateAccessToken(user);
        String newRefreshTokenString = UUID.randomUUID().toString();
        RefreshToken newRefreshToken = RefreshToken.create(user.getId(), newRefreshTokenString, REFRESH_TOKEN_VALIDITY_DAYS);
        refreshTokenPersistencePort.save(newRefreshToken);

        return RefreshTokenUseCase.Response.of(
                newAccessToken,
                newRefreshTokenString,
                ACCESS_TOKEN_VALIDITY_MS / 1000,
                user.getUsername(),
                user.getRoles(),
                user.getPermissions()
        );
    }

    private String generateAccessToken(User user) {
        SecretKey key = Keys.hmacShaKeyFor(jwtSecret.getBytes(StandardCharsets.UTF_8));
        return Jwts.builder()
                .issuer(com.fip.shared.security.GatewaySecurityConstants.JWT_ISSUER)
                .audience().add(com.fip.shared.security.GatewaySecurityConstants.JWT_AUDIENCE).and()
                .subject(user.getUsername())
                .claim("userId", user.getId().toString())
                .claim("tenantId", user.getTenantId())
                .claim("roles", user.getRoles())
                .claim("permissions", user.getPermissions())
                .issuedAt(new Date())
                .expiration(new Date(System.currentTimeMillis() + ACCESS_TOKEN_VALIDITY_MS))
                .signWith(key)
                .compact();
    }
}
