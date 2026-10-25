package com.fip.auth.ports.in;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;

public interface AuthenticateUserUseCase {
    Response authenticate(Command command);

    record Command(
            @NotBlank(message = "Username is required")
            String username,

            @NotBlank(message = "Password is required")
            String password
    ) {
    }

    record Response(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            String username,
            String tenantId,
            Set<String> roles,
            Set<String> permissions
    ) {
        public static Response of(String accessToken, String refreshToken, long expiresIn, String username, String tenantId, Set<String> roles, Set<String> permissions) {
            return new Response(accessToken, refreshToken, "Bearer", expiresIn, username, tenantId, roles, permissions);
        }
    }
}
