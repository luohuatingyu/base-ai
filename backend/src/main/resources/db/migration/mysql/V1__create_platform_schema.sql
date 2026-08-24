-- 平台 MySQL 基线 Schema。
-- 本文件由历史迁移 V1~V23 合并而成，直接给出各表的最终结构，不再保留中间演进步骤。
-- 建表顺序按外键依赖排列；workflow_definition 与 workflow_version 互相引用，其外键在两表建好后补充。

-- ==================== 组织与权限 ====================

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

-- ==================== 字典与日志 ====================

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

-- ==================== 平台配置 ====================

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

-- ==================== 模型与邮件 ====================

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

-- ==================== API 凭证 ====================

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

-- ==================== 任务链路与日志 ====================

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

-- ==================== 工作流定义与执行 ====================

CREATE TABLE IF NOT EXISTS workflow_node_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    node_type VARCHAR(24) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    localization_json LONGTEXT NOT NULL,
    config_encrypted LONGTEXT NOT NULL,
    system_template BIT(1) NOT NULL DEFAULT b'0',
    template_source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM',
    functional_category VARCHAR(32) NOT NULL DEFAULT 'BASIC',
    external_key VARCHAR(255) NULL,
    external_version VARCHAR(64) NULL,
    external_publisher VARCHAR(120) NULL,
    external_fingerprint CHAR(64) NULL,
    imported_at DATETIME(6) NULL,
    enabled BIT(1) NOT NULL DEFAULT b'1',
    voided BIT(1) NOT NULL DEFAULT b'0',
    created_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_node_template_code UNIQUE (code),
    CONSTRAINT uk_workflow_template_marketplace UNIQUE (template_source, external_key),
    INDEX idx_workflow_template_type (node_type, enabled, voided),
    INDEX idx_workflow_template_catalog (functional_category, template_source, enabled, voided)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_definition (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
    current_version_id BIGINT NULL,
    published_version_id BIGINT NULL,
    revision BIGINT NOT NULL DEFAULT 0,
    owner_user_id BIGINT NOT NULL,
    enabled BIT(1) NOT NULL DEFAULT b'1',
    voided BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_definition_code UNIQUE (code),
    INDEX idx_workflow_definition_status (status, enabled, voided)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_version (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    version_number INT NOT NULL,
    graph_json LONGTEXT NOT NULL,
    input_schema_json LONGTEXT NOT NULL,
    template_snapshot_json LONGTEXT NOT NULL,
    connection_snapshot_complete BIT(1) NOT NULL DEFAULT b'0',
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_version UNIQUE (workflow_id, version_number),
    CONSTRAINT fk_workflow_version_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- workflow_definition 与 workflow_version 互相引用，需在两表建好后补充外键。
ALTER TABLE workflow_definition
    ADD CONSTRAINT fk_workflow_current_version FOREIGN KEY (current_version_id) REFERENCES workflow_version(id),
    ADD CONSTRAINT fk_workflow_published_version FOREIGN KEY (published_version_id) REFERENCES workflow_version(id);

CREATE TABLE IF NOT EXISTS workflow_run (
    id VARCHAR(36) NOT NULL,
    workflow_id BIGINT NOT NULL,
    workflow_version_id BIGINT NOT NULL,
    parent_run_id VARCHAR(36) NULL,
    trace_id VARCHAR(32) NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    input_encrypted LONGTEXT NOT NULL,
    output_encrypted LONGTEXT NOT NULL,
    error_message VARCHAR(2000) NULL,
    owner_user_id BIGINT NOT NULL,
    api_key_id BIGINT NULL,
    execution_instance_id VARCHAR(120) NULL,
    lease_expires_at DATETIME(6) NULL,
    deadline_at DATETIME(6) NOT NULL,
    progress_at DATETIME(6) NULL,
    log_bytes BIGINT NOT NULL DEFAULT 0,
    cancel_requested BIT(1) NOT NULL DEFAULT b'0',
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_workflow_run_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id),
    CONSTRAINT fk_workflow_run_version FOREIGN KEY (workflow_version_id) REFERENCES workflow_version(id),
    CONSTRAINT fk_workflow_run_parent FOREIGN KEY (parent_run_id) REFERENCES workflow_run(id),
    CONSTRAINT fk_workflow_run_api_key FOREIGN KEY (api_key_id) REFERENCES sys_api_key(id),
    INDEX idx_workflow_run_workflow (workflow_id, created_at),
    INDEX idx_workflow_run_owner (owner_user_id, created_at),
    INDEX idx_workflow_run_lease (status, lease_expires_at),
    INDEX idx_workflow_run_api_key (api_key_id, created_at),
    INDEX idx_workflow_run_deadline (status, deadline_at),
    INDEX idx_workflow_run_dispatch (status, cancel_requested, deadline_at, execution_instance_id, lease_expires_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_node_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_run_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_name VARCHAR(120) NOT NULL,
    default_node_name VARCHAR(120) NOT NULL DEFAULT '',
    localization_json LONGTEXT NOT NULL,
    node_type VARCHAR(24) NOT NULL,
    sequence_no INT NOT NULL,
    iteration_path VARCHAR(200) NOT NULL DEFAULT '',
    status VARCHAR(20) NOT NULL,
    input_encrypted LONGTEXT NOT NULL,
    output_encrypted LONGTEXT NOT NULL,
    error_message VARCHAR(2000) NULL,
    started_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    finished_at DATETIME(6) NULL,
    PRIMARY KEY (id),
    CONSTRAINT fk_workflow_node_run_run FOREIGN KEY (workflow_run_id) REFERENCES workflow_run(id),
    INDEX idx_workflow_node_run_run (workflow_run_id, sequence_no)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 插件市场运行时 ====================

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
    localization_json LONGTEXT NOT NULL,
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

CREATE TABLE IF NOT EXISTS workflow_marketplace_plugin_probe (
    id BIGINT NOT NULL AUTO_INCREMENT,
    source VARCHAR(16) NOT NULL,
    catalog_external_key VARCHAR(255) NOT NULL,
    package_key VARCHAR(255) NOT NULL,
    package_version VARCHAR(64) NOT NULL,
    package_fingerprint CHAR(64) NULL,
    probe_status VARCHAR(24) NOT NULL DEFAULT 'QUEUED',
    compatibility_status VARCHAR(24) NOT NULL DEFAULT 'PROBING',
    compatibility_reason VARCHAR(500) NOT NULL DEFAULT '',
    result_json LONGTEXT NULL,
    attempt_count INT NOT NULL DEFAULT 0,
    next_attempt_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    lease_owner VARCHAR(120) NULL,
    lease_expires_at DATETIME(6) NULL,
    last_accessed_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    probed_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_marketplace_plugin_probe UNIQUE (source, package_key, package_version),
    INDEX idx_workflow_marketplace_probe_queue (probe_status, next_attempt_at, lease_expires_at),
    INDEX idx_workflow_marketplace_probe_retention (last_accessed_at, probe_status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_plugin_admission (
    plugin_id BIGINT NOT NULL,
    license_name VARCHAR(160) NOT NULL DEFAULT '',
    license_url VARCHAR(500) NOT NULL DEFAULT '',
    external_services_json LONGTEXT NOT NULL,
    no_external_service BIT(1) NOT NULL DEFAULT b'0',
    data_types_json LONGTEXT NOT NULL,
    data_notes VARCHAR(1000) NOT NULL DEFAULT '',
    admission_status VARCHAR(24) NOT NULL DEFAULT 'PENDING',
    review_note VARCHAR(1000) NOT NULL DEFAULT '',
    reviewed_by BIGINT NULL,
    reviewed_at DATETIME(6) NULL,
    enabled_before_admission BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (plugin_id),
    CONSTRAINT fk_workflow_plugin_admission_plugin FOREIGN KEY (plugin_id)
        REFERENCES workflow_marketplace_plugin(id),
    INDEX idx_workflow_plugin_admission_status (admission_status, updated_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 工作流连接与凭证 ====================

CREATE TABLE IF NOT EXISTS workflow_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    connection_type VARCHAR(24) NOT NULL,
    plugin_component_id BIGINT NULL,
    config_encrypted LONGTEXT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    enabled BIT(1) NOT NULL DEFAULT b'1',
    security_revision BIGINT NOT NULL DEFAULT 1,
    vector_status VARCHAR(16) NOT NULL DEFAULT 'UNKNOWN',
    vector_engine VARCHAR(32) NULL,
    vector_version VARCHAR(64) NULL,
    vector_checked_at DATETIME(6) NULL,
    vector_error VARCHAR(500) NOT NULL DEFAULT '',
    voided BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_connection_code UNIQUE (code),
    CONSTRAINT fk_workflow_connection_plugin_component FOREIGN KEY (plugin_component_id)
        REFERENCES workflow_marketplace_component(id),
    INDEX idx_workflow_connection_owner (owner_user_id, connection_type, voided),
    INDEX idx_workflow_connection_plugin_component (plugin_component_id, voided)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_version_connection (
    workflow_version_id BIGINT NOT NULL,
    connection_id BIGINT NOT NULL,
    security_revision BIGINT NOT NULL,
    PRIMARY KEY (workflow_version_id, connection_id),
    CONSTRAINT fk_workflow_version_connection_version FOREIGN KEY (workflow_version_id) REFERENCES workflow_version(id),
    CONSTRAINT fk_workflow_version_connection_connection FOREIGN KEY (connection_id) REFERENCES workflow_connection(id),
    INDEX idx_workflow_version_connection_target (connection_id, security_revision)
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

-- ==================== 工作流触发与调度 ====================

CREATE TABLE IF NOT EXISTS workflow_wait_state (
    workflow_run_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    resume_at DATETIME(6) NOT NULL,
    state_encrypted LONGTEXT NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'WAITING',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workflow_run_id),
    CONSTRAINT fk_workflow_wait_run FOREIGN KEY (workflow_run_id) REFERENCES workflow_run(id),
    INDEX idx_workflow_wait_resume (status, resume_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_trigger_delivery (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_id BIGINT NOT NULL,
    trigger_node_id VARCHAR(100) NOT NULL,
    event_id VARCHAR(160) NOT NULL,
    run_id VARCHAR(36) NULL,
    delivery_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
    last_error VARCHAR(500) NOT NULL DEFAULT '',
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_trigger_event UNIQUE (workflow_id, trigger_node_id, event_id),
    CONSTRAINT fk_workflow_trigger_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id),
    CONSTRAINT fk_workflow_trigger_run FOREIGN KEY (run_id) REFERENCES workflow_run(id),
    INDEX idx_workflow_trigger_received (received_at),
    INDEX idx_workflow_trigger_pending (delivery_status, run_id, received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_schedule_state (
    workflow_id BIGINT NOT NULL,
    workflow_version_id BIGINT NOT NULL,
    trigger_node_id VARCHAR(100) NOT NULL,
    cron_expression VARCHAR(120) NOT NULL,
    zone_id VARCHAR(80) NOT NULL,
    next_fire_at DATETIME(6) NOT NULL,
    last_fire_at DATETIME(6) NULL,
    active BIT(1) NOT NULL DEFAULT b'1',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (workflow_id, trigger_node_id),
    CONSTRAINT fk_workflow_schedule_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id),
    CONSTRAINT fk_workflow_schedule_version FOREIGN KEY (workflow_version_id) REFERENCES workflow_version(id),
    INDEX idx_workflow_schedule_due (active, next_fire_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS sys_api_key_workflow (
    api_key_id BIGINT NOT NULL,
    workflow_id BIGINT NOT NULL,
    PRIMARY KEY (api_key_id, workflow_id),
    CONSTRAINT fk_api_key_workflow_key FOREIGN KEY (api_key_id) REFERENCES sys_api_key(id),
    CONSTRAINT fk_api_key_workflow_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id),
    INDEX idx_api_key_workflow_definition (workflow_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 知识库 ====================

CREATE TABLE IF NOT EXISTS knowledge_base (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    connection_id BIGINT NOT NULL,
    storage_type VARCHAR(24) NOT NULL,
    resource_mode VARCHAR(16) NOT NULL,
    resource_name VARCHAR(120) NOT NULL,
    embedding_model_id BIGINT NOT NULL,
    embedding_dimension INT NULL,
    distance_metric VARCHAR(16) NOT NULL DEFAULT 'COSINE',
    chunk_size INT NOT NULL DEFAULT 400,
    chunk_overlap INT NOT NULL DEFAULT 60,
    owner_user_id BIGINT NOT NULL,
    enabled BIT(1) NOT NULL DEFAULT b'1',
    voided BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_knowledge_base_code UNIQUE (code),
    CONSTRAINT fk_knowledge_base_connection FOREIGN KEY (connection_id) REFERENCES workflow_connection(id),
    CONSTRAINT fk_knowledge_base_embedding_model FOREIGN KEY (embedding_model_id) REFERENCES sys_llm_model(id),
    INDEX idx_knowledge_base_owner (owner_user_id, voided, enabled)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_document (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    file_name VARCHAR(255) NOT NULL,
    content_type VARCHAR(120) NOT NULL DEFAULT 'application/octet-stream',
    content_hash CHAR(64) NOT NULL,
    status VARCHAR(20) NOT NULL DEFAULT 'INDEXING',
    chunk_count INT NOT NULL DEFAULT 0,
    error_message VARCHAR(500) NOT NULL DEFAULT '',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_document_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT uk_knowledge_document_hash UNIQUE (knowledge_base_id, content_hash),
    INDEX idx_knowledge_document_base (knowledge_base_id, status)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS knowledge_chunk (
    id BIGINT NOT NULL AUTO_INCREMENT,
    knowledge_base_id BIGINT NOT NULL,
    document_id BIGINT NOT NULL,
    chunk_index INT NOT NULL,
    content_encrypted LONGTEXT NOT NULL,
    content_hash CHAR(64) NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_knowledge_chunk_base FOREIGN KEY (knowledge_base_id) REFERENCES knowledge_base(id),
    CONSTRAINT fk_knowledge_chunk_document FOREIGN KEY (document_id) REFERENCES knowledge_document(id),
    CONSTRAINT uk_knowledge_chunk_index UNIQUE (document_id, chunk_index),
    INDEX idx_knowledge_chunk_base (knowledge_base_id, id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== API 触发自动化 ====================

CREATE TABLE IF NOT EXISTS automation_api_trigger_config (
    id BIGINT NOT NULL AUTO_INCREMENT,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    http_method VARCHAR(10) NOT NULL DEFAULT 'GET',
    url TEXT NOT NULL,
    headers_encrypted MEDIUMTEXT NOT NULL,
    query_params MEDIUMTEXT NOT NULL,
    request_body_encrypted MEDIUMTEXT NOT NULL,
    content_type VARCHAR(120) NOT NULL DEFAULT 'application/json',
    cron_expression VARCHAR(80) NULL,
    timeout_seconds INT NOT NULL DEFAULT 30,
    enabled BIT(1) NOT NULL DEFAULT b'1',
    voided BIT(1) NOT NULL DEFAULT b'0',
    auth_enabled BIT(1) NOT NULL DEFAULT b'0',
    auth_url TEXT NOT NULL,
    auth_method VARCHAR(10) NOT NULL DEFAULT 'POST',
    auth_body_encrypted MEDIUMTEXT NOT NULL,
    auth_content_type VARCHAR(120) NOT NULL DEFAULT 'application/json',
    auth_token_path VARCHAR(200) NOT NULL DEFAULT 'data.token',
    auth_token_header VARCHAR(120) NOT NULL DEFAULT 'Authorization',
    auth_token_prefix VARCHAR(40) NOT NULL DEFAULT 'Bearer ',
    owner_user_id BIGINT NOT NULL,
    last_trigger_at DATETIME(6) NULL,
    last_status VARCHAR(20) NULL,
    last_result MEDIUMTEXT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    INDEX idx_api_trigger_enabled (enabled, voided)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS automation_api_trigger_log (
    id BIGINT NOT NULL AUTO_INCREMENT,
    config_id BIGINT NOT NULL,
    trace_id VARCHAR(32) NULL,
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    http_status INT NULL,
    duration_ms BIGINT NULL,
    response_summary MEDIUMTEXT NULL,
    error_message MEDIUMTEXT NULL,
    triggered_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_api_trigger_log_config FOREIGN KEY (config_id) REFERENCES automation_api_trigger_config(id),
    INDEX idx_api_trigger_log_config (config_id, triggered_at DESC)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

-- ==================== 内置节点模板 ====================

INSERT INTO workflow_node_template(code, name, node_type, description, localization_json, config_encrypted,
    system_template, template_source, functional_category, enabled)
VALUES
    ('START', '开始', 'START', '定义工作流输入并启动执行', '{}', '', b'1', 'SYSTEM', 'BASIC', b'1'),
    ('END', '结束', 'END', '汇总工作流输出并结束执行', '{}', '', b'1', 'SYSTEM', 'BASIC', b'1'),
    ('LLM', 'LLM', 'LLM', '调用平台模型路由生成内容', '{}', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('HTTP', 'HTTP 请求', 'HTTP', '按安全策略调用 HTTP 接口', '{}', '', b'1', 'SYSTEM', 'NETWORK_API', b'1'),
    ('AGENT', 'Agent', 'AGENT', '由模型选择并调用受控工具', '{}', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('CONDITION', '条件分支', 'CONDITION', '按结构化条件选择执行分支', '{}', '', b'1', 'SYSTEM', 'FLOW_CONTROL', b'1'),
    ('ITERATION', '数组迭代', 'ITERATION', '顺序遍历数组并执行子画布', '{}', '', b'1', 'SYSTEM', 'FLOW_CONTROL', b'1'),
    ('LOOP', '条件循环', 'LOOP', '在次数上限内按条件执行子画布', '{}', '', b'1', 'SYSTEM', 'FLOW_CONTROL', b'1'),
    ('SWITCH', '多路分支', 'SWITCH', '按顺序匹配多个结构化条件', '{}', '', b'1', 'SYSTEM', 'FLOW_CONTROL', b'1'),
    ('MERGE', '结果合并', 'MERGE', '合并多个上游节点结果', '{}', '', b'1', 'SYSTEM', 'FLOW_CONTROL', b'1'),
    ('SUB_WORKFLOW', '子工作流', 'SUB_WORKFLOW', '确定性调用已发布工作流', '{}', '', b'1', 'SYSTEM', 'FLOW_CONTROL', b'1'),
    ('WAIT', '等待', 'WAIT', '等待指定时长后继续执行', '{}', '', b'1', 'SYSTEM', 'FLOW_CONTROL', b'1'),
    ('SET_VARIABLE', '变量设置', 'SET_VARIABLE', '生成新的结构化变量值', '{}', '', b'1', 'SYSTEM', 'DATA_TRANSFORM', b'1'),
    ('TEMPLATE', '文本模板', 'TEMPLATE', '使用受限变量引用生成文本', '{}', '', b'1', 'SYSTEM', 'TEXT_DOCUMENT', b'1'),
    ('JSON_PARSE', 'JSON 解析', 'JSON_PARSE', '将文本解析为 JSON 数据', '{}', '', b'1', 'SYSTEM', 'DATA_TRANSFORM', b'1'),
    ('JSON_VALIDATE', 'JSON 校验', 'JSON_VALIDATE', '使用 JSON Schema 校验数据', '{}', '', b'1', 'SYSTEM', 'DATA_TRANSFORM', b'1'),
    ('TRANSFORM', '对象转换', 'TRANSFORM', '映射和重组结构化数据', '{}', '', b'1', 'SYSTEM', 'DATA_TRANSFORM', b'1'),
    ('FILTER', '数组过滤', 'FILTER', '按结构化条件过滤数组元素', '{}', '', b'1', 'SYSTEM', 'DATA_TRANSFORM', b'1'),
    ('SORT', '数组排序', 'SORT', '按字段路径稳定排序数组', '{}', '', b'1', 'SYSTEM', 'DATA_TRANSFORM', b'1'),
    ('AGGREGATE', '数组聚合', 'AGGREGATE', '执行计数、求和、平均和极值聚合', '{}', '', b'1', 'SYSTEM', 'DATA_TRANSFORM', b'1'),
    ('CSV', 'CSV 转换', 'CSV', '在 CSV 文本与对象数组之间转换', '{}', '', b'1', 'SYSTEM', 'TEXT_DOCUMENT', b'1'),
    ('QUESTION_CLASSIFIER', '问题分类', 'QUESTION_CLASSIFIER', '使用模型从有限分类中选择结果', '{}', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('PARAMETER_EXTRACTOR', '参数提取', 'PARAMETER_EXTRACTOR', '使用模型提取结构化参数', '{}', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('STRUCTURED_OUTPUT', '结构化输出', 'STRUCTURED_OUTPUT', '解析并校验结构化输出', '{}', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('DOCUMENT_EXTRACTOR', '文档提取', 'DOCUMENT_EXTRACTOR', '从常用文档格式提取正文和元数据', '{}', '', b'1', 'SYSTEM', 'TEXT_DOCUMENT', b'1'),
    ('WEBHOOK_TRIGGER', 'Webhook 触发', 'WEBHOOK_TRIGGER', '通过签名 Webhook 启动工作流', '{}', '', b'1', 'SYSTEM', 'TRIGGER', b'1'),
    ('SCHEDULE_TRIGGER', '定时触发', 'SCHEDULE_TRIGGER', '按 Cron 表达式启动工作流', '{}', '', b'1', 'SYSTEM', 'TRIGGER', b'1'),
    ('EMAIL_SEND', '邮件发送', 'EMAIL_SEND', '使用受管邮件路由发送邮件', '{}', '', b'1', 'SYSTEM', 'NOTIFICATION', b'1'),
    ('IM_NOTIFY', '即时通知', 'IM_NOTIFY', '向受控通知 Webhook 发送消息', '{}', '', b'1', 'SYSTEM', 'NOTIFICATION', b'1'),
    ('SQL_QUERY', '数据库查询', 'SQL_QUERY', '参数化访问受管 MySQL 或 PostgreSQL', '{}', '', b'1', 'SYSTEM', 'DATA_STORAGE', b'1'),
    ('REDIS_COMMAND', 'Redis 操作', 'REDIS_COMMAND', '执行受限 Redis 命令', '{}', '', b'1', 'SYSTEM', 'DATA_STORAGE', b'1'),
    ('S3_OBJECT', '对象存储', 'S3_OBJECT', '访问受管 S3 兼容对象存储', '{}', '', b'1', 'SYSTEM', 'DATA_STORAGE', b'1'),
    ('KAFKA_PUBLISH', 'Kafka 发布', 'KAFKA_PUBLISH', '向受管 Kafka Topic 发布消息', '{}', '', b'1', 'SYSTEM', 'MESSAGE_QUEUE', b'1'),
    ('KAFKA_TRIGGER', 'Kafka 触发', 'KAFKA_TRIGGER', '消费 Kafka 消息并启动工作流', '{}', '', b'1', 'SYSTEM', 'MESSAGE_QUEUE', b'1'),
    ('RABBITMQ_PUBLISH', 'RabbitMQ 发布', 'RABBITMQ_PUBLISH', '向受管 RabbitMQ Exchange 发布消息', '{}', '', b'1', 'SYSTEM', 'MESSAGE_QUEUE', b'1'),
    ('RABBITMQ_TRIGGER', 'RabbitMQ 触发', 'RABBITMQ_TRIGGER', '消费 RabbitMQ 消息并启动工作流', '{}', '', b'1', 'SYSTEM', 'MESSAGE_QUEUE', b'1'),
    ('RAG', '知识库问答', 'RAG', '检索知识库上下文并调用文本模型生成带引用回答', '{}', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('KNOWLEDGE_RETRIEVAL', '知识库检索', 'KNOWLEDGE_RETRIEVAL', '检索知识库并返回匹配片段、来源和分数', '{}', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('KNOWLEDGE_UPSERT', '知识库入库', 'KNOWLEDGE_UPSERT', '将文本或文档提取、切片并写入知识库', '{}', '', b'1', 'SYSTEM', 'DATA_STORAGE', b'1'),
    ('EMBEDDING', '文本向量化', 'EMBEDDING', '将单个文本或文本数组转换为向量', '{}', '', b'1', 'SYSTEM', 'AI', b'1')
ON DUPLICATE KEY UPDATE code = VALUES(code);
