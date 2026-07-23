"""LLM 供应商响应解析测试。"""

import asyncio
import json

import httpx
import pytest

from app.config import Settings
from app.llm import LlmClient
from app.models import ChatMessage, LlmCandidate


def settings() -> Settings:
    """构造不依赖外部服务的 Worker 配置。"""
    return Settings(
        backend_url="http://backend:8080",
        internal_token="x" * 24,
        instance_id="worker-test",
        ai_model_pools_file="/missing/pools.yml",
        ai_feature_routing_file="/missing/routes.yml",
        ai_group_pools=(),
        ai_features={},
        llm_timeout_seconds=10,
        llm_log_content=False,
        persist_level="INFO",
    )


def candidate() -> LlmCandidate:
    """创建用于请求模拟的最小模型候选配置。"""
    return LlmCandidate(
        providerCode="test-provider",
        baseUrl="https://provider.example",
        apiKeys=["test-key"],
        model="test-model",
    )


async def invoke_with(response: httpx.Response, request_holder: dict | None = None):
    """通过 MockTransport 调用单次模型请求。"""
    client = LlmClient(settings())
    await client.client.aclose()

    def handler(request: httpx.Request) -> httpx.Response:
        """保存请求体，以便断言 OpenAI 兼容参数。"""
        if request_holder is not None:
            request_holder["request"] = request
        return response

    client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    try:
        return await client._invoke(candidate(), "test-key", [ChatMessage(role="user", content="test")], 0, False)
    finally:
        await client.close()


def test_invoke_returns_openai_compatible_json_response():
    """OpenAI 兼容 JSON 响应应成功解析并返回模型内容。"""
    response = httpx.Response(
        200,
        json={"choices": [{"message": {"content": "OK"}}], "usage": {"prompt_tokens": 1, "completion_tokens": 2, "total_tokens": 3}},
    )

    request_holder = {}
    result = asyncio.run(invoke_with(response, request_holder))

    assert result.content == "OK"
    assert result.inputTokens == 1
    assert result.outputTokens == 2
    assert result.totalTokens == 3
    assert json.loads(request_holder["request"].content)["stream"] is False


@pytest.mark.parametrize(
    ("body", "content_type"),
    [(b"", "application/json"), (b"<html>gateway</html>", "text/html")],
)
def test_invoke_reports_empty_or_non_json_provider_response(body, content_type):
    """空响应或非 JSON 响应应给出供应商兼容性错误而非 JSONDecodeError。"""
    response = httpx.Response(200, content=body, headers={"content-type": content_type})

    with pytest.raises(RuntimeError, match="返回非 JSON 或空响应"):
        asyncio.run(invoke_with(response))


def test_invoke_reports_missing_openai_choice_content():
    """缺少 OpenAI choices 内容时应返回可诊断的格式错误。"""
    response = httpx.Response(200, json={"choices": []})

    with pytest.raises(RuntimeError, match=r"缺少 choices\[0\]\.message\.content"):
        asyncio.run(invoke_with(response))
