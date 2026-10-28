package com.fip.ingestion.adapters.out.persistence;

import com.fip.ingestion.domain.model.JobStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionJobJpaRepository extends JpaRepository<IngestionJobJpaEntity, UUID> {

    Optional<IngestionJobJpaEntity> findByJobIdAndTenantId(UUID jobId, String tenantId);

    Page<IngestionJobJpaEntity> findAllByTenantId(String tenantId, Pageable pageable);

    List<IngestionJobJpaEntity> findByTenantId(String tenantId);

    Optional<IngestionJobJpaEntity> findTopByTenantIdAndFileHashOrderByCreatedAtDesc(String tenantId, String fileHash);

    List<IngestionJobJpaEntity> findByStatusInAndHeartbeatAtBefore(Collection<JobStatus> statuses, LocalDateTime threshold);

    @Modifying(clearAutomatically = true)
    @Query("UPDATE IngestionJobJpaEntity j " +
           "SET j.processedRecords = j.processedRecords + :processed, " +
           "    j.acceptedRecords = j.acceptedRecords + :accepted, " +
           "    j.unverifiedRecords = j.unverifiedRecords + :unverified, " +
           "    j.rejectedRecords = j.rejectedRecords + :rejected, " +
           "    j.duplicateRecords = j.duplicateRecords + :duplicate, " +
           "    j.lastProcessedRow = :lastRow, " +
           "    j.heartbeatAt = CURRENT_TIMESTAMP " +
           "WHERE j.jobId = :jobId")
    int atomicIncrementCounters(
            @Param("jobId") UUID jobId,
            @Param("processed") int processed,
            @Param("accepted") int accepted,
            @Param("unverified") int unverified,
            @Param("rejected") int rejected,
            @Param("duplicate") int duplicate,
            @Param("lastRow") int lastRow
    );
}
