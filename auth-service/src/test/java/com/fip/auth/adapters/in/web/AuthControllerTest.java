package com.fip.auth.adapters.in.web;

import com.fip.auth.domain.model.User;
import com.fip.auth.ports.in.AuthenticateUserUseCase;
import com.fip.auth.ports.in.CreateUserUseCase;
import com.fip.auth.ports.in.RefreshTokenUseCase;
import com.fip.common.response.StandardResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthControllerTest {

    @Mock
    private CreateUserUseCase createUserUseCase;

    @Mock
    private AuthenticateUserUseCase authenticateUserUseCase;

    @Mock
    private RefreshTokenUseCase refreshTokenUseCase;

    @InjectMocks
    private AuthController authController;

    private CreateUserUseCase.Command registerCommand;
    private AuthenticateUserUseCase.Command loginCommand;
    private RefreshTokenUseCase.Command refreshCommand;

    @BeforeEach
    void setUp() {
        registerCommand = new CreateUserUseCase.Command(
                "priya_architect",
                "SecureP@ss123",
                "priya@fip.internal",
                Set.of("ROLE_ANALYST"),
                "tenant-alpha"
        );

        loginCommand = new AuthenticateUserUseCase.Command(
                "priya_architect",
                "SecureP@ss123"
        );

        refreshCommand = new RefreshTokenUseCase.Command("sample-refresh-token-uuid");
    }

    @Test
    @DisplayName("register should return 201 Created and StandardResponse containing registered user")
    void register_shouldReturnCreatedStatus() {
        User mockUser = User.createNew(
                registerCommand.username(),
                "hashed_pwd",
                registerCommand.email(),
                registerCommand.roles(),
                registerCommand.tenantId()
        );
        when(createUserUseCase.register(any(CreateUserUseCase.Command.class))).thenReturn(mockUser);

        ResponseEntity<StandardResponse<User>> response = authController.register(registerCommand);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData().getUsername()).isEqualTo("priya_architect");
        assertThat(response.getBody().getData().getRoles()).contains("ROLE_ANALYST");
        verify(createUserUseCase).register(any(CreateUserUseCase.Command.class));
    }

    @Test
    @DisplayName("login should return 200 OK and JWT access token response")
    void login_shouldReturnSuccess() {
        AuthenticateUserUseCase.Response expectedResponse = AuthenticateUserUseCase.Response.of(
                "jwt.access.token",
                "refresh-token-uuid",
                3600L,
                "priya_architect",
                "tenant-alpha",
                Set.of("ROLE_ANALYST"),
                Set.of("fip:read", "fip:ai:process")
        );
        when(authenticateUserUseCase.authenticate(any(AuthenticateUserUseCase.Command.class)))
                .thenReturn(expectedResponse);

        ResponseEntity<StandardResponse<AuthenticateUserUseCase.Response>> response = authController.login(loginCommand);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().isSuccess()).isTrue();
        assertThat(response.getBody().getData().accessToken()).isEqualTo("jwt.access.token");
        assertThat(response.getBody().getData().refreshToken()).isEqualTo("refresh-token-uuid");
        verify(authenticateUserUseCase).authenticate(any(AuthenticateUserUseCase.Command.class));
    }

    @Test
    @DisplayName("refresh should rotate tokens and return 200 OK")
    void refresh_shouldReturnRotatedTokens() {
        RefreshTokenUseCase.Response expectedResponse = RefreshTokenUseCase.Response.of(
                "new.jwt.access.token",
                "new-refresh-token-uuid",
                3600L,
                "priya_architect",
                Set.of("ROLE_ANALYST"),
                Set.of("fip:read", "fip:ai:process")
        );
        when(refreshTokenUseCase.refreshToken(any(RefreshTokenUseCase.Command.class)))
                .thenReturn(expectedResponse);

        ResponseEntity<StandardResponse<RefreshTokenUseCase.Response>> response = authController.refresh(refreshCommand);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().getData().accessToken()).isEqualTo("new.jwt.access.token");
        assertThat(response.getBody().getData().refreshToken()).isEqualTo("new-refresh-token-uuid");
        verify(refreshTokenUseCase).refreshToken(any(RefreshTokenUseCase.Command.class));
    }
}
