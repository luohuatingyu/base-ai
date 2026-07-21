import asyncio
import json

import httpx

from app.config import Settings
from app.llm import LlmClient
from app.models import ChatMessage, LlmCandidate


def test_llm_client_fails_over_to_next_candidate():
    """验证首个候选失败后自动切换到下一模型。"""
    async def scenario():
        settings = Settings("http://backend", "x" * 32, "worker", "", "", (), {}, 10, False, "INFO")
        client = LlmClient(settings)

        async def handler(request: httpx.Request):
            if "first.example" in str(request.url):
                return httpx.Response(500, json={"error": "failed"})
            return httpx.Response(200, content=json.dumps({
                "choices": [{"message": {"content": "ok"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }).encode())

        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        candidates = [
            LlmCandidate(providerCode="first", baseUrl="https://first.example/v1", apiKeys=["key-1"], model="m1"),
            LlmCandidate(providerCode="second", baseUrl="https://second.example/v1", apiKeys=["key-2"], model="m2"),
        ]
        result = await client.chat([ChatMessage(role="user", content="hello")], 0, candidates)
        assert result.model == "m2"
        assert result.content == "ok"
        await client.close()

    asyncio.run(scenario())


def test_llm_client_builds_candidate_from_yaml_pool():
    """验证 Worker 仅使用 YAML 组池生成默认文本模型候选。"""
    settings = Settings("http://backend", "x" * 32, "worker", "/config/ai-group-pools.yml", "", ({
        "pool_id": "qianwen",
        "base_url": "https://example.com/v1",
        "api_keys": ("key-1", "key-2"),
        "models": {"text_model": {"middle": "qwen-plus"}},
        "concurrency": 3,
        "concurrency_level": "API_KEY",
    },), {}, 10, False, "INFO")
    candidate = LlmClient(settings)._fallback_candidates()[0]
    assert candidate.providerCode == "qianwen"
    assert candidate.model == "qwen-plus"
    assert candidate.concurrencyLevel == "API_KEY"


def test_llm_client_selects_model_by_business_feature():
    """验证业务功能配置决定模型等级。"""
    settings = Settings("http://backend", "x" * 32, "worker", "", "", ({
        "pool_id": "qianwen",
        "base_url": "https://example.com/v1",
        "api_keys": ("key-1",),
        "models": {"reasoning_model": {"premium": "qwen-max"}},
        "concurrency": 2,
        "concurrency_level": "PROVIDER",
    },), {"quote_text": {
        "model_type": "reasoning_model",
        "capability_level": "premium",
        "enable_thinking": True,
    }}, 10, False, "INFO")
    client = LlmClient(settings)
    feature = client._feature_config("quote_text")
    candidate = client._fallback_candidates(feature)[0]
    assert candidate.model == "qwen-max"
    assert feature["enable_thinking"] is True
    asyncio.run(client.close())
