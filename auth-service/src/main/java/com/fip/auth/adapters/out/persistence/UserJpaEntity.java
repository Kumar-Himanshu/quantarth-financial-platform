package com.fip.auth.adapters.out.persistence;

import com.fip.common.model.AuditModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.JoinTable;
import jakarta.persistence.ManyToMany;
import jakarta.persistence.Table;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

@Entity
@Table(name = "users")
public class UserJpaEntity extends AuditModel {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "username", nullable = false, unique = true, length = 50)
    private String username;

    @Column(name = "password", nullable = false)
    private String password;

    @Column(name = "email", nullable = false, unique = true, length = 100)
    private String email;

    @Column(name = "status", nullable = false, length = 20)
    private String status = "ACTIVE";

    @Column(name = "tenant_id", nullable = false, length = 50)
    private String tenantId = "default";

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "user_roles",
            joinColumns = @JoinColumn(name = "user_id"),
            inverseJoinColumns = @JoinColumn(name = "role_id")
    )
    private Set<RoleJpaEntity> roles = new HashSet<>();

    public UserJpaEntity() {
    }

    public UserJpaEntity(UUID id, String username, String password, String email, String status, String tenantId, Set<RoleJpaEntity> roles) {
        this.id = id;
        this.username = username;
        this.password = password;
        this.email = email;
        this.status = status != null ? status : "ACTIVE";
        this.tenantId = tenantId != null ? tenantId : "default";
        this.roles = roles != null ? roles : new HashSet<>();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getUsername() { return username; }
    public void setUsername(String username) { this.username = username; }

    public String getPassword() { return password; }
    public void setPassword(String password) { this.password = password; }

    public String getEmail() { return email; }
    public void setEmail(String email) { this.email = email; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }

    public Set<RoleJpaEntity> getRoles() { return roles; }
    public void setRoles(Set<RoleJpaEntity> roles) { this.roles = roles; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String username;
        private String password;
        private String email;
        private String status = "ACTIVE";
        private String tenantId = "default";
        private Set<RoleJpaEntity> roles = new HashSet<>();

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder username(String username) { this.username = username; return this; }
        public Builder password(String password) { this.password = password; return this; }
        public Builder email(String email) { this.email = email; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder tenantId(String tenantId) { this.tenantId = tenantId; return this; }
        public Builder roles(Set<RoleJpaEntity> roles) {
            this.roles = roles != null ? roles : new HashSet<>();
            return this;
        }

        public UserJpaEntity build() {
            return new UserJpaEntity(id, username, password, email, status, tenantId, roles);
        }
    }
}
