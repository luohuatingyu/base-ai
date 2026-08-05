UPDATE sys_api_key SET secret_encrypted = '' WHERE secret_encrypted IS NULL;
ALTER TABLE sys_api_key MODIFY COLUMN secret_encrypted TEXT NOT NULL;
