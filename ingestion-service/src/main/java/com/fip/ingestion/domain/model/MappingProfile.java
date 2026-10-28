package com.fip.ingestion.domain.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.io.Serializable;
import java.util.Map;
import java.util.UUID;

@JsonInclude(JsonInclude.Include.NON_NULL)
@JsonIgnoreProperties(ignoreUnknown = true)
public record MappingProfile(
        UUID profileId,
        String tenantId,
        String name,
        SourceType sourceFormat,
        String targetEntityType,
        String delimiter,
        boolean hasHeader,
        String dateFormat,
        String timezone,
        String currencyDefault,
        Map<String, FieldMapping> fieldMappings,
        int version,
        boolean isActive
) implements Serializable {

    public MappingProfile {
        if (delimiter == null || delimiter.isEmpty()) {
            delimiter = ",";
        }
        if (dateFormat == null || dateFormat.isEmpty()) {
            dateFormat = "yyyy-MM-dd HH:mm:ss";
        }
        if (timezone == null || timezone.isEmpty()) {
            timezone = "UTC";
        }
        if (currencyDefault == null || currencyDefault.isEmpty()) {
            currencyDefault = "INR";
        }
    }
}
