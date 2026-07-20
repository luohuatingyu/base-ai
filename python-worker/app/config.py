import os
from dataclasses import dataclass


@dataclass(frozen=True)
class Settings:
    """集中保存 Worker、LLM 与日志环境变量。"""

    backend_url: str
    internal_token: str
    instance_id: str
    llm_base_url: str
    llm_api_keys: tuple[str, ...]
    llm_model: str
    llm_concurrency: int
    llm_timeout_seconds: float
    llm_log_content: bool
    persist_level: str


def load_settings() -> Settings:
    """从统一环境变量构建不可变配置。"""
    return Settings(
        backend_url=os.getenv("BACKEND_URL", "http://backend:8080").rstrip("/"),
        internal_token=os.getenv("PYTHON_WORKER_INTERNAL_TOKEN", "").strip(),
        instance_id=os.getenv("PYTHON_WORKER_INSTANCE_ID", "python-worker-1").strip(),
        llm_base_url=os.getenv("LLM_BASE_URL", "").strip().rstrip("/"),
        llm_api_keys=tuple(
            key for key in (item.strip() for item in os.getenv("LLM_API_KEYS", "").split(",")) if key
        ),
        llm_model=os.getenv("LLM_MODEL", "").strip(),
        llm_concurrency=_positive_int(os.getenv("LLM_CONCURRENCY"), 4),
        llm_timeout_seconds=float(os.getenv("LLM_TIMEOUT_SECONDS", "60")),
        llm_log_content=_boolean(os.getenv("LLM_LOG_CONTENT", "false")),
        persist_level=os.getenv("JOB_LOG_PERSIST_LEVEL", "INFO").upper(),
    )


def validate_settings(settings: Settings) -> None:
    """启动时校验内部令牌，模型配置在调用时给出明确错误。"""
    if len(settings.internal_token) < 24 or "replace-with" in settings.internal_token:
        raise RuntimeError("PYTHON_WORKER_INTERNAL_TOKEN 必须设置为安全随机字符串")


def _positive_int(value: str | None, default: int) -> int:
    """将环境变量解析为正整数。"""
    try:
        parsed = int(value or default)
    except ValueError:
        return default
    return parsed if parsed > 0 else default


def _boolean(value: str) -> bool:
    """解析常见布尔文本。"""
    return value.strip().lower() in {"1", "true", "yes", "on"}
