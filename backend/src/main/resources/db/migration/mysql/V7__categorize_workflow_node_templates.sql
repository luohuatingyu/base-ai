ALTER TABLE workflow_node_template
    ADD COLUMN template_source VARCHAR(16) NOT NULL DEFAULT 'SYSTEM' AFTER system_template,
    ADD COLUMN functional_category VARCHAR(32) NOT NULL DEFAULT 'BASIC' AFTER template_source,
    ADD INDEX idx_workflow_template_catalog (functional_category, template_source, enabled, voided);

UPDATE workflow_node_template
SET template_source = 'SYSTEM',
    functional_category = CASE
        WHEN node_type IN ('START', 'END') THEN 'BASIC'
        WHEN node_type IN ('LLM', 'AGENT', 'QUESTION_CLASSIFIER', 'PARAMETER_EXTRACTOR', 'STRUCTURED_OUTPUT') THEN 'AI'
        WHEN node_type IN ('CONDITION', 'SWITCH', 'ITERATION', 'LOOP', 'MERGE', 'SUB_WORKFLOW', 'WAIT') THEN 'FLOW_CONTROL'
        WHEN node_type IN ('SET_VARIABLE', 'JSON_PARSE', 'JSON_VALIDATE', 'TRANSFORM', 'FILTER', 'SORT', 'AGGREGATE') THEN 'DATA_TRANSFORM'
        WHEN node_type IN ('TEMPLATE', 'CSV', 'DOCUMENT_EXTRACTOR') THEN 'TEXT_DOCUMENT'
        WHEN node_type = 'HTTP' THEN 'NETWORK_API'
        WHEN node_type IN ('WEBHOOK_TRIGGER', 'SCHEDULE_TRIGGER') THEN 'TRIGGER'
        WHEN node_type IN ('EMAIL_SEND', 'IM_NOTIFY') THEN 'NOTIFICATION'
        WHEN node_type IN ('SQL_QUERY', 'REDIS_COMMAND', 'S3_OBJECT') THEN 'DATA_STORAGE'
        WHEN node_type IN ('KAFKA_PUBLISH', 'KAFKA_TRIGGER', 'RABBITMQ_PUBLISH', 'RABBITMQ_TRIGGER') THEN 'MESSAGE_QUEUE'
        ELSE 'BASIC'
    END;
