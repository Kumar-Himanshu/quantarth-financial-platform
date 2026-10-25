package com.fip.shared.dto;

import java.io.Serializable;
import java.util.UUID;

public record TenantDto(
        UUID id,
        String name,
        String domain,
        String status,
        String planName,
        long storageQuotaBytes,
        long apiQuotaPerMonth) implements Serializable {
}
