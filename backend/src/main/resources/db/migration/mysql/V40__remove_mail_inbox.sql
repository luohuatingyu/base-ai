DROP TABLE IF EXISTS sys_mail_account_role;
ALTER TABLE sys_mail_account
    DROP COLUMN imap_enabled,
    DROP COLUMN imap_host,
    DROP COLUMN imap_port,
    DROP COLUMN imap_username,
    DROP COLUMN imap_tls_mode,
    DROP COLUMN imap_password_encrypted;
