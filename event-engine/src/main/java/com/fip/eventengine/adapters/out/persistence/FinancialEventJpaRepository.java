package com.fip.eventengine.adapters.out.persistence;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
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
           "(:eventSource IS NULL OR e.eventSource = :eventSource OR e.sourceSystem = :eventSource) " +
           "ORDER BY COALESCE(e.occurredAt, e.timestamp) DESC")
    List<FinancialEventJpaEntity> searchEvents(
            @Param("eventType") String eventType,
            @Param("eventSource") String eventSource,
            @Param("tenantId") String tenantId
    );

    @Query("SELECT e FROM FinancialEventJpaEntity e WHERE " +
           "e.tenantId = :tenantId AND " +
           "(e.sourceSystem = :sourceSystem OR e.eventSource = :sourceSystem) AND " +
           "e.idempotencyKey = :idempotencyKey")
    Optional<FinancialEventJpaEntity> findByTenantIdAndSourceSystemAndIdempotencyKey(
            @Param("tenantId") String tenantId,
            @Param("sourceSystem") String sourceSystem,
            @Param("idempotencyKey") String idempotencyKey
    );

    @Query("SELECT e FROM FinancialEventJpaEntity e WHERE " +
           "e.tenantId = :tenantId AND " +
           "(:entityType IS NULL OR e.entityType = :entityType) AND " +
           "(:entityId IS NULL OR e.entityId = :entityId) AND " +
           "(:eventType IS NULL OR e.eventType = :eventType) AND " +
           "(:sourceSystem IS NULL OR e.sourceSystem = :sourceSystem OR e.eventSource = :sourceSystem) AND " +
           "(:fromDate IS NULL OR COALESCE(e.occurredAt, e.timestamp) >= :fromDate) AND " +
           "(:toDate IS NULL OR COALESCE(e.occurredAt, e.timestamp) <= :toDate) " +
           "ORDER BY COALESCE(e.occurredAt, e.timestamp) DESC")
    Page<FinancialEventJpaEntity> searchCanonicalEvents(
            @Param("tenantId") String tenantId,
            @Param("entityType") String entityType,
            @Param("entityId") String entityId,
            @Param("eventType") String eventType,
            @Param("sourceSystem") String sourceSystem,
            @Param("fromDate") LocalDateTime fromDate,
            @Param("toDate") LocalDateTime toDate,
            Pageable pageable
    );

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT e FROM FinancialEventJpaEntity e WHERE " +
           "e.tenantId = :tenantId AND " +
           "e.entityType = 'PAYMENT' AND " +
           "e.entityId = :paymentId")
    Optional<FinancialEventJpaEntity> findPaymentForUpdate(
            @Param("tenantId") String tenantId,
            @Param("paymentId") String paymentId
    );

    @Query("SELECT e FROM FinancialEventJpaEntity e WHERE " +
           "e.tenantId = :tenantId AND " +
           "e.entityType = 'REFUND' AND " +
           "(e.causationId = :paymentId OR e.entityId = :paymentId) " +
           "ORDER BY COALESCE(e.occurredAt, e.timestamp) ASC")
    List<FinancialEventJpaEntity> findRefundsByPaymentId(
            @Param("tenantId") String tenantId,
            @Param("paymentId") String paymentId
    );

    @Query("SELECT e FROM FinancialEventJpaEntity e WHERE " +
           "e.tenantId = :tenantId AND " +
           "e.entityType = 'REFUND' AND " +
           "e.entityId = :refundId " +
           "ORDER BY COALESCE(e.occurredAt, e.timestamp) DESC")
    List<FinancialEventJpaEntity> findRefundsByRefundId(
            @Param("tenantId") String tenantId,
            @Param("refundId") String refundId
    );
}
