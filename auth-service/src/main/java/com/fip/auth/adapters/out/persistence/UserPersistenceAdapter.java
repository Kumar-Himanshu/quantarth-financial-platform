package com.fip.auth.adapters.out.persistence;

import com.fip.auth.domain.model.User;
import com.fip.auth.ports.out.UserPersistencePort;
import org.springframework.stereotype.Component;

import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

@Component
public class UserPersistenceAdapter implements UserPersistencePort {

    private final UserJpaRepository userRepository;
    private final RoleJpaRepository roleRepository;
    private final UserMapper userMapper;

    public UserPersistenceAdapter(UserJpaRepository userRepository,
                                  RoleJpaRepository roleRepository,
                                  UserMapper userMapper) {
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
        this.userMapper = userMapper;
    }

    @Override
    public User save(User user) {
        Set<RoleJpaEntity> roleEntities = new HashSet<>();
        if (user.getRoles() != null && !user.getRoles().isEmpty()) {
            for (String roleName : user.getRoles()) {
                roleRepository.findByNameAndSoftDeleteFalse(roleName)
                        .ifPresent(roleEntities::add);
            }
        }
        if (roleEntities.isEmpty()) {
            roleRepository.findByNameAndSoftDeleteFalse("ROLE_USER")
                    .ifPresent(roleEntities::add);
        }

        UserJpaEntity entity = UserJpaEntity.builder()
                .id(user.getId())
                .username(user.getUsername())
                .password(user.getPassword())
                .email(user.getEmail())
                .status(user.getStatus() != null ? user.getStatus() : "ACTIVE")
                .tenantId(user.getTenantId() != null ? user.getTenantId() : "default")
                .roles(roleEntities)
                .build();

        UserJpaEntity saved = userRepository.save(entity);
        return userMapper.toDomain(saved);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return userRepository.findById(id)
                .filter(u -> !u.isSoftDelete())
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByUsername(String username) {
        return userRepository.findByUsernameAndSoftDeleteFalse(username)
                .map(userMapper::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return userRepository.findByEmailAndSoftDeleteFalse(email)
                .map(userMapper::toDomain);
    }
}
