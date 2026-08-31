-- 旧 Worker 共享目录中的解压结果可能已被其他插件污染，禁止迁移到新的独立卷。
-- 保留插件、组件和人工准入状态，仅重新下载并验证所有已完成的固定版本包。
UPDATE workflow_marketplace_plugin_probe
SET probe_status = 'QUEUED',
    compatibility_status = 'PROBING',
    compatibility_reason = '',
    package_fingerprint = NULL,
    result_json = NULL,
    attempt_count = 0,
    next_attempt_at = CURRENT_TIMESTAMP(6),
    lease_owner = NULL,
    lease_expires_at = NULL,
    probed_at = NULL,
    updated_at = CURRENT_TIMESTAMP(6)
WHERE probe_status = 'COMPLETE';
