package com.fip.ingestion.adapters.out.persistence;

import com.fip.ingestion.domain.model.RecordStatus;
import com.fip.ingestion.domain.model.ReviewStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "ingestion_job_records")
public class IngestionJobRecordJpaEntity {

    @Id
    @Column(name = "record_id", nullable = false, updatable = false)
    private UUID recordId;

    @Column(name = "job_id", nullable = false)
    private UUID jobId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "record_index", nullable = false)
    private int recordIndex;

    @Column(name = "source_record_id", length = 128)
    private String sourceRecordId;

    @Column(name = "entity_type", length = 64)
    private String entityType;

    @Column(name = "canonical_event_id")
    private UUID canonicalEventId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 32)
    private RecordStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "review_status", nullable = false, length = 32)
    private ReviewStatus reviewStatus = ReviewStatus.NONE;

    @Column(name = "error_code", length = 64)
    private String errorCode;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "raw_snippet", columnDefinition = "TEXT")
    private String rawSnippet;

    @Column(name = "reconciled_at")
    private LocalDateTime reconciledAt;

    @Column(name = "reconciled_by", length = 64)
    private String reconciledBy;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public IngestionJobRecordJpaEntity() {
    }

    @PrePersist
    public void onPrePersist() {
        if (recordId == null) {
            recordId = UUID.randomUUID();
        }
        if (createdAt == null) {
            createdAt = LocalDateTime.now();
        }
    }

    public UUID getRecordId() { return recordId; }
    public void setRecordId(UUID recordId) { this.recordId = recordId; }
    public UUID getJobId() { return jobId; }
    public void setJobId(UUID jobId) { this.jobId = jobId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public int getRecordIndex() { return recordIndex; }
    public void setRecordIndex(int recordIndex) { this.recordIndex = recordIndex; }
    public String getSourceRecordId() { return sourceRecordId; }
    public void setSourceRecordId(String sourceRecordId) { this.sourceRecordId = sourceRecordId; }
    public String getEntityType() { return entityType; }
    public void setEntityType(String entityType) { this.entityType = entityType; }
    public UUID getCanonicalEventId() { return canonicalEventId; }
    public void setCanonicalEventId(UUID canonicalEventId) { this.canonicalEventId = canonicalEventId; }
    public RecordStatus getStatus() { return status; }
    public void setStatus(RecordStatus status) { this.status = status; }
    public ReviewStatus getReviewStatus() { return reviewStatus; }
    public void setReviewStatus(ReviewStatus reviewStatus) { this.reviewStatus = reviewStatus; }
    public String getErrorCode() { return errorCode; }
    public void setErrorCode(String errorCode) { this.errorCode = errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public void setErrorMessage(String errorMessage) { this.errorMessage = errorMessage; }
    public String getRawSnippet() { return rawSnippet; }
    public void setRawSnippet(String rawSnippet) { this.rawSnippet = rawSnippet; }
    public LocalDateTime getReconciledAt() { return reconciledAt; }
    public void setReconciledAt(LocalDateTime reconciledAt) { this.reconciledAt = reconciledAt; }
    public String getReconciledBy() { return reconciledBy; }
    public void setReconciledBy(String reconciledBy) { this.reconciledBy = reconciledBy; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
}
