CREATE TABLE IF NOT EXISTS automation_api_trigger_config (
    id BIGSERIAL PRIMARY KEY,
    name VARCHAR(120) NOT NULL,
    description VARCHAR(500) NOT NULL DEFAULT '',
    http_method VARCHAR(10) NOT NULL DEFAULT 'GET',
    url TEXT NOT NULL,
    headers_encrypted TEXT NOT NULL DEFAULT '',
    query_params TEXT NOT NULL DEFAULT '',
    request_body_encrypted TEXT NOT NULL DEFAULT '',
    content_type VARCHAR(120) NOT NULL DEFAULT 'application/json',
    cron_expression VARCHAR(80),
    timeout_seconds INT NOT NULL DEFAULT 30,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    voided BOOLEAN NOT NULL DEFAULT FALSE,
    auth_enabled BOOLEAN NOT NULL DEFAULT FALSE,
    auth_url TEXT NOT NULL DEFAULT '',
    auth_method VARCHAR(10) NOT NULL DEFAULT 'POST',
    auth_body_encrypted TEXT NOT NULL DEFAULT '',
    auth_content_type VARCHAR(120) NOT NULL DEFAULT 'application/json',
    auth_token_path VARCHAR(200) NOT NULL DEFAULT 'data.token',
    auth_token_header VARCHAR(120) NOT NULL DEFAULT 'Authorization',
    auth_token_prefix VARCHAR(40) NOT NULL DEFAULT 'Bearer ',
    owner_user_id BIGINT NOT NULL,
    last_trigger_at TIMESTAMP,
    last_status VARCHAR(20),
    last_result TEXT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS automation_api_trigger_log (
    id BIGSERIAL PRIMARY KEY,
    config_id BIGINT NOT NULL REFERENCES automation_api_trigger_config(id),
    job_id VARCHAR(32),
    trigger_type VARCHAR(20) NOT NULL,
    status VARCHAR(20) NOT NULL,
    http_status INT,
    duration_ms BIGINT,
    response_summary TEXT,
    error_message TEXT,
    triggered_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_api_trigger_enabled ON automation_api_trigger_config(enabled, voided);
CREATE INDEX IF NOT EXISTS idx_api_trigger_log_config ON automation_api_trigger_log(config_id, triggered_at DESC);
