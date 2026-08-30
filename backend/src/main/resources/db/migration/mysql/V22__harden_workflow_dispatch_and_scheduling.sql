ALTER TABLE workflow_run
    ADD INDEX idx_workflow_run_dispatch (status, cancel_requested, deadline_at, execution_instance_id, lease_expires_at);

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
