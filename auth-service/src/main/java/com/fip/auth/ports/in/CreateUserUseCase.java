package com.fip.auth.ports.in;

import com.fip.auth.domain.model.User;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.util.Set;

public interface CreateUserUseCase {
    User register(Command command);

    String createTenantInvitationToken(String tenantId, long validityMs);

    record Command(
            @NotBlank(message = "Username is required")
            @Size(min = 4, max = 50, message = "Username must be between 4 and 50 characters")
            String username,

            @NotBlank(message = "Password is required")
            @Size(min = 6, message = "Password must be at least 6 characters")
            String password,

            @NotBlank(message = "Email is required")
            @Email(message = "Invalid email format")
            String email,

            Set<String> roles,
            String tenantId,
            String invitationToken
    ) {
        public Command(String username, String password, String email, Set<String> roles, String tenantId) {
            this(username, password, email, roles, tenantId, null);
        }
    }
}
