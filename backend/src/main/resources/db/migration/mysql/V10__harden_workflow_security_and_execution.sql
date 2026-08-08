CREATE TABLE IF NOT EXISTS sys_api_key_workflow (
    api_key_id BIGINT NOT NULL,
    workflow_id BIGINT NOT NULL,
    PRIMARY KEY (api_key_id, workflow_id),
    CONSTRAINT fk_api_key_workflow_key FOREIGN KEY (api_key_id) REFERENCES sys_api_key(id),
    CONSTRAINT fk_api_key_workflow_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id),
    INDEX idx_api_key_workflow_definition (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE workflow_connection
    ADD COLUMN security_revision BIGINT NOT NULL DEFAULT 1 AFTER enabled;

ALTER TABLE workflow_version
    ADD COLUMN connection_snapshot_complete BIT(1) NOT NULL DEFAULT b'0' AFTER template_snapshot_json;

CREATE TABLE IF NOT EXISTS workflow_version_connection (
    workflow_version_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    security_revision BIGINT NOT NULL,
    PRIMARY KEY (workflow_version_id, connection_id),
    CONSTRAINT fk_workflow_version_connection_version FOREIGN KEY (workflow_version_id) REFERENCES workflow_version(id),
    CONSTRAINT fk_workflow_version_connection_connection FOREIGN KEY (connection_id) REFERENCES workflow_connection(id),
    INDEX idx_workflow_version_connection_target (connection_id, security_revision)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

ALTER TABLE workflow_run
    ADD COLUMN api_key_id BIGINT NULL AFTER owner_user_id,
    ADD COLUMN execution_instance_id VARCHAR(120) NULL AFTER api_key_id,
    ADD COLUMN lease_expires_at DATETIME(6) NULL AFTER execution_instance_id,
    ADD COLUMN log_bytes BIGINT NOT NULL DEFAULT 0 AFTER lease_expires_at,
    ADD CONSTRAINT fk_workflow_run_api_key FOREIGN KEY (api_key_id) REFERENCES sys_api_key(id),
    ADD INDEX idx_workflow_run_lease (status, lease_expires_at),
    ADD INDEX idx_workflow_run_api_key (api_key_id, created_at);
