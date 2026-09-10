ALTER TABLE automation_device_agent_registration
    ADD COLUMN feature_automation_status VARCHAR(16) NOT NULL DEFAULT 'DISABLED'
        AFTER feature_diagnostics_status,
    ADD COLUMN last_xcuitest_driver_version VARCHAR(40) NULL AFTER last_agent_version,
    ADD CONSTRAINT ck_device_agent_registration_automation
        CHECK (feature_automation_status IN ('DISABLED', 'ENABLED', 'FAILED'));

ALTER TABLE automation_device_agent_command
    ADD COLUMN target_device_id CHAR(64) NULL AFTER agent_id,
    ADD KEY idx_device_agent_command_device
        (agent_id, target_device_id, status, created_at),
    ADD CONSTRAINT fk_device_agent_command_device
        FOREIGN KEY (agent_id, target_device_id)
        REFERENCES automation_device_agent_device (agent_id, device_id) ON DELETE CASCADE;

ALTER TABLE automation_device_agent_device
    ADD COLUMN wda_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN' AFTER status,
    ADD COLUMN wda_running BOOLEAN NOT NULL DEFAULT FALSE AFTER wda_status,
    ADD COLUMN wda_local_port INT NULL AFTER wda_running,
    ADD COLUMN observed_wda_local_port INT NULL AFTER wda_local_port,
    ADD COLUMN wda_port_error_code VARCHAR(64) NULL AFTER observed_wda_local_port,
    ADD UNIQUE KEY uk_device_agent_device_wda_port (agent_id, wda_local_port),
    ADD CONSTRAINT ck_device_agent_device_wda_status
        CHECK (wda_status IN ('UNKNOWN', 'READY', 'MISSING', 'ERROR')),
    ADD CONSTRAINT ck_device_agent_device_wda_port
        CHECK (wda_local_port IS NULL OR wda_local_port BETWEEN 1024 AND 65535),
    ADD CONSTRAINT ck_device_agent_device_observed_wda_port
        CHECK (observed_wda_local_port IS NULL OR observed_wda_local_port BETWEEN 1024 AND 65535);

CREATE TABLE automation_device_agent_wda_config (
    agent_id VARCHAR(64) NOT NULL,
    signing_config_encrypted TEXT NULL,
    launch_mode VARCHAR(16) NOT NULL DEFAULT 'XCODEBUILD',
    wda_url VARCHAR(256) NULL,
    appium_server_url VARCHAR(256) NOT NULL DEFAULT 'http://127.0.0.1:4723',
    base_wda_local_port INT NOT NULL DEFAULT 8100,
    config_version BIGINT NOT NULL DEFAULT 1,
    config_hash CHAR(64) NOT NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (agent_id),
    CONSTRAINT fk_device_agent_wda_config_registration FOREIGN KEY (agent_id)
        REFERENCES automation_device_agent_registration (agent_id) ON DELETE CASCADE,
    CONSTRAINT ck_device_agent_wda_config_launch_mode
        CHECK (launch_mode IN ('XCODEBUILD', 'PREINSTALLED', 'URL')),
    CONSTRAINT ck_device_agent_wda_config_base_port
        CHECK (base_wda_local_port BETWEEN 1024 AND 65535)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE automation_device_agent_registry (
    agent_id VARCHAR(64) NOT NULL,
    port_override INT NULL,
    desired_state VARCHAR(16) NOT NULL DEFAULT 'OFFLINE',
    observed_state VARCHAR(24) NOT NULL DEFAULT 'NOT_INSTALLED',
    observed_port INT NULL,
    tunnel_count INT NOT NULL DEFAULT 0,
    helper_version VARCHAR(64) NULL,
    last_error_code VARCHAR(64) NULL,
    config_version BIGINT NOT NULL DEFAULT 1,
    last_reported_at DATETIME(6) NULL,
    created_by BIGINT NULL,
    updated_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6) ON UPDATE CURRENT_TIMESTAMP(6),
    PRIMARY KEY (agent_id),
    KEY idx_device_agent_registry_state (desired_state, observed_state, last_reported_at),
    CONSTRAINT fk_device_agent_registry_registration FOREIGN KEY (agent_id)
        REFERENCES automation_device_agent_registration (agent_id) ON DELETE CASCADE,
    CONSTRAINT ck_device_agent_registry_port_override
        CHECK (port_override IS NULL OR port_override BETWEEN 1024 AND 65535),
    CONSTRAINT ck_device_agent_registry_observed_port
        CHECK (observed_port IS NULL OR observed_port BETWEEN 1024 AND 65535),
    CONSTRAINT ck_device_agent_registry_desired
        CHECK (desired_state IN ('ONLINE', 'OFFLINE')),
    CONSTRAINT ck_device_agent_registry_observed
        CHECK (observed_state IN ('NOT_INSTALLED', 'OFFLINE', 'STARTING', 'ONLINE',
                                  'RESTARTING', 'ERROR', 'STALE')),
    CONSTRAINT ck_device_agent_registry_tunnels CHECK (tunnel_count BETWEEN 0 AND 100)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO automation_device_agent_registry
    (agent_id, desired_state, observed_state, created_at, updated_at)
SELECT agent_id, 'OFFLINE', 'NOT_INSTALLED', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
FROM automation_device_agent_registration;
