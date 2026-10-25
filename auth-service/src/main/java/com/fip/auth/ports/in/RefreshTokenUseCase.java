package com.fip.auth.ports.in;

import jakarta.validation.constraints.NotBlank;
import java.util.Set;

public interface RefreshTokenUseCase {
    Response refreshToken(Command command);

    record Command(
            @NotBlank(message = "Refresh token is required")
            String refreshToken
    ) {
    }

    record Response(
            String accessToken,
            String refreshToken,
            String tokenType,
            long expiresIn,
            String username,
            Set<String> roles,
            Set<String> permissions
    ) {
        public static Response of(String accessToken, String refreshToken, long expiresIn, String username, Set<String> roles, Set<String> permissions) {
            return new Response(accessToken, refreshToken, "Bearer", expiresIn, username, roles, permissions);
        }
    }
}
