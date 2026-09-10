-- 为数据源留存最近一次连通性检测结果，供管理页展示健康状态和轻量指标。
ALTER TABLE workflow_connection
    ADD COLUMN last_test_at DATETIME(6) NULL AFTER vector_error,
    ADD COLUMN last_test_ok BIT(1) NULL AFTER last_test_at,
    ADD COLUMN last_test_latency_ms INT NULL AFTER last_test_ok,
    ADD COLUMN last_test_info TEXT NULL AFTER last_test_latency_ms;
