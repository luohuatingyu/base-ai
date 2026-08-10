INSERT INTO workflow_node_template(code, name, node_type, description, config_encrypted, system_template,
    template_source, functional_category, enabled)
VALUES
    ('KNOWLEDGE_RETRIEVAL', '知识库检索', 'KNOWLEDGE_RETRIEVAL', '检索知识库并返回匹配片段、来源和分数', '', b'1', 'SYSTEM', 'AI', b'1'),
    ('KNOWLEDGE_UPSERT', '知识库入库', 'KNOWLEDGE_UPSERT', '将文本或文档提取、切片并写入知识库', '', b'1', 'SYSTEM', 'DATA_STORAGE', b'1')
ON DUPLICATE KEY UPDATE code=VALUES(code);
