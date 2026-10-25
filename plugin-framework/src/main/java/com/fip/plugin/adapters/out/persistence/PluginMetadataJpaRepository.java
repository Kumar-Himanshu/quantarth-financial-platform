package com.fip.plugin.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface PluginMetadataJpaRepository extends JpaRepository<PluginMetadataJpaEntity, UUID> {
    Optional<PluginMetadataJpaEntity> findByNameAndSoftDeleteFalse(String name);
}
