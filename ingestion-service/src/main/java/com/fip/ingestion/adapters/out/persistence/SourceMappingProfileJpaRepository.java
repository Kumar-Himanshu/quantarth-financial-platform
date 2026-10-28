package com.fip.ingestion.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SourceMappingProfileJpaRepository extends JpaRepository<SourceMappingProfileJpaEntity, UUID> {

    Optional<SourceMappingProfileJpaEntity> findByProfileIdAndTenantId(UUID profileId, String tenantId);

    Optional<SourceMappingProfileJpaEntity> findByTenantIdAndName(String tenantId, String name);

    List<SourceMappingProfileJpaEntity> findAllByTenantIdAndIsActiveTrue(String tenantId);
}
