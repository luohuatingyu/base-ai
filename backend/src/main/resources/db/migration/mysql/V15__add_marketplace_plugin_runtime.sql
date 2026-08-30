CREATE TABLE IF NOT EXISTS workflow_marketplace_plugin (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source VARCHAR(16) NOT NULL,
    package_key VARCHAR(255) NOT NULL,
    package_version VARCHAR(64) NOT NULL,
    package_fingerprint CHAR(64) NOT NULL,
    publisher VARCHAR(120) NOT NULL DEFAULT '',
    trust_level VARCHAR(32) NOT NULL DEFAULT 'COMMUNITY',
    runtime_language VARCHAR(16) NOT NULL,
    install_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    compatibility_status VARCHAR(24) NOT NULL DEFAULT 'PROBING',
    compatibility_reason VARCHAR(500) NOT NULL DEFAULT '',
    enabled BIT(1) NOT NULL DEFAULT b'0',
    installed_by BIGINT NOT NULL,
    installed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_marketplace_plugin UNIQUE (source, package_key),
    INDEX idx_workflow_marketplace_plugin_status (source, install_status, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_marketplace_component (
    id BIGINT NOT NULL AUTO_INCREMENT,
    plugin_id BIGINT NOT NULL,
    external_key VARCHAR(255) NOT NULL,
    component_type VARCHAR(32) NOT NULL,
    name VARCHAR(160) NOT NULL,
    description VARCHAR(1000) NOT NULL DEFAULT '',
    schema_json LONGTEXT NOT NULL,
    credential_schema_json LONGTEXT NOT NULL,
    compatibility_status VARCHAR(24) NOT NULL DEFAULT 'PROBING',
    compatibility_reason VARCHAR(500) NOT NULL DEFAULT '',
    schema_fingerprint CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_marketplace_component UNIQUE (plugin_id, external_key),
    CONSTRAINT fk_workflow_marketplace_component_plugin FOREIGN KEY (plugin_id)
        REFERENCES workflow_marketplace_plugin(id),
    INDEX idx_workflow_marketplace_component_type (component_type, compatibility_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_plugin_oauth_state (
    state_hash CHAR(64) NOT NULL,
    connection_id BIGINT NOT NULL,
    component_id BIGINT NOT NULL,
    verifier_encrypted TEXT NOT NULL,
    redirect_uri VARCHAR(500) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (state_hash),
    CONSTRAINT fk_workflow_plugin_oauth_connection FOREIGN KEY (connection_id)
        REFERENCES workflow_connection(id),
    CONSTRAINT fk_workflow_plugin_oauth_component FOREIGN KEY (component_id)
        REFERENCES workflow_marketplace_component(id),
    INDEX idx_workflow_plugin_oauth_expiry (expires_at, consumed_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_plugin_trigger_subscription (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    workflow_version_id BIGINT NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    component_id BIGINT NOT NULL,
    subscription_encrypted LONGTEXT NOT NULL,
    status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    lease_owner VARCHAR(120) NULL,
    lease_expires_at DATETIME(6) NULL,
    last_error VARCHAR(1000) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_plugin_trigger UNIQUE (workflow_version_id, node_id),
    CONSTRAINT fk_workflow_plugin_trigger_workflow FOREIGN KEY (workflow_id)
        REFERENCES workflow_definition(id),
    CONSTRAINT fk_workflow_plugin_trigger_version FOREIGN KEY (workflow_version_id)
        REFERENCES workflow_version(id),
    CONSTRAINT fk_workflow_plugin_trigger_component FOREIGN KEY (component_id)
        REFERENCES workflow_marketplace_component(id),
    INDEX idx_workflow_plugin_trigger_lease (status, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE workflow_connection
    ADD COLUMN plugin_component_id BIGINT NULL AFTER connection_type,
    ADD CONSTRAINT fk_workflow_connection_plugin_component FOREIGN KEY (plugin_component_id)
        REFERENCES workflow_marketplace_component(id),
    ADD INDEX idx_workflow_connection_plugin_component (plugin_component_id, voided);
