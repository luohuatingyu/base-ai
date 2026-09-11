ALTER TABLE data_sync_plan
    ADD COLUMN server_id BIGINT NULL AFTER target_connection_id,
    ADD CONSTRAINT fk_data_sync_plan_server FOREIGN KEY (server_id) REFERENCES managed_server(id),
    ADD INDEX idx_data_sync_plan_server (server_id, voided, enabled);

ALTER TABLE data_sync_run
    ADD COLUMN server_id BIGINT NULL AFTER owner_user_id,
    ADD COLUMN agent_job_id VARCHAR(32) NULL AFTER server_id,
    ADD COLUMN active_slot TINYINT NULL AFTER status,
    ADD CONSTRAINT fk_data_sync_run_server FOREIGN KEY (server_id) REFERENCES managed_server(id),
    ADD CONSTRAINT uk_data_sync_run_agent_job UNIQUE (agent_job_id),
    ADD CONSTRAINT uk_data_sync_run_active UNIQUE (plan_id, active_slot),
    ADD INDEX idx_data_sync_run_server (server_id, started_at);
