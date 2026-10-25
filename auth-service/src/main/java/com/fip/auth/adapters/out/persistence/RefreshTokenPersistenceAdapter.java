package com.fip.auth.adapters.out.persistence;

import com.fip.auth.domain.model.RefreshToken;
import com.fip.auth.ports.out.RefreshTokenPersistencePort;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class RefreshTokenPersistenceAdapter implements RefreshTokenPersistencePort {

    private final RefreshTokenJpaRepository tokenRepository;
    private final UserJpaRepository userRepository;

    public RefreshTokenPersistenceAdapter(RefreshTokenJpaRepository tokenRepository,
                                          UserJpaRepository userRepository) {
        this.tokenRepository = tokenRepository;
        this.userRepository = userRepository;
    }

    @Override
    public RefreshToken save(RefreshToken token) {
        UserJpaEntity userEntity = userRepository.findById(token.getUserId())
                .orElseThrow(() -> new IllegalArgumentException("User not found for ID: " + token.getUserId()));

        RefreshTokenJpaEntity entity = RefreshTokenJpaEntity.builder()
                .id(token.getId())
                .user(userEntity)
                .tokenHash(token.getTokenHash())
                .expiresAt(token.getExpiresAt())
                .revoked(token.isRevoked())
                .build();

        RefreshTokenJpaEntity saved = tokenRepository.save(entity);
        return mapToDomain(saved);
    }

    @Override
    public Optional<RefreshToken> findByTokenHash(String tokenHash) {
        return tokenRepository.findByTokenHashAndRevokedFalse(tokenHash)
                .map(this::mapToDomain);
    }

    @Override
    public void revoke(UUID tokenId) {
        tokenRepository.findById(tokenId).ifPresent(entity -> {
            entity.setRevoked(true);
            tokenRepository.save(entity);
        });
    }

    @Override
    public void revokeAllByUserId(UUID userId) {
        tokenRepository.deleteByUserId(userId);
    }

    private RefreshToken mapToDomain(RefreshTokenJpaEntity entity) {
        return RefreshToken.builder()
                .id(entity.getId())
                .userId(entity.getUser().getId())
                .tokenHash(entity.getTokenHash())
                .expiresAt(entity.getExpiresAt())
                .revoked(entity.isRevoked())
                .createdAt(entity.getCreatedAt())
                .build();
    }
}
