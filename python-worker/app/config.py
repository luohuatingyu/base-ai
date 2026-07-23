"""Worker 配置加载模块，集中读取环境变量并提供类型化配置对象。"""

import os
from dataclasses import dataclass
from pathlib import Path

import yaml

POOL_RESERVED_FIELDS = {
    "base_url", "api_key", "api_keys", "concurrency", "concurrency_level", "concurrency_mode", "timeout_seconds", "thinking_parameter",
}


@dataclass(frozen=True)
class Settings:
    """集中保存 Worker、LLM 与日志环境变量。"""

    backend_url: str
    internal_token: str
    instance_id: str
    ai_model_pools_file: str
    ai_feature_routing_file: str
    ai_group_pools: tuple[dict, ...]
    ai_features: dict[str, dict]
    llm_timeout_seconds: float
    llm_log_content: bool
    persist_level: str
    log_level: str = "INFO"


def load_settings() -> Settings:
    """从环境变量、模型组池和业务模型 YAML 构建不可变配置。"""
    model_pools_file = os.getenv("AI_MODEL_POOLS_FILE", "/app/config/ai-model-pools.yml").strip()
    feature_routing_file = os.getenv("AI_FEATURE_ROUTING_FILE", "/app/config/ai-feature-routing.yml").strip()
    return Settings(
        backend_url=os.getenv("BACKEND_URL", "http://backend:8080").rstrip("/"),
        internal_token=os.getenv("PYTHON_WORKER_INTERNAL_TOKEN", "").strip(),
        instance_id=os.getenv("PYTHON_WORKER_INSTANCE_ID", "python-worker-1").strip(),
        ai_model_pools_file=model_pools_file,
        ai_feature_routing_file=feature_routing_file,
        ai_group_pools=tuple(_load_group_pools(model_pools_file)),
        ai_features=_load_features(feature_routing_file),
        llm_timeout_seconds=float(os.getenv("LLM_TIMEOUT_SECONDS", "60")),
        llm_log_content=_boolean(os.getenv("LLM_LOG_CONTENT", "true")),
        persist_level=os.getenv("TRACE_LOG_PERSIST_LEVEL", "INFO").upper(),
        log_level=os.getenv("LOG_LEVEL", "INFO").upper(),
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
        models = {}
        for model_type, raw_models in raw_pool.items():
            normalized_type = str(model_type).strip()
            if model_type in POOL_RESERVED_FIELDS or not normalized_type or not isinstance(raw_models, dict):
                continue
            normalized_models = {}
            for level, model_config in raw_models.items():
                normalized_level = str(level).strip()
                if not normalized_level:
                    continue
                if isinstance(model_config, dict):
                    model_name = str(model_config.get("model", "")).strip()
                    thinking_levels = model_config.get("thinking_levels", {})
                    if model_name:
                        normalized_models[normalized_level] = {
                            "model": model_name,
                            "thinking_levels": {
                                str(key).strip(): str(value).strip()
                                for key, value in thinking_levels.items()
                                if str(key).strip() and str(value).strip()
                            } if isinstance(thinking_levels, dict) else {},
                        }
                elif str(model_config).strip():
                    normalized_models[normalized_level] = str(model_config).strip()
            if normalized_models:
                models[normalized_type] = normalized_models
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
        enable_thinking = _boolean(str(raw_feature.get("enable_thinking", "false")))
        features[str(feature_code).strip()] = {
            "capability_level": _non_empty(raw_feature.get("capability_level"), "middle"),
            "enable_thinking": enable_thinking,
            "thinking_level": _non_empty(raw_feature.get("thinking_level"), "medium") if enable_thinking else None,
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


def _non_empty(value, default: str) -> str:
    """读取可扩展字符串配置，空值回退默认值。"""
    normalized = str(value or "").strip()
    return normalized or default


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
