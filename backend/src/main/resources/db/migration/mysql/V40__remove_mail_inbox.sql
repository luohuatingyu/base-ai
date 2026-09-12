DROP TABLE IF EXISTS sys_mail_account_role;
ALTER TABLE sys_mail_account
    DROP COLUMN IF EXISTS imap_enabled,
    DROP COLUMN IF EXISTS imap_host,
    DROP COLUMN IF EXISTS imap_port,
    DROP COLUMN IF EXISTS imap_username,
    DROP COLUMN IF EXISTS imap_tls_mode,
    DROP COLUMN IF EXISTS imap_password_encrypted;
