package com.fip.plugin.ports.out;

import com.fip.plugin.domain.model.PluginMetadata;
import java.util.List;
import java.util.Optional;

public interface PluginPersistencePort {
    PluginMetadata save(PluginMetadata plugin);

    Optional<PluginMetadata> findById(java.util.UUID id);

    Optional<PluginMetadata> findByName(String name);

    List<PluginMetadata> findAll();
}
