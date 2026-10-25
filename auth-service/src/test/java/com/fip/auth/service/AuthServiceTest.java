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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock
    private UserPersistencePort userPersistencePort;

    @Mock
    private RefreshTokenPersistencePort refreshTokenPersistencePort;

    @Mock
    private EventPublishingPort eventPublishingPort;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private ObjectMapper objectMapper;

    private AuthService authService;

    private final String jwtSecret = "1234567890123456789012345678901234567890123456789012345678901234";

    @BeforeEach
    void setUp() {
        authService = new AuthService(
                userPersistencePort,
                refreshTokenPersistencePort,
                eventPublishingPort,
                passwordEncoder,
                objectMapper,
                jwtSecret
        );
    }

    @Test
    @DisplayName("register: public self-service registration must allocate a fresh unique tenant UUID and force ROLE_USER")
    void register_publicRegistration_shouldGenerateUniqueTenantAndForceRoleUser() throws Exception {
        // Client does not supply tenantId (public self-registration)
        CreateUserUseCase.Command cmd = new CreateUserUseCase.Command(
                "john_doe", "Password@123", "john@fip.com", Set.of("ROLE_ADMIN"), null
        );
        when(userPersistencePort.findByUsername("john_doe")).thenReturn(Optional.empty());
        when(userPersistencePort.findByEmail("john@fip.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Password@123")).thenReturn("hashed_pwd");

        when(userPersistencePort.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        User result = authService.register(cmd);

        assertThat(result.getUsername()).isEqualTo("john_doe");
        assertThat(result.getRoles()).containsExactly("ROLE_USER");
        assertThat(result.getTenantId()).isNotBlank();
        // Must be a valid UUID
        assertThat(UUID.fromString(result.getTenantId())).isNotNull();
        verify(userPersistencePort).save(any(User.class));
        verify(eventPublishingPort).publish(any(CanonicalEvent.class));
    }

    @Test
    @DisplayName("register: client-specified tenantId without invitation token must be rejected (Tenant Takeover Prevention)")
    void register_arbitraryTenantWithoutToken_shouldThrowBadRequest() {
        CreateUserUseCase.Command cmd = new CreateUserUseCase.Command(
                "attacker", "Password@123", "attacker@evil.com", Set.of("ROLE_USER"), "victim-enterprise-tenant"
        );
        when(userPersistencePort.findByUsername("attacker")).thenReturn(Optional.empty());
        when(userPersistencePort.findByEmail("attacker@evil.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.register(cmd))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Client-specified tenantId is prohibited during public registration");
    }

    @Test
    @DisplayName("register: valid invitation token must permit joining the specified tenant")
    void register_withValidInvitationToken_shouldSucceed() throws Exception {
        String targetTenant = "enterprise-corp-42";
        String token = authService.createTenantInvitationToken(targetTenant, 3600000L);

        CreateUserUseCase.Command cmd = new CreateUserUseCase.Command(
                "invited_user", "Password@123", "invited@enterprise.com", Set.of("ROLE_USER"), targetTenant, token
        );
        when(userPersistencePort.findByUsername("invited_user")).thenReturn(Optional.empty());
        when(userPersistencePort.findByEmail("invited@enterprise.com")).thenReturn(Optional.empty());
        when(passwordEncoder.encode("Password@123")).thenReturn("hashed_pwd");
        when(userPersistencePort.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        User result = authService.register(cmd);

        assertThat(result.getUsername()).isEqualTo("invited_user");
        assertThat(result.getTenantId()).isEqualTo(targetTenant);
        assertThat(result.getRoles()).containsExactly("ROLE_USER");
    }

    @Test
    @DisplayName("register: tampered invitation token must be rejected with BadRequestException")
    void register_withTamperedInvitationToken_shouldThrowBadRequest() {
        String targetTenant = "enterprise-corp-42";
        String token = authService.createTenantInvitationToken(targetTenant, 3600000L);
        String tamperedToken = token + "corrupted";

        CreateUserUseCase.Command cmd = new CreateUserUseCase.Command(
                "attacker", "Password@123", "attacker@enterprise.com", Set.of("ROLE_USER"), targetTenant, tamperedToken
        );
        when(userPersistencePort.findByUsername("attacker")).thenReturn(Optional.empty());
        when(userPersistencePort.findByEmail("attacker@enterprise.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.register(cmd))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Invalid or expired invitation token");
    }

    @Test
    @DisplayName("register: concurrent registrations must each receive distinct tenant UUIDs")
    void register_concurrentRegistrations_shouldEnsureUniqueTenantUUIDs() throws Exception {
        when(userPersistencePort.findByUsername(any())).thenReturn(Optional.empty());
        when(userPersistencePort.findByEmail(any())).thenReturn(Optional.empty());
        when(passwordEncoder.encode(any())).thenReturn("hashed_pwd");
        when(userPersistencePort.save(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        Set<String> generatedTenants = java.util.concurrent.ConcurrentHashMap.newKeySet();
        int concurrency = 10;
        java.util.concurrent.ExecutorService executor = java.util.concurrent.Executors.newFixedThreadPool(concurrency);
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(concurrency);

        for (int i = 0; i < concurrency; i++) {
            final int index = i;
            executor.submit(() -> {
                try {
                    User user = authService.register(new CreateUserUseCase.Command(
                            "user_" + index, "Password@123", "user" + index + "@fip.com", null, null
                    ));
                    generatedTenants.add(user.getTenantId());
                } finally {
                    latch.countDown();
                }
            });
        }

        latch.await(5, java.util.concurrent.TimeUnit.SECONDS);
        executor.shutdown();

        assertThat(generatedTenants).hasSize(concurrency);
    }

    @Test
    @DisplayName("register should throw BadRequestException if username already exists")
    void register_duplicateUsername_shouldThrow() {
        CreateUserUseCase.Command cmd = new CreateUserUseCase.Command(
                "existing_user", "Password@123", "unique@fip.com", Set.of("ROLE_USER"), "tenant-1"
        );
        User existing = User.createNew("existing_user", "h", "old@fip.com", Set.of("ROLE_USER"), "tenant-1");
        when(userPersistencePort.findByUsername("existing_user")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> authService.register(cmd))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Username already exists");
    }

    @Test
    @DisplayName("authenticate should succeed with valid credentials and return access and refresh tokens")
    void authenticate_validCredentials_shouldSucceed() throws Exception {
        AuthenticateUserUseCase.Command cmd = new AuthenticateUserUseCase.Command("john_doe", "Password@123");
        User user = User.createNew("john_doe", "hashed_pwd", "john@fip.com", Set.of("ROLE_ANALYST"), "tenant-1");

        when(userPersistencePort.findByUsername("john_doe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("Password@123", "hashed_pwd")).thenReturn(true);
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        AuthenticateUserUseCase.Response response = authService.authenticate(cmd);

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        assertThat(response.username()).isEqualTo("john_doe");
        verify(refreshTokenPersistencePort).save(any(RefreshToken.class));
        verify(eventPublishingPort).publish(any(CanonicalEvent.class));
    }

    @Test
    @DisplayName("authenticate should throw UnauthorizedException on wrong password")
    void authenticate_wrongPassword_shouldThrow() {
        AuthenticateUserUseCase.Command cmd = new AuthenticateUserUseCase.Command("john_doe", "WrongPass");
        User user = User.createNew("john_doe", "hashed_pwd", "john@fip.com", Set.of("ROLE_USER"), "tenant-1");

        when(userPersistencePort.findByUsername("john_doe")).thenReturn(Optional.of(user));
        when(passwordEncoder.matches("WrongPass", "hashed_pwd")).thenReturn(false);

        assertThatThrownBy(() -> authService.authenticate(cmd))
                .isInstanceOf(UnauthorizedException.class)
                .hasMessageContaining("Invalid username or password");
    }

    @Test
    @DisplayName("refreshToken should rotate refresh token and issue new access token")
    void refreshToken_validToken_shouldRotate() {
        UUID userId = UUID.randomUUID();
        RefreshToken oldToken = RefreshToken.create(userId, "valid-token-hash", 30);
        User user = User.createNew("john_doe", "h", "john@fip.com", Set.of("ROLE_USER"), "tenant-1");

        when(refreshTokenPersistencePort.findByTokenHash("valid-token-hash")).thenReturn(Optional.of(oldToken));
        when(userPersistencePort.findById(userId)).thenReturn(Optional.of(user));

        RefreshTokenUseCase.Response response = authService.refreshToken(new RefreshTokenUseCase.Command("valid-token-hash"));

        assertThat(response.accessToken()).isNotBlank();
        assertThat(response.refreshToken()).isNotBlank();
        verify(refreshTokenPersistencePort).revoke(oldToken.getId());
        verify(refreshTokenPersistencePort).save(any(RefreshToken.class));
    }
}
