-- =============================================================================
-- Financial Intelligence Platform (FIP) - Plugin Framework Schema Migration
-- Flyway Migration: V1__init_plugins.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS plugins (
    id UUID NOT NULL,
    name VARCHAR(100) NOT NULL,
    version VARCHAR(20) NOT NULL,
    provider VARCHAR(100) DEFAULT 'INTERNAL',
    description VARCHAR(500),
    main_class VARCHAR(255) NOT NULL,
    jar_path VARCHAR(512) NOT NULL,
    checksum_sha256 VARCHAR(64),
    status VARCHAR(30) NOT NULL DEFAULT 'REGISTERED',
    capabilities TEXT,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version BIGINT DEFAULT 0,
    soft_delete BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_plugins PRIMARY KEY (id),
    CONSTRAINT uk_plugins_name_version UNIQUE (name, version)
);

CREATE TABLE IF NOT EXISTS plugin_execution_logs (
    id UUID NOT NULL,
    plugin_id UUID NOT NULL,
    plugin_name VARCHAR(100) NOT NULL,
    action VARCHAR(100) NOT NULL,
    execution_time_ms BIGINT NOT NULL,
    status VARCHAR(20) NOT NULL,
    error_message TEXT,
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
    executed_by VARCHAR(100),
    executed_at TIMESTAMP NOT NULL,
    CONSTRAINT pk_plugin_execution_logs PRIMARY KEY (id),
    CONSTRAINT fk_plugin_execution_logs_plugin FOREIGN KEY (plugin_id) REFERENCES plugins (id) ON DELETE CASCADE
);

CREATE INDEX IF NOT EXISTS idx_plugins_name ON plugins(name);
CREATE INDEX IF NOT EXISTS idx_plugins_status ON plugins(status);
CREATE INDEX IF NOT EXISTS idx_plugin_execution_logs_plugin ON plugin_execution_logs(plugin_id);
CREATE INDEX IF NOT EXISTS idx_plugin_execution_logs_tenant ON plugin_execution_logs(tenant_id);
CREATE INDEX IF NOT EXISTS idx_plugin_execution_logs_time ON plugin_execution_logs(executed_at);
