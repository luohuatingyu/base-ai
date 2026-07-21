import os
from dataclasses import dataclass
from pathlib import Path

import yaml


@dataclass(frozen=True)
class Settings:
    """集中保存 Worker、LLM 与日志环境变量。"""

    backend_url: str
    internal_token: str
    instance_id: str
    ai_group_pools_file: str
    ai_group_pools: tuple[dict, ...]
    llm_timeout_seconds: float
    llm_log_content: bool
    persist_level: str


def load_settings() -> Settings:
    """从环境变量和 YAML 组池文件构建不可变配置。"""
    group_pools_file = os.getenv("AI_GROUP_POOLS_FILE", "/app/config/ai-group-pools.yml").strip()
    return Settings(
        backend_url=os.getenv("BACKEND_URL", "http://backend:8080").rstrip("/"),
        internal_token=os.getenv("PYTHON_WORKER_INTERNAL_TOKEN", "").strip(),
        instance_id=os.getenv("PYTHON_WORKER_INSTANCE_ID", "python-worker-1").strip(),
        ai_group_pools_file=group_pools_file,
        ai_group_pools=tuple(_load_group_pools(group_pools_file)),
        llm_timeout_seconds=float(os.getenv("LLM_TIMEOUT_SECONDS", "60")),
        llm_log_content=_boolean(os.getenv("LLM_LOG_CONTENT", "false")),
        persist_level=os.getenv("JOB_LOG_PERSIST_LEVEL", "INFO").upper(),
    )


def validate_settings(settings: Settings) -> None:
    """启动时校验内部令牌，模型配置在调用时给出明确错误。"""
    if len(settings.internal_token) < 24 or "replace-with" in settings.internal_token:
        raise RuntimeError("PYTHON_WORKER_INTERNAL_TOKEN 必须设置为安全随机字符串")


def _load_group_pools(path: str) -> list[dict]:
    """读取并规范化 YAML 组池，非法或缺失文件返回空列表。"""
    config_path = Path(path)
    if not config_path.is_file():
        return []
    try:
        with config_path.open("r", encoding="utf-8") as config_file:
            config = yaml.safe_load(config_file) or {}
    except (OSError, yaml.YAMLError):
        return []
    raw_pools = config.get("group_pools", {}) if isinstance(config, dict) else {}
    if not isinstance(raw_pools, dict):
        return []
    pools = []
    for pool_id, raw_pool in raw_pools.items():
        if not isinstance(raw_pool, dict):
            continue
        base_url = str(raw_pool.get("base_url", "")).strip().rstrip("/")
        api_keys = tuple(str(key).strip() for key in raw_pool.get("api_keys", ()) if str(key).strip())
        text_models = raw_pool.get("text_model", {})
        middle_model = str(text_models.get("middle", "")).strip() if isinstance(text_models, dict) else ""
        if not base_url or not api_keys or not middle_model:
            continue
        pools.append({
            "pool_id": str(pool_id).strip(),
            "base_url": base_url,
            "api_keys": api_keys,
            "model": middle_model,
            "concurrency": _positive_int(raw_pool.get("concurrency"), 4),
            "concurrency_level": "API_KEY" if raw_pool.get("concurrency_level") == "api-key" else "PROVIDER",
        })
    return pools


def _positive_int(value, default: int) -> int:
    """将配置值解析为正整数。"""
    try:
        parsed = int(value or default)
    except (TypeError, ValueError):
        return default
    return parsed if parsed > 0 else default


def _boolean(value: str) -> bool:
    """解析常见布尔文本。"""
    return value.strip().lower() in {"1", "true", "yes", "on"}
