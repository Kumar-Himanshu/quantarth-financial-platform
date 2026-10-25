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
@Table(name = "roles")
public class RoleJpaEntity extends AuditModel {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, unique = true, length = 50)
    private String name;

    @Column(name = "description", length = 255)
    private String description;

    @ManyToMany(fetch = FetchType.EAGER)
    @JoinTable(
            name = "role_permissions",
            joinColumns = @JoinColumn(name = "role_id"),
            inverseJoinColumns = @JoinColumn(name = "permission_id")
    )
    private Set<PermissionJpaEntity> permissions = new HashSet<>();

    public RoleJpaEntity() {
    }

    public RoleJpaEntity(UUID id, String name, String description, Set<PermissionJpaEntity> permissions) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.permissions = permissions != null ? permissions : new HashSet<>();
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public Set<PermissionJpaEntity> getPermissions() { return permissions; }
    public void setPermissions(Set<PermissionJpaEntity> permissions) { this.permissions = permissions; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String name;
        private String description;
        private Set<PermissionJpaEntity> permissions = new HashSet<>();

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder permissions(Set<PermissionJpaEntity> permissions) {
            this.permissions = permissions != null ? permissions : new HashSet<>();
            return this;
        }

        public RoleJpaEntity build() {
            return new RoleJpaEntity(id, name, description, permissions);
        }
    }
}
