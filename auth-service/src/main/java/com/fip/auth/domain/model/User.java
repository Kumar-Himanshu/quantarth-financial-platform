package com.fip.auth.domain.model;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;

public class User {
    private final UUID id;
    private final String username;
    private final String password; // BCrypt hashed
    private final String email;
    private final String status;
    private final String tenantId;
    private final Set<String> roles;
    private final Set<String> permissions;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public User(UUID id, String username, String password, String email, String status,
                String tenantId, Set<String> roles, Set<String> permissions,
                LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.email = email;
        this.status = status;
        this.tenantId = tenantId;
        this.roles = roles;
        this.permissions = permissions;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getUsername() { return username; }
    public String getPassword() { return password; }
    public String getEmail() { return email; }
    public String getStatus() { return status; }
    public String getTenantId() { return tenantId; }
    public Set<String> getRoles() { return roles; }
    public Set<String> getPermissions() { return permissions; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String username;
        private String password;
        private String email;
        private String status;
        private String tenantId;
        private Set<String> roles;
        private Set<String> permissions;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder roles(Set<String> roles) { this.roles = roles; return this; }
        public Builder permissions(Set<String> permissions) { this.permissions = permissions; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public User build() {
            return new User(id, username, password, email, status, tenantId, roles, permissions, createdAt, updatedAt);
        }
    }

    public static User createNew(String username, String hashedPassword, String email, Set<String> roles, String tenantId) {
        return User.builder()
                .id(UUID.randomUUID())
                .username(username)
                .password(hashedPassword)
                .email(email)
                .status("ACTIVE")
                .tenantId(tenantId != null ? tenantId : "default")
                .roles(roles != null && !roles.isEmpty() ? roles : Collections.singleton("ROLE_USER"))
                .permissions(Collections.emptySet())
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
