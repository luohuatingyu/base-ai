CREATE TABLE automation_device_agent_registration (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id VARCHAR(64) NOT NULL,
    device_name VARCHAR(128) NULL,
    pairing_status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    agent_secret_encrypted TEXT NULL,
    backend_url VARCHAR(512) NULL,
    feature_diagnostics_status VARCHAR(16) NOT NULL DEFAULT 'ENABLED',
    feature_autostart_status VARCHAR(16) NOT NULL DEFAULT 'DISABLED',
    last_online_at DATETIME(6) NULL,
    last_agent_version VARCHAR(40) NULL,
    last_heartbeat_status VARCHAR(16) NULL,
    available_versions JSON NULL,
    last_error_code VARCHAR(64) NULL,
    is_default BOOLEAN NOT NULL DEFAULT FALSE,
    default_slot TINYINT GENERATED ALWAYS AS (CASE WHEN is_default = TRUE THEN 1 ELSE NULL END) STORED,
    revoked_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_agent_registration_agent (agent_id),
    UNIQUE KEY uk_device_agent_registration_default (default_slot),
    CONSTRAINT ck_device_agent_registration_pairing CHECK (pairing_status IN ('PENDING', 'PAIRED', 'REVOKED')),
    CONSTRAINT ck_device_agent_registration_diagnostics CHECK (feature_diagnostics_status IN ('DISABLED', 'ENABLED', 'FAILED')),
    CONSTRAINT ck_device_agent_registration_autostart CHECK (feature_autostart_status IN ('DISABLED', 'ENABLED', 'FAILED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_device_agent_pairing (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id VARCHAR(64) NOT NULL,
    code_hash CHAR(64) NOT NULL,
    code_ciphertext TEXT NOT NULL,
    requested_features JSON NOT NULL,
    failed_attempts INT NOT NULL DEFAULT 0,
    expires_at DATETIME(6) NOT NULL,
    used_at DATETIME(6) NULL,
    revoked_at DATETIME(6) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    UNIQUE KEY uk_device_agent_pairing_hash (code_hash),
    KEY idx_device_agent_pairing_agent_created (agent_id, created_at),
    KEY idx_device_agent_pairing_expiry (expires_at),
    CONSTRAINT fk_device_agent_pairing_registration FOREIGN KEY (agent_id)
        REFERENCES automation_device_agent_registration (agent_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_device_agent_state (
    agent_id VARCHAR(64) NOT NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    ios_version VARCHAR(40) NULL,
    readiness_status VARCHAR(16) NULL,
    readiness_checks JSON NULL,
    last_error_code VARCHAR(64) NULL,
    last_diagnostics_at DATETIME(6) NULL,
    last_heartbeat_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (agent_id),
    CONSTRAINT fk_device_agent_state_registration FOREIGN KEY (agent_id)
        REFERENCES automation_device_agent_registration (agent_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_device_agent_command (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id VARCHAR(64) NOT NULL,
    command_type VARCHAR(32) NOT NULL,
    command_params JSON NULL,
    status VARCHAR(16) NOT NULL DEFAULT 'PENDING',
    lease_token VARCHAR(96) NULL,
    lease_expires_at DATETIME(6) NULL,
    result_summary VARCHAR(2000) NULL,
    error_code VARCHAR(64) NULL,
    started_at DATETIME(6) NULL,
    completed_at DATETIME(6) NULL,
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_device_agent_command_lease (agent_id, status, created_at),
    CONSTRAINT fk_device_agent_command_registration FOREIGN KEY (agent_id)
        REFERENCES automation_device_agent_registration (agent_id) ON DELETE CASCADE,
    CONSTRAINT ck_device_agent_command_status CHECK (status IN ('PENDING', 'LEASED', 'COMPLETED', 'FAILED', 'EXPIRED', 'CANCELLED'))
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_device_agent_device (
    agent_id VARCHAR(64) NOT NULL,
    device_id CHAR(64) NOT NULL,
    device_name VARCHAR(128) NULL,
    model VARCHAR(80) NULL,
    platform VARCHAR(20) NOT NULL DEFAULT 'iOS',
    os_version VARCHAR(40) NULL,
    connected BOOLEAN NOT NULL DEFAULT FALSE,
    connection_type VARCHAR(16) NULL,
    status VARCHAR(32) NOT NULL DEFAULT 'OFFLINE',
    last_error_code VARCHAR(64) NULL,
    last_seen_at DATETIME(6) NULL,
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (agent_id, device_id),
    KEY idx_device_agent_device_connected (agent_id, connected, last_seen_at),
    CONSTRAINT fk_device_agent_device_registration FOREIGN KEY (agent_id)
        REFERENCES automation_device_agent_registration (agent_id) ON DELETE CASCADE
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_device_agent_audit (
    id BIGINT NOT NULL AUTO_INCREMENT,
    agent_id VARCHAR(64) NULL,
    event_type VARCHAR(64) NOT NULL,
    event_detail JSON NULL,
    user_id BIGINT NULL,
    ip_address VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    KEY idx_device_agent_audit_agent_created (agent_id, created_at),
    KEY idx_device_agent_audit_type_created (event_type, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
