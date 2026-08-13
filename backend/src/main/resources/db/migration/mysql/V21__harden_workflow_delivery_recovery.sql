ALTER TABLE workflow_trigger_delivery
    ADD COLUMN delivery_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED' AFTER run_id,
    ADD COLUMN last_error VARCHAR(500) NOT NULL DEFAULT '' AFTER delivery_status,
    ADD INDEX idx_workflow_trigger_pending (delivery_status, run_id, received_at);

UPDATE workflow_trigger_delivery
SET delivery_status=CASE WHEN run_id IS NULL THEN 'RECEIVED' ELSE 'ENQUEUED' END
WHERE delivery_status='RECEIVED';

ALTER TABLE workflow_run
    ADD COLUMN deadline_at DATETIME(6) NULL AFTER lease_expires_at,
    ADD COLUMN progress_at DATETIME(6) NULL AFTER deadline_at,
    ADD INDEX idx_workflow_run_deadline (status, deadline_at);

UPDATE workflow_run
SET deadline_at=CASE WHEN status IN ('QUEUED','RUNNING','WAITING')
    THEN DATE_ADD(NOW(6), INTERVAL 1 DAY) ELSE NOW(6) END
WHERE deadline_at IS NULL;

ALTER TABLE workflow_run
    MODIFY COLUMN deadline_at DATETIME(6) NOT NULL;
