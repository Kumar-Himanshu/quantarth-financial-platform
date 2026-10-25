package com.fip.plugin.adapters.out.persistence;

import com.fip.plugin.domain.model.PluginMetadata;
import org.mapstruct.Mapper;

@Mapper(componentModel = "spring")
public interface PluginMetadataMapper {

    PluginMetadata toDomain(PluginMetadataJpaEntity entity);

    PluginMetadataJpaEntity toEntity(PluginMetadata domain);
}
