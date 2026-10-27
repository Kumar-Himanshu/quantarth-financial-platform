package com.fip.eventengine.adapters.out.persistence;

import jakarta.persistence.LockModeType;
import jakarta.persistence.QueryHint;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.jpa.repository.QueryHints;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Repository
public interface EventOutboxJpaRepository extends JpaRepository<EventOutboxJpaEntity, UUID> {

    List<EventOutboxJpaEntity> findByStatusOrderByCreatedAtAsc(String status, Pageable pageable);

    List<EventOutboxJpaEntity> findByStatus(String status);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @QueryHints({@QueryHint(name = "jakarta.persistence.lock.timeout", value = "-2")})
    @Query("SELECT e FROM EventOutboxJpaEntity e WHERE " +
           "(e.status = 'PENDING' AND (e.nextAttemptAt IS NULL OR e.nextAttemptAt <= :now) AND e.lockedAt IS NULL) " +
           "OR (e.status = 'PROCESSING' AND e.lockedAt < :staleThreshold) " +
           "ORDER BY e.createdAt ASC")
    List<EventOutboxJpaEntity> findClaimableRecords(@Param("now") LocalDateTime now,
                                                   @Param("staleThreshold") LocalDateTime staleThreshold,
                                                   Pageable pageable);
}
