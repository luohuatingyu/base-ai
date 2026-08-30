CREATE TABLE IF NOT EXISTS workflow_connection (
    id BIGINT NOT NULL AUTO_INCREMENT,
    code VARCHAR(80) NOT NULL,
    name VARCHAR(120) NOT NULL,
    connection_type VARCHAR(24) NOT NULL,
    config_encrypted LONGTEXT NOT NULL,
    owner_user_id BIGINT NOT NULL,
    enabled BIT(1) NOT NULL DEFAULT b'1',
    voided BIT(1) NOT NULL DEFAULT b'0',
    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    updated_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_connection_code UNIQUE (code),
    INDEX idx_workflow_connection_owner (owner_user_id, connection_type, voided)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

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
    received_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (id),
    CONSTRAINT uk_workflow_trigger_event UNIQUE (workflow_id, trigger_node_id, event_id),
    CONSTRAINT fk_workflow_trigger_definition FOREIGN KEY (workflow_id) REFERENCES workflow_definition(id),
    CONSTRAINT fk_workflow_trigger_run FOREIGN KEY (run_id) REFERENCES workflow_run(id),
    INDEX idx_workflow_trigger_received (received_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;

INSERT INTO workflow_node_template(code, name, node_type, description, config_encrypted, system_template)
VALUES
    ('SWITCH', '多路分支', 'SWITCH', '按顺序匹配多个结构化条件', '', b'1'),
    ('MERGE', '结果合并', 'MERGE', '合并多个上游节点结果', '', b'1'),
    ('SUB_WORKFLOW', '子工作流', 'SUB_WORKFLOW', '确定性调用已发布工作流', '', b'1'),
    ('WAIT', '等待', 'WAIT', '等待指定时长后继续执行', '', b'1'),
    ('SET_VARIABLE', '变量设置', 'SET_VARIABLE', '生成新的结构化变量值', '', b'1'),
    ('TEMPLATE', '文本模板', 'TEMPLATE', '使用受限变量引用生成文本', '', b'1'),
    ('JSON_PARSE', 'JSON 解析', 'JSON_PARSE', '将文本解析为 JSON 数据', '', b'1'),
    ('JSON_VALIDATE', 'JSON 校验', 'JSON_VALIDATE', '使用 JSON Schema 校验数据', '', b'1'),
    ('TRANSFORM', '对象转换', 'TRANSFORM', '映射和重组结构化数据', '', b'1'),
    ('FILTER', '数组过滤', 'FILTER', '按结构化条件过滤数组元素', '', b'1'),
    ('SORT', '数组排序', 'SORT', '按字段路径稳定排序数组', '', b'1'),
    ('AGGREGATE', '数组聚合', 'AGGREGATE', '执行计数、求和、平均和极值聚合', '', b'1'),
    ('CSV', 'CSV 转换', 'CSV', '在 CSV 文本与对象数组之间转换', '', b'1'),
    ('QUESTION_CLASSIFIER', '问题分类', 'QUESTION_CLASSIFIER', '使用模型从有限分类中选择结果', '', b'1'),
    ('PARAMETER_EXTRACTOR', '参数提取', 'PARAMETER_EXTRACTOR', '使用模型提取结构化参数', '', b'1'),
    ('STRUCTURED_OUTPUT', '结构化输出', 'STRUCTURED_OUTPUT', '解析并校验结构化输出', '', b'1'),
    ('DOCUMENT_EXTRACTOR', '文档提取', 'DOCUMENT_EXTRACTOR', '从常用文档格式提取正文和元数据', '', b'1'),
    ('WEBHOOK_TRIGGER', 'Webhook 触发', 'WEBHOOK_TRIGGER', '通过签名 Webhook 启动工作流', '', b'1'),
    ('SCHEDULE_TRIGGER', '定时触发', 'SCHEDULE_TRIGGER', '按 Cron 表达式启动工作流', '', b'1'),
    ('EMAIL_SEND', '邮件发送', 'EMAIL_SEND', '使用受管邮件路由发送邮件', '', b'1'),
    ('IM_NOTIFY', '即时通知', 'IM_NOTIFY', '向受控通知 Webhook 发送消息', '', b'1'),
    ('SQL_QUERY', '数据库查询', 'SQL_QUERY', '参数化访问受管 MySQL 或 PostgreSQL', '', b'1'),
    ('REDIS_COMMAND', 'Redis 操作', 'REDIS_COMMAND', '执行受限 Redis 命令', '', b'1'),
    ('S3_OBJECT', '对象存储', 'S3_OBJECT', '访问受管 S3 兼容对象存储', '', b'1'),
    ('KAFKA_PUBLISH', 'Kafka 发布', 'KAFKA_PUBLISH', '向受管 Kafka Topic 发布消息', '', b'1'),
    ('KAFKA_TRIGGER', 'Kafka 触发', 'KAFKA_TRIGGER', '消费 Kafka 消息并启动工作流', '', b'1'),
    ('RABBITMQ_PUBLISH', 'RabbitMQ 发布', 'RABBITMQ_PUBLISH', '向受管 RabbitMQ Exchange 发布消息', '', b'1'),
    ('RABBITMQ_TRIGGER', 'RabbitMQ 触发', 'RABBITMQ_TRIGGER', '消费 RabbitMQ 消息并启动工作流', '', b'1')
ON DUPLICATE KEY UPDATE code = VALUES(code);
