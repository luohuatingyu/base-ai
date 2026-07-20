from app.config import load_settings


def test_load_settings_reads_unified_environment(monkeypatch):
    """验证统一环境变量可生成模型配置。"""
    monkeypatch.setenv("PYTHON_WORKER_INTERNAL_TOKEN", "a" * 32)
    monkeypatch.setenv("LLM_API_KEYS", "key-1,key-2")
    monkeypatch.setenv("LLM_CONCURRENCY", "3")
    settings = load_settings()
    assert settings.llm_api_keys == ("key-1", "key-2")
    assert settings.llm_concurrency == 3
