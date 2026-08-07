ALTER TABLE workflow_node_template
    MODIFY COLUMN template_source VARCHAR(16) NOT NULL DEFAULT 'CUSTOM';

UPDATE workflow_node_template
SET template_source = CASE WHEN system_template = b'1' THEN 'SYSTEM' ELSE 'CUSTOM' END;
