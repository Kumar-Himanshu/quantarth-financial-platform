package com.fip.auth.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserJpaRepository extends JpaRepository<UserJpaEntity, UUID> {
    Optional<UserJpaEntity> findByUsernameAndSoftDeleteFalse(String username);

    Optional<UserJpaEntity> findByEmailAndSoftDeleteFalse(String email);
}
