package com.fip.auth.adapters.out.persistence;

import com.fip.common.model.AuditModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "refresh_tokens")
public class RefreshTokenJpaEntity extends AuditModel {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private UserJpaEntity user;

    @Column(name = "token_hash", nullable = false, unique = true, length = 255)
    private String tokenHash;

    @Column(name = "expires_at", nullable = false)
    private LocalDateTime expiresAt;

    @Column(name = "revoked", nullable = false)
    private boolean revoked = false;

    public RefreshTokenJpaEntity() {
    }

    public RefreshTokenJpaEntity(UUID id, UserJpaEntity user, String tokenHash, LocalDateTime expiresAt, boolean revoked) {
        this.id = id;
        this.user = user;
        this.tokenHash = tokenHash;
        this.expiresAt = expiresAt;
        this.revoked = revoked;
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public UserJpaEntity getUser() { return user; }
    public void setUser(UserJpaEntity user) { this.user = user; }

    public String getTokenHash() { return tokenHash; }
    public void setTokenHash(String tokenHash) { this.tokenHash = tokenHash; }

    public LocalDateTime getExpiresAt() { return expiresAt; }
    public void setExpiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; }

    public boolean isRevoked() { return revoked; }
    public void setRevoked(boolean revoked) { this.revoked = revoked; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private UserJpaEntity user;
        private String tokenHash;
        private LocalDateTime expiresAt;
        private boolean revoked = false;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder user(UserJpaEntity user) { this.user = user; return this; }
        public Builder tokenHash(String tokenHash) { this.tokenHash = tokenHash; return this; }
        public Builder expiresAt(LocalDateTime expiresAt) { this.expiresAt = expiresAt; return this; }
        public Builder revoked(boolean revoked) { this.revoked = revoked; return this; }

        public RefreshTokenJpaEntity build() {
            return new RefreshTokenJpaEntity(id, user, tokenHash, expiresAt, revoked);
        }
    }
}
