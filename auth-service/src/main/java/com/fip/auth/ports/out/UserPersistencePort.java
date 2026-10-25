package com.fip.auth.ports.out;

import com.fip.auth.domain.model.User;
import java.util.Optional;
import java.util.UUID;

public interface UserPersistencePort {
    User save(User user);

    Optional<User> findById(UUID id);

    Optional<User> findByUsername(String username);

    Optional<User> findByEmail(String email);
}
