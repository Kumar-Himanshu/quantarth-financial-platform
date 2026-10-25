package com.fip.plugin.adapters.out.persistence;

import com.fip.common.model.AuditModel;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.util.UUID;

@Entity
@Table(name = "plugins")
public class PluginMetadataJpaEntity extends AuditModel {

    @Id
    @Column(name = "id", updatable = false, nullable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 100)
    private String name;

    @Column(name = "version", nullable = false, length = 20)
    private String version;

    @Column(name = "provider", length = 100)
    private String provider;

    @Column(name = "description", length = 500)
    private String description;

    @Column(name = "main_class", nullable = false, length = 255)
    private String mainClass;

    @Column(name = "jar_path", nullable = false, length = 512)
    private String jarPath;

    @Column(name = "checksum_sha256", length = 64)
    private String checksumSha256;

    @Column(name = "status", nullable = false, length = 30)
    private String status;

    @Column(name = "capabilities", columnDefinition = "TEXT")
    private String capabilities;

    public PluginMetadataJpaEntity() {
    }

    public PluginMetadataJpaEntity(UUID id, String name, String version, String provider, String description,
                                  String mainClass, String jarPath, String checksumSha256, String status, String capabilities) {
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
    }

    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getVersion() { return version; }
    public void setVersion(String version) { this.version = version; }

    public String getProvider() { return provider; }
    public void setProvider(String provider) { this.provider = provider; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getMainClass() { return mainClass; }
    public void setMainClass(String mainClass) { this.mainClass = mainClass; }

    public String getJarPath() { return jarPath; }
    public void setJarPath(String jarPath) { this.jarPath = jarPath; }

    public String getChecksumSha256() { return checksumSha256; }
    public void setChecksumSha256(String checksumSha256) { this.checksumSha256 = checksumSha256; }

    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }

    public String getCapabilities() { return capabilities; }
    public void setCapabilities(String capabilities) { this.capabilities = capabilities; }

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

        public PluginMetadataJpaEntity build() {
            return new PluginMetadataJpaEntity(id, name, version, provider, description, mainClass, jarPath, checksumSha256, status, capabilities);
        }
    }
}
