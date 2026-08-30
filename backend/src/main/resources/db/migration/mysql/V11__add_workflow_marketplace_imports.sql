ALTER TABLE workflow_node_template
    ADD COLUMN external_key VARCHAR(255) NULL AFTER functional_category,
    ADD COLUMN external_version VARCHAR(64) NULL AFTER external_key,
    ADD COLUMN external_publisher VARCHAR(120) NULL AFTER external_version,
    ADD COLUMN external_fingerprint CHAR(64) NULL AFTER external_publisher,
    ADD COLUMN imported_at DATETIME(6) NULL AFTER external_fingerprint,
    ADD CONSTRAINT uk_workflow_template_marketplace UNIQUE (template_source, external_key);
