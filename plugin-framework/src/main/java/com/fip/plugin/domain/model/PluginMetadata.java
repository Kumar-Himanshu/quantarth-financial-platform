package com.fip.plugin.domain.model;

import java.time.LocalDateTime;
import java.util.UUID;

public class PluginMetadata {
    private final UUID id;
    private final String name;
    private final String version;
    private final String provider;
    private final String description;
    private final String mainClass;
    private final String jarPath;
    private final String checksumSha256;
    private final String status;
    private final String capabilities;
    private final LocalDateTime createdAt;
    private final LocalDateTime updatedAt;

    public PluginMetadata(UUID id, String name, String version, String provider, String description,
                          String mainClass, String jarPath, String checksumSha256, String status,
                          String capabilities, LocalDateTime createdAt, LocalDateTime updatedAt) {
        this.id = id;
        this.name = name;
        this.version = version;
        this.provider = provider;
        this.description = description;
        this.mainClass = mainClass;
        this.jarPath = jarPath;
        this.checksumSha256 = checksumSha256;
        this.status = status;
        this.capabilities = capabilities;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
    }

    public UUID getId() { return id; }
    public String getName() { return name; }
    public String getVersion() { return version; }
    public String getProvider() { return provider; }
    public String getDescription() { return description; }
    public String getMainClass() { return mainClass; }
    public String getJarPath() { return jarPath; }
    public String getChecksumSha256() { return checksumSha256; }
    public String getStatus() { return status; }
    public String getCapabilities() { return capabilities; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }

    public static Builder builder() { return new Builder(); }

    public static class Builder {
        private UUID id;
        private String name;
        private String version;
        private String provider;
        private String description;
        private String mainClass;
        private String jarPath;
        private String checksumSha256;
        private String status;
        private String capabilities;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;

        public Builder id(UUID id) { this.id = id; return this; }
        public Builder name(String name) { this.name = name; return this; }
        public Builder version(String version) { this.version = version; return this; }
        public Builder provider(String provider) { this.provider = provider; return this; }
        public Builder description(String description) { this.description = description; return this; }
        public Builder mainClass(String mainClass) { this.mainClass = mainClass; return this; }
        public Builder jarPath(String jarPath) { this.jarPath = jarPath; return this; }
        public Builder checksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; return this; }
        public Builder status(String status) { this.status = status; return this; }
        public Builder capabilities(String capabilities) { this.capabilities = capabilities; return this; }
        public Builder createdAt(LocalDateTime createdAt) { this.createdAt = createdAt; return this; }
        public Builder updatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; return this; }

        public PluginMetadata build() {
            return new PluginMetadata(id, name, version, provider, description, mainClass, jarPath, checksumSha256, status, capabilities, createdAt, updatedAt);
        }
    }

    public static PluginMetadata createNew(String name, String version, String provider, String description,
                                           String mainClass, String jarPath, String checksumSha256, String capabilities) {
        return PluginMetadata.builder()
                .id(UUID.randomUUID())
                .name(name)
                .version(version)
                .provider(provider != null ? provider : "INTERNAL")
                .description(description != null ? description : "")
                .mainClass(mainClass)
                .jarPath(jarPath)
                .checksumSha256(checksumSha256 != null ? checksumSha256 : "")
                .status("REGISTERED")
                .capabilities(capabilities != null ? capabilities : "GENERAL")
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();
    }

    public PluginMetadata withStatus(String newStatus) {
        return PluginMetadata.builder()
                .id(this.id)
                .name(this.name)
                .version(this.version)
                .provider(this.provider)
                .description(this.description)
                .mainClass(this.mainClass)
                .jarPath(this.jarPath)
                .checksumSha256(this.checksumSha256)
                .status(newStatus)
                .capabilities(this.capabilities)
                .createdAt(this.createdAt)
                .updatedAt(LocalDateTime.now())
                .build();
    }
}
