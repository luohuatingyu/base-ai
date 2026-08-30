ALTER TABLE workflow_node_template
    MODIFY COLUMN template_source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM';

UPDATE workflow_node_template
SET template_source = 'SYSTEM'
WHERE template_source NOT IN ('SYSTEM', 'N8N', 'DIFY');
