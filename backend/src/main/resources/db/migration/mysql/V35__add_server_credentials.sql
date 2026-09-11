CREATE TABLE IF NOT EXISTS server_credential (
    id BIGINT AUTO_INCREMENT PRIMARY KEY,
    owner_user_id BIGINT NOT NULL,
    label VARCHAR(120) NOT NULL,
    credential_type VARCHAR(16) NOT NULL DEFAULT 'RSA',
    username VARCHAR(255),
    public_key TEXT,
    private_key_encrypted TEXT,
    certificate TEXT,
    password_encrypted TEXT,
    enabled BOOLEAN NOT NULL DEFAULT TRUE,
    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    voided BOOLEAN NOT NULL DEFAULT FALSE,
    CONSTRAINT fk_server_credential_owner FOREIGN KEY (owner_user_id) REFERENCES sys_user(id),
    INDEX idx_server_credential_owner (owner_user_id, voided, enabled)
);
ALTER TABLE managed_server ADD COLUMN credential_id BIGINT NULL;
ALTER TABLE managed_server ADD CONSTRAINT fk_managed_server_credential FOREIGN KEY (credential_id) REFERENCES server_credential(id);
