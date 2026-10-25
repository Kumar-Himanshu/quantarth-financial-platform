-- =============================================================================
-- Financial Intelligence Platform (FIP) - Auth Service Schema Migration
-- Flyway Migration: V1__init_auth.sql
-- =============================================================================

CREATE TABLE IF NOT EXISTS permissions (
    id UUID NOT NULL,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version BIGINT DEFAULT 0,
    soft_delete BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_permissions PRIMARY KEY (id),
    CONSTRAINT uk_permissions_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS roles (
    id UUID NOT NULL,
    name VARCHAR(50) NOT NULL,
    description VARCHAR(255),
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version BIGINT DEFAULT 0,
    soft_delete BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_roles PRIMARY KEY (id),
    CONSTRAINT uk_roles_name UNIQUE (name)
);

CREATE TABLE IF NOT EXISTS role_permissions (
    role_id UUID NOT NULL,
    permission_id UUID NOT NULL,
    CONSTRAINT pk_role_permissions PRIMARY KEY (role_id, permission_id),
    CONSTRAINT fk_role_permissions_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE,
    CONSTRAINT fk_role_permissions_permission FOREIGN KEY (permission_id) REFERENCES permissions (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS users (
    id UUID NOT NULL,
    username VARCHAR(50) NOT NULL,
    password VARCHAR(255) NOT NULL,
    email VARCHAR(100) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'ACTIVE',
    tenant_id VARCHAR(50) NOT NULL DEFAULT 'default',
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version BIGINT DEFAULT 0,
    soft_delete BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_users PRIMARY KEY (id),
    CONSTRAINT uk_users_username UNIQUE (username),
    CONSTRAINT uk_users_email UNIQUE (email)
);

CREATE TABLE IF NOT EXISTS user_roles (
    user_id UUID NOT NULL,
    role_id UUID NOT NULL,
    CONSTRAINT pk_user_roles PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_roles_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,
    CONSTRAINT fk_user_roles_role FOREIGN KEY (role_id) REFERENCES roles (id) ON DELETE CASCADE
);

CREATE TABLE IF NOT EXISTS refresh_tokens (
    id UUID NOT NULL,
    user_id UUID NOT NULL,
    token_hash VARCHAR(255) NOT NULL,
    expires_at TIMESTAMP NOT NULL,
    revoked BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMP NOT NULL,
    updated_at TIMESTAMP NOT NULL,
    created_by VARCHAR(100),
    updated_by VARCHAR(100),
    version BIGINT DEFAULT 0,
    soft_delete BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT pk_refresh_tokens PRIMARY KEY (id),
    CONSTRAINT uk_refresh_tokens_hash UNIQUE (token_hash),
    CONSTRAINT fk_refresh_tokens_user FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE
);

-- Performance and Audit Indexes
CREATE INDEX IF NOT EXISTS idx_users_username ON users(username);
CREATE INDEX IF NOT EXISTS idx_users_email ON users(email);
CREATE INDEX IF NOT EXISTS idx_users_tenant ON users(tenant_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_user ON refresh_tokens(user_id);
CREATE INDEX IF NOT EXISTS idx_refresh_tokens_hash ON refresh_tokens(token_hash);
CREATE INDEX IF NOT EXISTS idx_role_permissions_role ON role_permissions(role_id);
CREATE INDEX IF NOT EXISTS idx_user_roles_user ON user_roles(user_id);

-- Seed System Default Permissions
INSERT INTO permissions (id, name, description, created_at, updated_at, created_by, updated_by, version, soft_delete)
VALUES 
    ('11111111-1111-1111-1111-111111111101', 'fip:read', 'Read platform financial analytics and data', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('11111111-1111-1111-1111-111111111102', 'fip:write', 'Write transactions, create models', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('11111111-1111-1111-1111-111111111103', 'fip:admin', 'Full platform administrator access', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('11111111-1111-1111-1111-111111111104', 'fip:plugins:execute', 'Execute registered analytical plugins', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('11111111-1111-1111-1111-111111111105', 'fip:events:publish', 'Publish canonical financial events', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('11111111-1111-1111-1111-111111111106', 'fip:ai:process', 'Submit documents and images to AI pipeline', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE)
ON CONFLICT (name) DO NOTHING;

-- Seed System Default Roles
INSERT INTO roles (id, name, description, created_at, updated_at, created_by, updated_by, version, soft_delete)
VALUES 
    ('22222222-2222-2222-2222-222222222201', 'ROLE_SUPER_ADMIN', 'Platform Super Administrator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('22222222-2222-2222-2222-222222222202', 'ROLE_ADMIN', 'Tenant Administrator', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('22222222-2222-2222-2222-222222222203', 'ROLE_ANALYST', 'Financial Intelligence Analyst', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('22222222-2222-2222-2222-222222222204', 'ROLE_DEVELOPER', 'Plugin and Integration Developer', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE),
    ('22222222-2222-2222-2222-222222222205', 'ROLE_USER', 'Standard Platform Consumer', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, 'SYSTEM', 'SYSTEM', 0, FALSE)
ON CONFLICT (name) DO NOTHING;

-- Link Roles and Permissions
INSERT INTO role_permissions (role_id, permission_id)
VALUES
    -- SUPER_ADMIN gets all permissions
    ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111101'),
    ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111102'),
    ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111103'),
    ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111104'),
    ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111105'),
    ('22222222-2222-2222-2222-222222222201', '11111111-1111-1111-1111-111111111106'),
    -- ANALYST gets read, plugins, ai
    ('22222222-2222-2222-2222-222222222203', '11111111-1111-1111-1111-111111111101'),
    ('22222222-2222-2222-2222-222222222203', '11111111-1111-1111-1111-111111111104'),
    ('22222222-2222-2222-2222-222222222203', '11111111-1111-1111-1111-111111111106'),
    -- USER gets read, ai
    ('22222222-2222-2222-2222-222222222205', '11111111-1111-1111-1111-111111111101'),
    ('22222222-2222-2222-2222-222222222205', '11111111-1111-1111-1111-111111111106')
ON CONFLICT (role_id, permission_id) DO NOTHING;
