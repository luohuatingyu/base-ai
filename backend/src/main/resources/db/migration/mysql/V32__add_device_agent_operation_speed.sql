ALTER TABLE automation_device_agent_ida_config
    ADD COLUMN operation_speed VARCHAR(16) NOT NULL DEFAULT 'STANDARD' AFTER base_ida_local_port,
    ADD COLUMN wireless_source_poll_interval_seconds INT NOT NULL DEFAULT 10 AFTER operation_speed,
    ADD COLUMN wireless_source_max_attempts INT NOT NULL DEFAULT 12
        AFTER wireless_source_poll_interval_seconds,
    ADD CONSTRAINT ck_device_agent_operation_speed
        CHECK (operation_speed IN ('SLOW', 'STANDARD', 'FAST')),
    ADD CONSTRAINT ck_device_agent_wireless_poll_interval
        CHECK (wireless_source_poll_interval_seconds BETWEEN 1 AND 60),
    ADD CONSTRAINT ck_device_agent_wireless_max_attempts
        CHECK (wireless_source_max_attempts BETWEEN 2 AND 30);
