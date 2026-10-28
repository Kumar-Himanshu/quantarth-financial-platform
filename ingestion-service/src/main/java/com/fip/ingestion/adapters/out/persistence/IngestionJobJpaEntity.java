package com.fip.ingestion.adapters.out.persistence;

import com.fip.ingestion.domain.model.JobStatus;
import com.fip.ingestion.domain.model.SourceType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ingestion_jobs")
public class IngestionJobJpaEntity {

    @Id
    @Column(name = "job_id", nullable = false, updatable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Enumerated(EnumType.STRING)
    @Column(name = "source_type", nullable = false, length = 32)
    private SourceType sourceType;

    @Column(name = "source_system", nullable = false, length = 64)
    private String sourceSystem;

    @Column(name = "source_name", length = 256)
    private String sourceName;

    @Column(name = "file_hash", length = 64)
    private String fileHash;

    @Column(name = "staging_file_path", length = 512)
    private String stagingFilePath;

    @Column(name = "mapping_profile_id")
    private UUID mappingProfileId;

    @Column(name = "mapping_profile_version", nullable = false)
    private int mappingProfileVersion = 1;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private JobStatus status = JobStatus.RECEIVED;

    @Column(name = "total_records", nullable = false)
    private int totalRecords = 0;

    @Column(name = "processed_records", nullable = false)
    private int processedRecords = 0;

    @Column(name = "accepted_records", nullable = false)
    private int acceptedRecords = 0;

    @Column(name = "unverified_records", nullable = false)
    private int unverifiedRecords = 0;

    @Column(name = "rejected_records", nullable = false)
    private int rejectedRecords = 0;

    @Column(name = "duplicate_records", nullable = false)
    private int duplicateRecords = 0;

    @Column(name = "last_processed_row", nullable = false)
    private int lastProcessedRow = 0;

    @Column(name = "retry_count", nullable = false)
    private int retryCount = 0;

    @Column(name = "heartbeat_at")
    private LocalDateTime heartbeatAt;

    @Column(name = "started_at")
    private LocalDateTime startedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    @Column(name = "error_summary", columnDefinition = "TEXT")
    private String errorSummary;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public IngestionJobJpaEntity() {
    }

    @PrePersist
    public void onPrePersist() {
        if (jobId == null) {
            jobId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
        if (updatedAt == null) {
            updatedAt = LocalDateTime.now();
        }
    }

    @PreUpdate
    public void onPreUpdate() {
        updatedAt = LocalDateTime.now();
    }

    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public SourceType getSourceType() { return sourceType; }
    public void setSourceType(SourceType sourceType) { this.sourceType = sourceType; }
    public String getSourceSystem() { return sourceSystem; }
    public void setSourceSystem(String sourceSystem) { this.sourceSystem = sourceSystem; }
    public String getSourceName() { return sourceName; }
    public void setSourceName(String sourceName) { this.sourceName = sourceName; }
    public String getFileHash() { return fileHash; }
    public void setFileHash(String fileHash) { this.fileHash = fileHash; }
    public String getStagingFilePath() { return stagingFilePath; }
    public void setStagingFilePath(String stagingFilePath) { this.stagingFilePath = stagingFilePath; }
    public UUID getMappingProfileId() { return mappingProfileId; }
    public void setMappingProfileId(UUID mappingProfileId) { this.mappingProfileId = mappingProfileId; }
    public int getMappingProfileVersion() { return mappingProfileVersion; }
    public void setMappingProfileVersion(int mappingProfileVersion) { this.mappingProfileVersion = mappingProfileVersion; }
    public JobStatus getStatus() { return status; }
    public void setStatus(JobStatus status) { this.status = status; }
    public int getTotalRecords() { return totalRecords; }
    public void setTotalRecords(int totalRecords) { this.totalRecords = totalRecords; }
    public int getProcessedRecords() { return processedRecords; }
    public void setProcessedRecords(int processedRecords) { this.processedRecords = processedRecords; }
    public int getAcceptedRecords() { return acceptedRecords; }
    public void setAcceptedRecords(int acceptedRecords) { this.acceptedRecords = acceptedRecords; }
    public int getUnverifiedRecords() { return unverifiedRecords; }
    public void setUnverifiedRecords(int unverifiedRecords) { this.unverifiedRecords = unverifiedRecords; }
    public int getRejectedRecords() { return rejectedRecords; }
    public void setRejectedRecords(int rejectedRecords) { this.rejectedRecords = rejectedRecords; }
    public int getDuplicateRecords() { return duplicateRecords; }
    public void setDuplicateRecords(int duplicateRecords) { this.duplicateRecords = duplicateRecords; }
    public int getLastProcessedRow() { return lastProcessedRow; }
    public void setLastProcessedRow(int lastProcessedRow) { this.lastProcessedRow = lastProcessedRow; }
    public int getRetryCount() { return retryCount; }
    public void setRetryCount(int retryCount) { this.retryCount = retryCount; }
    public LocalDateTime getHeartbeatAt() { return heartbeatAt; }
    public void setHeartbeatAt(LocalDateTime heartbeatAt) { this.heartbeatAt = heartbeatAt; }
    public LocalDateTime getStartedAt() { return startedAt; }
    public void setStartedAt(LocalDateTime startedAt) { this.startedAt = startedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
    public String getErrorSummary() { return errorSummary; }
    public void setErrorSummary(String errorSummary) { this.errorSummary = errorSummary; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
