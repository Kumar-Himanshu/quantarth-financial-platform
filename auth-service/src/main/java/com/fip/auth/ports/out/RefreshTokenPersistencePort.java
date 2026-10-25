package com.fip.auth.ports.out;

import com.fip.auth.domain.model.RefreshToken;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenPersistencePort {
    RefreshToken save(RefreshToken token);

    Optional<RefreshToken> findByTokenHash(String tokenHash);

    void revoke(UUID tokenId);

    void revokeAllByUserId(UUID userId);
}
