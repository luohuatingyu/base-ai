CREATE TABLE ai_chat_conversation (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    owner_id BIGINT NOT NULL,
    title VARCHAR(120) NOT NULL,
    settings_json LONGTEXT NOT NULL,
    active_message_id BIGINT NULL,
    lease_until DATETIME(6) NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    INDEX idx_chat_owner_updated (owner_id, updated_at, id)
);
CREATE TABLE ai_chat_message (
    id BIGINT NOT NULL AUTO_INCREMENT PRIMARY KEY,
    conversation_id BIGINT NOT NULL,
    request_id VARCHAR(64) NOT NULL,
    role VARCHAR(16) NOT NULL,
    content_json LONGTEXT NOT NULL,
    status VARCHAR(16) NOT NULL,
    metadata_json TEXT NULL,
    trace_id VARCHAR(64) NULL,
    created_at DATETIME(6) NOT NULL,
    CONSTRAINT uk_chat_request_role UNIQUE (conversation_id, request_id, role),
    CONSTRAINT fk_chat_message_conversation FOREIGN KEY (conversation_id) REFERENCES ai_chat_conversation(id),
    INDEX idx_chat_message_order (conversation_id, id)
);
