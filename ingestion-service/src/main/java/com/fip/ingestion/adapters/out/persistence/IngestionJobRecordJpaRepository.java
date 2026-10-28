package com.fip.ingestion.adapters.out.persistence;

import com.fip.ingestion.domain.model.RecordStatus;
import com.fip.ingestion.domain.model.ReviewStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface IngestionJobRecordJpaRepository extends JpaRepository<IngestionJobRecordJpaEntity, UUID> {

    Page<IngestionJobRecordJpaEntity> findByJobIdAndTenantId(UUID jobId, String tenantId, Pageable pageable);

    java.util.List<IngestionJobRecordJpaEntity> findByJobId(UUID jobId);

    Page<IngestionJobRecordJpaEntity> findByJobIdAndTenantIdAndStatus(
            UUID jobId, String tenantId, RecordStatus status, Pageable pageable);

    Page<IngestionJobRecordJpaEntity> findByJobIdAndTenantIdAndReviewStatus(
            UUID jobId, String tenantId, ReviewStatus reviewStatus, Pageable pageable);

    Optional<IngestionJobRecordJpaEntity> findByRecordIdAndTenantId(UUID recordId, String tenantId);

    boolean existsByTenantIdAndSourceRecordId(String tenantId, String sourceRecordId);
}
