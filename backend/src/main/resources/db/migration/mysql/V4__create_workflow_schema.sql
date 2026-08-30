CREATE TABLE IF NOT EXISTS workflow_node_template (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    node_type VARCHAR(24) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    config_encrypted LONGTEXT NOT NULL,
    system_template BIT(1) NOT NULL DEFAULT b'0',
    enabled BIT(1) NOT NULL DEFAULT b'1',
    voided BIT(1) NOT NULL DEFAULT b'0',
    created_by BIGINT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_node_template_code UNIQUE (code),
    INDEX idx_workflow_template_type (node_type, enabled, voided)
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
    created_by BIGINT NOT NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_version UNIQUE (workflow_id, version_number),
    CONSTRAINT fk_workflow_version_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    cancel_requested BIT(1) NOT NULL DEFAULT b'0',
    started_at DATETIME(6) NULL,
    finished_at DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT fk_workflow_run_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id),
    CONSTRAINT fk_workflow_run_version FOREIGN KEY (workflow_version_id) REFERENCES workflow_version(id),
    CONSTRAINT fk_workflow_run_parent FOREIGN KEY (parent_run_id) REFERENCES workflow_run(id),
    INDEX idx_workflow_run_workflow (workflow_id, created_at),
    INDEX idx_workflow_run_owner (owner_user_id, created_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

CREATE TABLE IF NOT EXISTS workflow_node_run (
    id BIGINT NOT NULL AUTO_INCREMENT,
    workflow_run_id VARCHAR(36) NOT NULL,
    node_id VARCHAR(100) NOT NULL,
    node_name VARCHAR(120) NOT NULL,
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

INSERT INTO workflow_node_template(code, name, node_type, description, config_encrypted, system_template)
VALUES
    ('START', '开始', 'START', '定义工作流输入并启动执行', '', b'1'),
    ('END', '结束', 'END', '汇总工作流输出并结束执行', '', b'1'),
    ('LLM', 'LLM', 'LLM', '调用平台模型路由生成内容', '', b'1'),
    ('HTTP', 'HTTP 请求', 'HTTP', '按安全策略调用 HTTP 接口', '', b'1'),
    ('AGENT', 'Agent', 'AGENT', '由模型选择并调用受控工具', '', b'1'),
    ('CONDITION', '条件分支', 'CONDITION', '按结构化条件选择执行分支', '', b'1'),
    ('ITERATION', '数组迭代', 'ITERATION', '顺序遍历数组并执行子画布', '', b'1'),
    ('LOOP', '条件循环', 'LOOP', '在次数上限内按条件执行子画布', '', b'1')
ON DUPLICATE KEY UPDATE code = VALUES(code);
