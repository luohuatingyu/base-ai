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


def test_invoke_sends_mapped_thinking_value():
    """开启思考时应将模型映射后的供应商等级写入指定字段。"""
    response = httpx.Response(200, json={"choices": [{"message": {"content": "OK"}}]})
    request_holder = {}
    configured = candidate().model_copy(update={"thinkingParameter": "reasoning_effort", "thinkingValue": "xhigh"})

    async def invoke():
        def handler(request: httpx.Request) -> httpx.Response:
            """记录思考请求并返回 OpenAI 兼容响应。"""
            request_holder["request"] = request
            return response

        client = LlmClient(settings())
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        try:
            return await client._invoke(configured, "test-key", [ChatMessage(role="user", content="test")], 0, True)
        finally:
            await client.close()

    asyncio.run(invoke())

    assert json.loads(request_holder["request"].content)["reasoning_effort"] == "xhigh"


def test_invoke_forwards_multimodal_content_without_rewriting():
    """视觉请求应按 OpenAI-compatible 结构转发文本和图片片段。"""
    response = httpx.Response(200, json={"choices": [{"message": {"content": "图片中有一只猫"}}]})
    request_holder = {}
    client = LlmClient(settings())

    async def invoke():
        def handler(request: httpx.Request) -> httpx.Response:
            request_holder["request"] = request
            return response

        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        try:
            message = ChatMessage(
                role="user",
                content=[
                    {"type": "text", "text": "请描述图片"},
                    {"type": "image_url", "image_url": {"url": "data:image/png;base64,AAAA"}},
                ],
            )
            return await client._invoke(candidate(), "test-key", [message], 0, False)
        finally:
            await client.close()

    asyncio.run(invoke())
    payload = json.loads(request_holder["request"].content)
    assert payload["messages"][0]["content"][1] == {
        "type": "image_url",
        "image_url": {"url": "data:image/png;base64,AAAA"},
    }


@pytest.mark.parametrize(
    "content",
    [
        [{"type": "image_url", "image_url": {"url": "data:image/gif;base64,AAAA"}}],
        [{"type": "image_url", "image_url": {"url": "not-a-url"}}],
    ],
)
def test_chat_message_rejects_unsupported_image_urls(content):
    """Worker 应拒绝不支持格式或不安全的图片地址。"""
    with pytest.raises(ValueError):
        ChatMessage(role="user", content=content)


def test_configured_route_with_no_candidates_does_not_use_yaml_fallback():
    """路由已配置但无匹配模型时，不能退回 YAML 默认模型池。"""
    client = LlmClient(settings())
    try:
        with pytest.raises(RuntimeError, match="未配置可用的模型能力路由"):
            asyncio.run(client.chat([ChatMessage(role="user", content="test")], 0, [], True, route_configured=True))
    finally:
        asyncio.run(client.close())


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
