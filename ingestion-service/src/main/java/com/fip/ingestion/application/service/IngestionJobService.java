package com.fip.ingestion.application.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobJpaRepository;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaEntity;
import com.fip.ingestion.adapters.out.persistence.IngestionJobRecordJpaRepository;
import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.RecordStatus;
import com.fip.ingestion.domain.model.ReviewStatus;
import com.fip.ingestion.domain.model.SourceType;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.UUID;

@Service
public class IngestionJobService {

    private static final Logger log = LoggerFactory.getLogger(IngestionJobService.class);

    private final IngestionJobJpaRepository jobRepository;
    private final IngestionJobRecordJpaRepository recordRepository;
    private final ObjectMapper objectMapper;

    public IngestionJobService(
            IngestionJobJpaRepository jobRepository,
            IngestionJobRecordJpaRepository recordRepository,
            ObjectMapper objectMapper) {
        this.jobRepository = jobRepository;
        this.recordRepository = recordRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public IngestionJobJpaEntity createJob(
            String tenantId,
            SourceType sourceType,
            String sourceSystem,
            String sourceName,
            String fileHash,
            String stagingFilePath,
            UUID mappingProfileId,
            int mappingProfileVersion,
            int totalRecords) {

        IngestionJobJpaEntity job = new IngestionJobJpaEntity();
        job.setJobId(UUID.randomUUID());
        job.setTenantId(tenantId);
        job.setSourceType(sourceType);
        job.setSourceSystem(sourceSystem);
        job.setSourceName(sourceName);
        job.setFileHash(fileHash);
        job.setStagingFilePath(stagingFilePath);
        job.setMappingProfileId(mappingProfileId);
        job.setMappingProfileVersion(mappingProfileVersion);
        job.setStatus(JobStatus.RECEIVED);
        job.setTotalRecords(totalRecords);
        job.setHeartbeatAt(LocalDateTime.now());
        job.setStartedAt(LocalDateTime.now());

        return jobRepository.save(job);
    }

    @Transactional
    public void recordItem(
            UUID jobId,
            String tenantId,
            int recordIndex,
            String sourceRecordId,
            String entityType,
            UUID canonicalEventId,
            RecordStatus status,
            ReviewStatus reviewStatus,
            String errorCode,
            String errorMessage,
            String rawSnippet) {

        IngestionJobRecordJpaEntity record = new IngestionJobRecordJpaEntity();
        record.setRecordId(UUID.randomUUID());
        record.setJobId(jobId);
        record.setTenantId(tenantId);
        record.setRecordIndex(recordIndex);
        record.setSourceRecordId(sourceRecordId);
        record.setEntityType(entityType);
        record.setCanonicalEventId(canonicalEventId);
        record.setStatus(status);
        record.setReviewStatus(reviewStatus != null ? reviewStatus : ReviewStatus.NONE);
        record.setErrorCode(errorCode);
        record.setErrorMessage(errorMessage);
        record.setRawSnippet(rawSnippet);

        recordRepository.save(record);
    }

    @Transactional
    public void incrementCounters(
            UUID jobId,
            int processed,
            int accepted,
            int unverified,
            int rejected,
            int duplicate,
            int lastRow) {
        jobRepository.atomicIncrementCounters(jobId, processed, accepted, unverified, rejected, duplicate, lastRow);
    }

    @Transactional
    public void completeJob(UUID jobId, String errorSummary) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(job.getRejectedRecords() == 0 ? JobStatus.COMPLETED : JobStatus.PARTIALLY_COMPLETED);
            job.setCompletedAt(LocalDateTime.now());
            if (errorSummary != null) {
                job.setErrorSummary(errorSummary);
            }
            jobRepository.save(job);
            log.info("Job [{}] completed with status [{}] (total: {}, accepted: {}, unverified: {}, rejected: {}, duplicate: {})",
                    jobId, job.getStatus(), job.getTotalRecords(), job.getAcceptedRecords(),
                    job.getUnverifiedRecords(), job.getRejectedRecords(), job.getDuplicateRecords());
        });
    }

    @Transactional
    public void failJob(UUID jobId, String errorSummary) {
        jobRepository.findById(jobId).ifPresent(job -> {
            job.setStatus(JobStatus.FAILED);
            job.setCompletedAt(LocalDateTime.now());
            job.setErrorSummary(errorSummary);
            jobRepository.save(job);
            log.error("Job [{}] marked FAILED: {}", jobId, errorSummary);
        });
    }

    @Transactional(readOnly = true)
    public Optional<IngestionJobJpaEntity> getJob(UUID jobId, String tenantId) {
        return jobRepository.findByJobIdAndTenantId(jobId, tenantId);
    }

    @Transactional(readOnly = true)
    public Page<IngestionJobRecordJpaEntity> getJobRecords(
            UUID jobId,
            String tenantId,
            RecordStatus status,
            ReviewStatus reviewStatus,
            Pageable pageable) {

        if (status != null) {
            return recordRepository.findByJobIdAndTenantIdAndStatus(jobId, tenantId, status, pageable);
        }
        if (reviewStatus != null) {
            return recordRepository.findByJobIdAndTenantIdAndReviewStatus(jobId, tenantId, reviewStatus, pageable);
        }
        return recordRepository.findByJobIdAndTenantId(jobId, tenantId, pageable);
    }

    @Transactional(readOnly = true)
    public Optional<IngestionJobJpaEntity> findRecentCompletedJobByFileHash(String tenantId, String fileHash) {
        return jobRepository.findTopByTenantIdAndFileHashOrderByCreatedAtDesc(tenantId, fileHash)
                .filter(j -> j.getStatus() == JobStatus.COMPLETED);
    }
}
