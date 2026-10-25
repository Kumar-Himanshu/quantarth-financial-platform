package com.fip.eventengine.adapters.out.persistence;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface FinancialEventJpaRepository extends JpaRepository<FinancialEventJpaEntity, UUID> {

    Optional<FinancialEventJpaEntity> findByEventId(UUID eventId);

    Optional<FinancialEventJpaEntity> findByEventIdAndTenantId(UUID eventId, String tenantId);

    boolean existsByEventId(UUID eventId);

    @Query("SELECT e FROM FinancialEventJpaEntity e WHERE " +
           "e.tenantId = :tenantId AND " +
           "(:eventType IS NULL OR e.eventType = :eventType) AND " +
           "(:eventSource IS NULL OR e.eventSource = :eventSource) " +
           "ORDER BY e.timestamp DESC")
    List<FinancialEventJpaEntity> searchEvents(
            @Param("eventType") String eventType,
            @Param("eventSource") String eventSource,
            @Param("tenantId") String tenantId
    );
}
