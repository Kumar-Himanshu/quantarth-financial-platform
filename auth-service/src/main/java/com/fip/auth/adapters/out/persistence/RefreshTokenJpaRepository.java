package com.fip.auth.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface RefreshTokenJpaRepository extends JpaRepository<RefreshTokenJpaEntity, UUID> {
    Optional<RefreshTokenJpaEntity> findByTokenHashAndRevokedFalse(String tokenHash);
    void deleteByUserId(UUID userId);
}
