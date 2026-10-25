package com.fip.auth.adapters.out.persistence;

import com.fip.auth.domain.model.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.Named;

import java.util.Collections;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;

@Mapper(componentModel = "spring")
public interface UserMapper {

    @Mapping(target = "roles", source = "roles", qualifiedByName = "mapRolesToNames")
    @Mapping(target = "permissions", source = "roles", qualifiedByName = "mapRolesToPermissions")
    User toDomain(UserJpaEntity entity);

    @Named("mapRolesToNames")
    default Set<String> mapRolesToNames(Set<RoleJpaEntity> roles) {
        if (roles == null) {
            return Collections.emptySet();
        }
        return roles.stream()
                .map(RoleJpaEntity::getName)
                .collect(Collectors.toSet());
    }

    @Named("mapRolesToPermissions")
    default Set<String> mapRolesToPermissions(Set<RoleJpaEntity> roles) {
        if (roles == null) {
            return Collections.emptySet();
        }
        Set<String> permissions = new HashSet<>();
        for (RoleJpaEntity role : roles) {
            if (role.getPermissions() != null) {
                for (PermissionJpaEntity perm : role.getPermissions()) {
                    permissions.add(perm.getName());
                }
            }
        }
        return permissions;
    }
}
