package com.fip.ingestion.adapters.out.persistence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "source_mapping_profiles")
public class SourceMappingProfileJpaEntity {

    @Id
    @Column(name = "profile_id", nullable = false, updatable = false)
    private UUID profileId;

    @Column(name = "tenant_id", nullable = false, length = 64)
    private String tenantId;

    @Column(name = "name", nullable = false, length = 128)
    private String name;

    @Column(name = "source_format", nullable = false, length = 32)
    private String sourceFormat;

    @Column(name = "target_entity_type", nullable = false, length = 64)
    private String targetEntityType;

    @Column(name = "delimiter", nullable = false, length = 5)
    private String delimiter = ",";

    @Column(name = "has_header", nullable = false)
    private boolean hasHeader = true;

    @Column(name = "date_format", nullable = false, length = 64)
    private String dateFormat = "yyyy-MM-dd HH:mm:ss";

    @Column(name = "timezone", nullable = false, length = 64)
    private String timezone = "UTC";

    @Column(name = "currency_default", nullable = false, length = 3)
    private String currencyDefault = "INR";

    @Column(name = "mapping_rules", nullable = false, columnDefinition = "TEXT")
    private String mappingRules;

    @Column(name = "version", nullable = false)
    private int version = 1;

    @Column(name = "is_active", nullable = false)
    private boolean isActive = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    @Column(name = "created_by", length = 64)
    private String createdBy;

    public SourceMappingProfileJpaEntity() {
    }

    @PrePersist
    public void onPrePersist() {
        if (profileId == null) {
            profileId = UUID.randomUUID();
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

    public UUID getProfileId() { return profileId; }
    public void setProfileId(UUID profileId) { this.profileId = profileId; }
    public String getTenantId() { return tenantId; }
    public void setTenantId(String tenantId) { this.tenantId = tenantId; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getSourceFormat() { return sourceFormat; }
    public void setSourceFormat(String sourceFormat) { this.sourceFormat = sourceFormat; }
    public String getTargetEntityType() { return targetEntityType; }
    public void setTargetEntityType(String targetEntityType) { this.targetEntityType = targetEntityType; }
    public String getDelimiter() { return delimiter; }
    public void setDelimiter(String delimiter) { this.delimiter = delimiter; }
    public boolean isHasHeader() { return hasHeader; }
    public void setHasHeader(boolean hasHeader) { this.hasHeader = hasHeader; }
    public String getDateFormat() { return dateFormat; }
    public void setDateFormat(String dateFormat) { this.dateFormat = dateFormat; }
    public String getTimezone() { return timezone; }
    public void setTimezone(String timezone) { this.timezone = timezone; }
    public String getCurrencyDefault() { return currencyDefault; }
    public void setCurrencyDefault(String currencyDefault) { this.currencyDefault = currencyDefault; }
    public String getMappingRules() { return mappingRules; }
    public void setMappingRules(String mappingRules) { this.mappingRules = mappingRules; }
    public int getVersion() { return version; }
    public void setVersion(int version) { this.version = version; }
    public boolean isActive() { return isActive; }
    public void setActive(boolean active) { isActive = active; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public void setCreatedAt(LocalDateTime createdAt) { this.createdAt = createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
    public String getCreatedBy() { return createdBy; }
    public void setCreatedBy(String createdBy) { this.createdBy = createdBy; }
}
