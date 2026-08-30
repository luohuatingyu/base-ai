CREATE TABLE IF NOT EXISTS sys_department (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NULL,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL,
    enabled BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_department_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_position (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    sort_order INT NOT NULL,
    enabled BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_position_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_menu (
    id BIGINT NOT NULL AUTO_INCREMENT,
    parent_id BIGINT NULL,
    name VARCHAR(100) NOT NULL,
    type VARCHAR(20) NOT NULL,
    path VARCHAR(160) NULL,
    component VARCHAR(120) NULL,
    icon VARCHAR(60) NULL,
    permission VARCHAR(120) NULL,
    sort_order INT NOT NULL,
    visible BIT(1) NOT NULL,
    enabled BIT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_menu_permission UNIQUE (permission)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_role (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(500) NULL,
    data_scope VARCHAR(32) NOT NULL,
    enabled BIT(1) NOT NULL,
    sort_order INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_role_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NOT NULL,
    display_name VARCHAR(100) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    enabled BIT(1) NOT NULL,
    department_id BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_user_username UNIQUE (username),
    CONSTRAINT fk_sys_user_department FOREIGN KEY (department_id) REFERENCES sys_department (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_role_menu (
    role_id BIGINT NOT NULL,
    menu_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, menu_id),
    CONSTRAINT fk_role_menu_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_role_menu_menu FOREIGN KEY (menu_id) REFERENCES sys_menu (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_role_department (
    role_id BIGINT NOT NULL,
    department_id BIGINT NOT NULL,
    PRIMARY KEY (role_id, department_id),
    CONSTRAINT fk_role_department_role FOREIGN KEY (role_id) REFERENCES sys_role (id),
    CONSTRAINT fk_role_department_department FOREIGN KEY (department_id) REFERENCES sys_department (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user_role (
    user_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, role_id),
    CONSTRAINT fk_user_role_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_role_role FOREIGN KEY (role_id) REFERENCES sys_role (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_user_position (
    user_id BIGINT NOT NULL,
    position_id BIGINT NOT NULL,
    PRIMARY KEY (user_id, position_id),
    CONSTRAINT fk_user_position_user FOREIGN KEY (user_id) REFERENCES sys_user (id),
    CONSTRAINT fk_user_position_position FOREIGN KEY (position_id) REFERENCES sys_position (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_dictionary_type (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NULL,
    enabled BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_dictionary_type_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_dictionary_data (
    id BIGINT NOT NULL AUTO_INCREMENT,
    type_code VARCHAR(80) NOT NULL,
    label VARCHAR(120) NOT NULL,
    dict_value VARCHAR(120) NOT NULL,
    sort_order INT NOT NULL,
    enabled BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_dictionary_data UNIQUE (type_code, dict_value)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_login_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    username VARCHAR(64) NULL,
    ip_address VARCHAR(64) NULL,
    user_agent VARCHAR(500) NULL,
    success BIT(1) NOT NULL,
    message VARCHAR(500) NULL,
    login_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_login_log_time (login_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_operation_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    user_id BIGINT NULL,
    username VARCHAR(64) NULL,
    credential_type VARCHAR(20) NULL,
    credential_id BIGINT NULL,
    credential_name VARCHAR(100) NULL,
    method VARCHAR(16) NOT NULL,
    path VARCHAR(255) NOT NULL,
    controller VARCHAR(64) NULL,
    action VARCHAR(64) NULL,
    request_data MEDIUMTEXT NULL,
    ip_address VARCHAR(64) NULL,
    duration_ms BIGINT NULL,
    success BIT(1) NOT NULL,
    error_message VARCHAR(1000) NULL,
    operated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    INDEX idx_operation_log_time (operated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_setting (
    id BIGINT NOT NULL AUTO_INCREMENT,
    group_code VARCHAR(64) NOT NULL,
    config_key VARCHAR(120) NOT NULL,
    name VARCHAR(120) NOT NULL,
    config_value MEDIUMTEXT NULL,
    `sensitive` BIT(1) NOT NULL,
    enabled BIT(1) NOT NULL,
    sort_order INT NOT NULL,
    system_managed BIT(1) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_setting_key UNIQUE (config_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_setting_sync_outbox (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_key VARCHAR(120) NOT NULL,
    operation VARCHAR(16) NOT NULL,
    attempts INT NOT NULL,
    created_at DATETIME(6) NOT NULL,
    processed_at DATETIME(6) NULL,
    last_error VARCHAR(500) NULL,
    PRIMARY KEY (id),
    INDEX idx_sys_setting_sync_outbox_pending (processed_at, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_llm_provider (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    base_url VARCHAR(500) NOT NULL,
    api_keys_encrypted MEDIUMTEXT NOT NULL,
    concurrency_limit INT NOT NULL,
    concurrency_level VARCHAR(24) NOT NULL,
    timeout_seconds INT NOT NULL,
    thinking_parameter VARCHAR(64) NOT NULL,
    enabled BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_llm_provider_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_llm_model (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    provider_id BIGINT NOT NULL,
    model_name VARCHAR(160) NOT NULL,
    model_type VARCHAR(1000) NOT NULL,
    capability_level VARCHAR(24) NOT NULL,
    thinking_levels VARCHAR(1000) NULL,
    health_status VARCHAR(16) NOT NULL,
    last_check_duration_ms BIGINT NULL,
    last_check_error VARCHAR(1000) NULL,
    last_checked_at DATETIME(6) NULL,
    enabled BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_llm_model_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_llm_route (
    id BIGINT NOT NULL AUTO_INCREMENT,
    feature_code VARCHAR(100) NOT NULL,
    name VARCHAR(120) NOT NULL,
    provider_ids VARCHAR(1000) NOT NULL,
    capability_level VARCHAR(24) NULL,
    thinking_level VARCHAR(24) NULL,
    enable_thinking BIT(1) NOT NULL,
    enabled BIT(1) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_llm_route_feature UNIQUE (feature_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_mail_account (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    host VARCHAR(255) NOT NULL,
    port INT NOT NULL,
    username VARCHAR(255) NOT NULL,
    from_address VARCHAR(255) NOT NULL,
    tls_mode VARCHAR(16) NOT NULL,
    password_encrypted TEXT NOT NULL,
    enabled BIT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mail_account_code UNIQUE (code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_mail_route (
    id BIGINT NOT NULL AUTO_INCREMENT,
    business_code VARCHAR(64) NOT NULL,
    name VARCHAR(120) NOT NULL,
    account_id BIGINT NULL,
    to_addresses TEXT NOT NULL,
    cc_addresses TEXT NULL,
    enabled BIT(1) NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_mail_route_business UNIQUE (business_code)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_api_key (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(100) NOT NULL,
    key_id VARCHAR(32) NOT NULL,
    secret_hash VARCHAR(64) NOT NULL,
    secret_encrypted TEXT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    enabled BIT(1) NOT NULL,
    expires_at DATETIME(6) NULL,
    rate_limit_type ENUM('SECOND','MINUTE','HOUR','DAY','UNLIMITED') NOT NULL,
    rate_limit_count INT NULL,
    last_used_at DATETIME(6) NULL,
    last_used_ip VARCHAR(64) NULL,
    created_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    revoked_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT uk_sys_api_key_id UNIQUE (key_id),
    CONSTRAINT fk_api_key_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user (id),
    INDEX idx_api_key_owner (owner_user_id),
    INDEX idx_api_key_enabled (enabled, revoked_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_api_key_endpoint (
    api_key_id BIGINT NOT NULL,
    endpoint_code VARCHAR(120) NOT NULL,
    PRIMARY KEY (api_key_id, endpoint_code),
    CONSTRAINT fk_api_key_endpoint_key FOREIGN KEY (api_key_id) REFERENCES sys_api_key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_api_key_ip_rule (
    api_key_id BIGINT NOT NULL,
    cidr VARCHAR(64) NOT NULL,
    PRIMARY KEY (api_key_id, cidr),
    CONSTRAINT fk_api_key_ip_rule_key FOREIGN KEY (api_key_id) REFERENCES sys_api_key (id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS task_trace (
    trace_id VARCHAR(32) PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    trigger_entry VARCHAR(64),
    status VARCHAR(24) NOT NULL,
    request_path VARCHAR(255),
    request_method VARCHAR(16),
    request_params_json MEDIUMTEXT,
    request_headers_json MEDIUMTEXT,
    java_instance_id VARCHAR(100),
    python_trace_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(1000),
    cancellation_reason VARCHAR(500),
    cancel_requested_at TIMESTAMP(6) NULL,
    heartbeat_at TIMESTAMP(6) NULL,
    version BIGINT NOT NULL DEFAULT 0,
    finished_reason VARCHAR(100),
    force_terminated_by BIGINT,
    force_terminated_at TIMESTAMP(6) NULL,
    force_terminate_reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at TIMESTAMP(6) NULL,
    INDEX idx_task_trace_owner_started (owner_user_id, started_at),
    INDEX idx_task_trace_status_started (status, started_at),
    INDEX idx_task_trace_status_heartbeat (status, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_trace_python (
    python_trace_id VARCHAR(64) PRIMARY KEY,
    parent_trace_id VARCHAR(32) NOT NULL,
    worker_endpoint VARCHAR(255) NOT NULL,
    worker_instance_id VARCHAR(100),
    status VARCHAR(24) NOT NULL,
    heartbeat_at TIMESTAMP(6) NULL,
    error_message VARCHAR(1000),
    finished_reason VARCHAR(100),
    cancel_requested_at TIMESTAMP(6) NULL,
    force_terminated_by BIGINT,
    force_terminated_at TIMESTAMP(6) NULL,
    force_terminate_reason VARCHAR(500),
    created_at TIMESTAMP(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    started_at TIMESTAMP(6) NULL,
    finished_at TIMESTAMP(6) NULL,
    INDEX idx_task_trace_python_parent (parent_trace_id, created_at),
    INDEX idx_task_trace_python_status (status, heartbeat_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS trace_log (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    trace_id VARCHAR(32) NOT NULL,
    python_trace_id VARCHAR(64),
    source VARCHAR(16) NOT NULL,
    level VARCHAR(16) NOT NULL,
    logger_name VARCHAR(255) NOT NULL,
    message MEDIUMTEXT NOT NULL,
    thread_name VARCHAR(255),
    throwable MEDIUMTEXT,
    logged_at TIMESTAMP(6) NOT NULL,
    INDEX idx_trace_log_trace_id_id (trace_id, id),
    INDEX idx_trace_log_logged_at (logged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
