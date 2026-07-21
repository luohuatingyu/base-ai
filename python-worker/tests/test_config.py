from app.config import load_settings


def test_load_settings_reads_yaml_group_pools(monkeypatch, tmp_path):
    """验证模型组池从 YAML 文件加载。"""
    monkeypatch.setenv("PYTHON_WORKER_INTERNAL_TOKEN", "a" * 32)
    config_path = tmp_path / "ai-model-pools.yml"
    config_path.write_text("group_pools:\n  qianwen:\n    base_url: https://example.com/v1\n    api_keys: [key-1, key-2]\n    text_model:\n      middle: qwen-plus\n    audio_model:\n      realtime: qwen-audio\n    concurrency: 3\n", encoding="utf-8")
    monkeypatch.setenv("AI_MODEL_POOLS_FILE", str(config_path))
    settings = load_settings()
    assert len(settings.ai_group_pools) == 1
    assert settings.ai_group_pools[0]["models"]["text_model"]["middle"] == "qwen-plus"
    assert settings.ai_group_pools[0]["models"]["audio_model"]["realtime"] == "qwen-audio"
    assert settings.ai_group_pools[0]["concurrency"] == 3


def test_load_settings_reads_yaml_features(monkeypatch, tmp_path):
    """验证业务模型配置从独立 YAML 文件加载。"""
    config_path = tmp_path / "ai-feature-routing.yml"
    config_path.write_text("features:\n  model:\n    capability_level: realtime\n    enable_thinking: true\n", encoding="utf-8")
    monkeypatch.setenv("AI_FEATURE_ROUTING_FILE", str(config_path))
    settings = load_settings()
    assert settings.ai_features["model"] == {
        "capability_level": "realtime",
        "enable_thinking": True,
    }
