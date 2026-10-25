package com.fip.plugin.adapters.out.persistence;

import com.fip.plugin.domain.model.PluginMetadata;
import com.fip.plugin.ports.out.PluginPersistencePort;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Component
public class PluginMetadataPersistenceAdapter implements PluginPersistencePort {

    private final PluginMetadataJpaRepository repository;
    private final PluginMetadataMapper mapper;

    public PluginMetadataPersistenceAdapter(PluginMetadataJpaRepository repository,
                                            PluginMetadataMapper mapper) {
        this.repository = repository;
        this.mapper = mapper;
    }

    @Override
    public PluginMetadata save(PluginMetadata metadata) {
        PluginMetadataJpaEntity entity = mapper.toEntity(metadata);
        PluginMetadataJpaEntity saved = repository.save(entity);
        return mapper.toDomain(saved);
    }

    @Override
    public Optional<PluginMetadata> findById(UUID id) {
        return repository.findById(id).map(mapper::toDomain);
    }

    @Override
    public Optional<PluginMetadata> findByName(String name) {
        return repository.findByNameAndSoftDeleteFalse(name).map(mapper::toDomain);
    }

    @Override
    public List<PluginMetadata> findAll() {
        return repository.findAll().stream()
                .filter(e -> !e.isSoftDelete())
                .map(mapper::toDomain)
                .collect(Collectors.toList());
    }
}
