package com.fip.auth.adapters.in.web;

import com.fip.auth.domain.model.User;
import com.fip.auth.ports.in.AuthenticateUserUseCase;
import com.fip.auth.ports.in.CreateUserUseCase;
import com.fip.auth.ports.in.RefreshTokenUseCase;
import com.fip.common.response.StandardResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/auth")
@Tag(name = "Authentication Management", description = "Endpoints for user registration, authentication, RBAC, and JWT refresh token rotation")
public class AuthController {

    private final CreateUserUseCase createUserUseCase;
    private final AuthenticateUserUseCase authenticateUserUseCase;
    private final RefreshTokenUseCase refreshTokenUseCase;

    public AuthController(CreateUserUseCase createUserUseCase,
                          AuthenticateUserUseCase authenticateUserUseCase,
                          RefreshTokenUseCase refreshTokenUseCase) {
        this.createUserUseCase = createUserUseCase;
        this.authenticateUserUseCase = authenticateUserUseCase;
        this.refreshTokenUseCase = refreshTokenUseCase;
    }

    @PostMapping("/register")
    @Operation(
            summary = "Register a new user",
            description = "Creates a new user record with assigned RBAC roles and tenant context, and emits a Canonical UserRegistered event.",
            responses = {
                    @ApiResponse(responseCode = "201", description = "User successfully registered"),
                    @ApiResponse(responseCode = "400", description = "Validation failure or duplicate username/email",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<User>> register(@Valid @RequestBody CreateUserUseCase.Command command) {
        User registered = createUserUseCase.register(command);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(StandardResponse.success(registered, "User successfully registered"));
    }

    @PostMapping("/login")
    @Operation(
            summary = "Authenticate user credentials",
            description = "Validates username and password, returns an RFC-compliant JWT Access Token (with RBAC roles & permissions) and a Refresh Token.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Authentication successful"),
                    @ApiResponse(responseCode = "401", description = "Invalid credentials",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<AuthenticateUserUseCase.Response>> login(
            @Valid @RequestBody AuthenticateUserUseCase.Command command) {
        AuthenticateUserUseCase.Response authResponse = authenticateUserUseCase.authenticate(command);
        return ResponseEntity.ok(StandardResponse.success(authResponse, "Authentication successful"));
    }

    @PostMapping("/refresh")
    @Operation(
            summary = "Rotate refresh token and issue new access token",
            description = "Validates the existing refresh token, revokes it (rotation pattern), and generates a new access token and refresh token.",
            responses = {
                    @ApiResponse(responseCode = "200", description = "Tokens successfully rotated"),
                    @ApiResponse(responseCode = "401", description = "Expired or invalid refresh token",
                            content = @Content(schema = @Schema(implementation = ProblemDetail.class)))
            }
    )
    public ResponseEntity<StandardResponse<RefreshTokenUseCase.Response>> refresh(
            @Valid @RequestBody RefreshTokenUseCase.Command command) {
        RefreshTokenUseCase.Response response = refreshTokenUseCase.refreshToken(command);
        return ResponseEntity.ok(StandardResponse.success(response, "Token refresh successful"));
    }
}
