-- 历史数据库曾使用独立的每分钟限流字段；新模型改用类型和数量后，该字段需允许为空。
-- 全新数据库不存在该列，因此仅在检测到历史列时执行兼容修改。
SET @legacy_rate_limit_column_sql = (
    SELECT IF(
        COUNT(*) > 0,
        'ALTER TABLE sys_api_key MODIFY COLUMN rate_limit_per_minute INT NULL',
        'SELECT 1'
    )
    FROM information_schema.COLUMNS
    WHERE TABLE_SCHEMA = DATABASE()
      AND TABLE_NAME = 'sys_api_key'
      AND COLUMN_NAME = 'rate_limit_per_minute'
);
PREPARE legacy_rate_limit_column_statement FROM @legacy_rate_limit_column_sql;
EXECUTE legacy_rate_limit_column_statement;
DEALLOCATE PREPARE legacy_rate_limit_column_statement;
