-- 请求快照曾可能包含历史敏感配置。保留操作元数据，永久清空全部历史请求内容。
UPDATE sys_operation_log
SET request_data = NULL
WHERE request_data IS NOT NULL;
