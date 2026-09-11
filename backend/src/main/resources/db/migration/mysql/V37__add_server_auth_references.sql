ALTER TABLE managed_server ADD COLUMN key_credential_id BIGINT NULL;
ALTER TABLE managed_server ADD COLUMN password_credential_id BIGINT NULL;
ALTER TABLE managed_server ADD CONSTRAINT fk_server_key_credential FOREIGN KEY (key_credential_id) REFERENCES server_credential(id);
ALTER TABLE managed_server ADD CONSTRAINT fk_server_password_credential FOREIGN KEY (password_credential_id) REFERENCES server_credential(id);
