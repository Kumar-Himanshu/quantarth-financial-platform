package com.fip.ingestion.application.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fip.common.tenant.TenantContext;
import com.fip.ingestion.adapters.out.persistence.SourceMappingProfileJpaEntity;
import com.fip.ingestion.adapters.out.persistence.SourceMappingProfileJpaRepository;
import com.fip.ingestion.domain.model.FieldMapping;
import com.fip.ingestion.domain.model.MappingProfile;
import com.fip.ingestion.domain.model.SourceType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
public class MappingProfileService {

    private final SourceMappingProfileJpaRepository profileRepository;
    private final ObjectMapper objectMapper;

    public MappingProfileService(SourceMappingProfileJpaRepository profileRepository, ObjectMapper objectMapper) {
        this.profileRepository = profileRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public MappingProfile createProfile(MappingProfile profile) {
        String tenantId = TenantContext.getCurrentTenant();
        if (tenantId == null || tenantId.isBlank()) {
            tenantId = profile.tenantId() != null ? profile.tenantId() : "default";
        }

        SourceMappingProfileJpaEntity entity = new SourceMappingProfileJpaEntity();
        entity.setProfileId(profile.profileId() != null ? profile.profileId() : UUID.randomUUID());
        entity.setTenantId(tenantId);
        entity.setName(profile.name());
        entity.setSourceFormat(profile.sourceFormat() != null ? profile.sourceFormat().name() : "CSV");
        entity.setTargetEntityType(profile.targetEntityType() != null ? profile.targetEntityType() : "PAYMENT");
        entity.setDelimiter(profile.delimiter());
        entity.setHasHeader(profile.hasHeader());
        entity.setDateFormat(profile.dateFormat());
        entity.setTimezone(profile.timezone());
        entity.setCurrencyDefault(profile.currencyDefault());
        entity.setVersion(profile.version() > 0 ? profile.version() : 1);
        entity.setActive(true);

        try {
            entity.setMappingRules(objectMapper.writeValueAsString(profile.fieldMappings()));
        } catch (Exception e) {
            entity.setMappingRules("{}");
        }

        SourceMappingProfileJpaEntity saved = profileRepository.save(entity);
        return toDomain(saved);
    }

    @Transactional(readOnly = true)
    public Optional<MappingProfile> getProfile(UUID profileId) {
        String tenantId = TenantContext.getCurrentTenant();
        return profileRepository.findByProfileIdAndTenantId(profileId, tenantId).map(this::toDomain);
    }

    @Transactional(readOnly = true)
    public Optional<MappingProfile> getProfileByName(String name) {
        String tenantId = TenantContext.getCurrentTenant();
        return profileRepository.findByTenantIdAndName(tenantId, name).map(this::toDomain);
    }

    @Transactional(readOnly = true)
    public List<MappingProfile> listProfiles() {
        String tenantId = TenantContext.getCurrentTenant();
        return profileRepository.findAllByTenantIdAndIsActiveTrue(tenantId).stream()
                .map(this::toDomain)
                .toList();
    }

    public MappingProfile toDomain(SourceMappingProfileJpaEntity entity) {
        Map<String, FieldMapping> mappings = Collections.emptyMap();
        try {
            if (entity.getMappingRules() != null && !entity.getMappingRules().isBlank()) {
                mappings = objectMapper.readValue(entity.getMappingRules(), new TypeReference<>() {});
            }
        } catch (Exception ignored) {}

        SourceType sourceType = SourceType.CSV;
        try {
            sourceType = SourceType.valueOf(entity.getSourceFormat().toUpperCase());
        } catch (Exception ignored) {}

        return new MappingProfile(
                entity.getProfileId(),
                entity.getTenantId(),
                entity.getName(),
                sourceType,
                entity.getTargetEntityType(),
                entity.getDelimiter(),
                entity.isHasHeader(),
                entity.getDateFormat(),
                entity.getTimezone(),
                entity.getCurrencyDefault(),
                mappings,
                entity.getVersion(),
                entity.isActive()
        );
    }
}
