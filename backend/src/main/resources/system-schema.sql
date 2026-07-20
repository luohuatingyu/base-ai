CREATE TABLE IF NOT EXISTS task_job (
    job_id VARCHAR(32) PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    task_type VARCHAR(64) NOT NULL,
    status VARCHAR(24) NOT NULL,
    request_path VARCHAR(255),
    error_message VARCHAR(1000),
    started_at TIMESTAMP(6) NOT NULL,
    finished_at TIMESTAMP(6) NULL,
    INDEX idx_task_job_owner_started (owner_user_id, started_at),
    INDEX idx_task_job_status_started (status, started_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;

CREATE TABLE IF NOT EXISTS task_job_log (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    job_id VARCHAR(32) NOT NULL,
    python_job_id VARCHAR(64),
    source VARCHAR(16) NOT NULL,
    level VARCHAR(16) NOT NULL,
    logger_name VARCHAR(255) NOT NULL,
    message MEDIUMTEXT NOT NULL,
    thread_name VARCHAR(255),
    throwable MEDIUMTEXT,
    logged_at TIMESTAMP(6) NOT NULL,
    INDEX idx_task_job_log_job_id_id (job_id, id),
    INDEX idx_task_job_log_logged_at (logged_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
