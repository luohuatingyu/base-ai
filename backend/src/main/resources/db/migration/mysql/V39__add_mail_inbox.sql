ALTER TABLE sys_mail_account ADD COLUMN imap_enabled BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE sys_mail_account ADD COLUMN imap_host VARCHAR(255);
ALTER TABLE sys_mail_account ADD COLUMN imap_port INT;
ALTER TABLE sys_mail_account ADD COLUMN imap_username VARCHAR(255);
ALTER TABLE sys_mail_account ADD COLUMN imap_tls_mode VARCHAR(16);
ALTER TABLE sys_mail_account ADD COLUMN imap_password_encrypted TEXT;
CREATE TABLE sys_mail_account_role (
    account_id BIGINT NOT NULL,
    role_id BIGINT NOT NULL,
    PRIMARY KEY (account_id, role_id),
    CONSTRAINT fk_mail_role_account FOREIGN KEY (account_id) REFERENCES sys_mail_account(id) ON DELETE CASCADE,
    CONSTRAINT fk_mail_role_role FOREIGN KEY (role_id) REFERENCES sys_role(id) ON DELETE CASCADE
);
