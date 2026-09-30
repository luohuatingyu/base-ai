-- 供应商显式绑定媒体协议策略；NULL 保留历史配置的兼容推断行为。
ALTER TABLE sys_llm_provider
    ADD COLUMN strategy_code VARCHAR(32) NULL AFTER code;
