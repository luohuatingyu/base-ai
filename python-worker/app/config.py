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
    ai_features_file: str
    ai_group_pools: tuple[dict, ...]
    ai_features: dict[str, dict]
    llm_timeout_seconds: float
    llm_log_content: bool
    persist_level: str


def load_settings() -> Settings:
    """从环境变量、模型组池和业务模型 YAML 构建不可变配置。"""
    group_pools_file = os.getenv("AI_GROUP_POOLS_FILE", "/app/config/ai-group-pools.yml").strip()
    features_file = os.getenv("AI_FEATURES_FILE", "/app/config/ai-features.yml").strip()
    return Settings(
        backend_url=os.getenv("BACKEND_URL", "http://backend:8080").rstrip("/"),
        internal_token=os.getenv("PYTHON_WORKER_INTERNAL_TOKEN", "").strip(),
        instance_id=os.getenv("PYTHON_WORKER_INSTANCE_ID", "python-worker-1").strip(),
        ai_group_pools_file=group_pools_file,
        ai_features_file=features_file,
        ai_group_pools=tuple(_load_group_pools(group_pools_file)),
        ai_features=_load_features(features_file),
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
    config = _load_yaml(path)
    raw_pools = config.get("group_pools", {}) if isinstance(config, dict) else {}
    if not isinstance(raw_pools, dict):
        return []
    pools = []
    for pool_id, raw_pool in raw_pools.items():
        if not isinstance(raw_pool, dict):
            continue
        base_url = str(raw_pool.get("base_url", "")).strip().rstrip("/")
        api_keys = tuple(str(key).strip() for key in raw_pool.get("api_keys", ()) if str(key).strip())
        models = {
            model_type: {
                level: str(model_name).strip()
                for level, model_name in raw_pool.get(model_type, {}).items()
                if str(model_name).strip()
            }
            for model_type in ("text_model", "vision_model")
            if isinstance(raw_pool.get(model_type), dict)
        }
        if not base_url or not api_keys or not models:
            continue
        pools.append({
            "pool_id": str(pool_id).strip(),
            "base_url": base_url,
            "api_keys": api_keys,
            "models": models,
            "concurrency": _positive_int(raw_pool.get("concurrency"), 4),
            "concurrency_level": "API_KEY" if raw_pool.get("concurrency_level") == "api-key" else "PROVIDER",
        })
    return pools


def _load_features(path: str) -> dict[str, dict]:
    """读取业务功能模型类型、能力等级和思考模式配置。"""
    config = _load_yaml(path)
    raw_features = config.get("features", {})
    if not isinstance(raw_features, dict):
        return {}
    features = {}
    for feature_code, raw_feature in raw_features.items():
        if not isinstance(raw_feature, dict):
            continue
        features[str(feature_code).strip()] = {
            "model_type": _choice(raw_feature.get("model_type"), {"text_model", "vision_model"}, "text_model"),
            "capability_level": _choice(raw_feature.get("capability_level"), {"low", "middle", "high"}, "middle"),
            "enable_thinking": _boolean(str(raw_feature.get("enable_thinking", "false"))),
        }
    return features


def _load_yaml(path: str) -> dict:
    """安全读取 YAML 字典，文件缺失或格式错误时返回空字典。"""
    config_path = Path(path)
    if not config_path.is_file():
        return {}
    try:
        with config_path.open("r", encoding="utf-8") as config_file:
            config = yaml.safe_load(config_file) or {}
    except (OSError, yaml.YAMLError):
        return {}
    return config if isinstance(config, dict) else {}


def _choice(value, allowed: set[str], default: str) -> str:
    """校验枚举配置，非法值回退默认值。"""
    normalized = str(value or "").strip()
    return normalized if normalized in allowed else default


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
