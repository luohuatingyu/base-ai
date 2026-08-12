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

INSERT INTO workflow_plugin_admission(plugin_id,external_services_json,data_types_json,enabled_before_admission)
SELECT id,'[]','[]',enabled FROM workflow_marketplace_plugin
ON DUPLICATE KEY UPDATE plugin_id=VALUES(plugin_id);

UPDATE workflow_marketplace_plugin p
JOIN workflow_plugin_admission a ON a.plugin_id=p.id
SET p.enabled=b'0',p.updated_at=CURRENT_TIMESTAMP(6)
WHERE a.admission_status='PENDING';
