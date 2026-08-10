INSERT INTO workflow_node_template(code, name, node_type, description, config_encrypted, system_template,
    template_source, functional_category, enabled)
VALUES
    ('EMBEDDING', '文本向量化', 'EMBEDDING', '将单个文本或文本数组转换为向量', '', b'1', 'SYSTEM', 'AI', b'1')
ON DUPLICATE KEY UPDATE code=VALUES(code);
