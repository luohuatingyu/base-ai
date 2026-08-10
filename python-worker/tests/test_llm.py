"""LLM 供应商响应解析测试。"""

import asyncio
import json

import httpx
import pytest

from app.config import Settings
from app.llm import LlmClient
from app.models import ChatMessage, EmbeddingRequest, EmbeddingResponse, LlmCandidate


def settings() -> Settings:
    """构造不依赖外部服务的 Worker 配置。"""
    return Settings(
        backend_url="http://backend:8080",
        internal_token="x" * 24,
        instance_id="worker-test",
        llm_timeout_seconds=10,
        llm_log_content=False,
        persist_level="INFO",
        llm_response_max_bytes=1024,
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


def test_embedding_uses_openai_compatible_endpoint_and_restores_input_order():
    """向量模型必须调用 embeddings 端点，并根据 index 恢复与输入一致的顺序。"""
    request_holder = {}

    async def invoke():
        def handler(request: httpx.Request) -> httpx.Response:
            """返回刻意乱序的向量，验证客户端恢复输入顺序。"""
            request_holder["request"] = request
            return httpx.Response(200, json={"data": [
                {"index": 1, "embedding": [0.0, 1.0]},
                {"index": 0, "embedding": [1.0, 0.0]},
            ]})

        client = LlmClient(settings())
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        try:
            return await client.embed(["55英寸", "一级能效"], [candidate()])
        finally:
            await client.close()

    result = asyncio.run(invoke())

    assert request_holder["request"].url.path == "/embeddings"
    assert json.loads(request_holder["request"].content) == {
        "model": "test-model",
        "input": ["55英寸", "一级能效"],
    }
    assert result.embeddings == [[1.0, 0.0], [0.0, 1.0]]


def test_embedding_falls_back_to_next_candidate():
    """首个向量候选失败时应继续尝试后续候选。"""
    attempted_hosts = []
    first = candidate().model_copy(update={"providerCode": "first", "baseUrl": "https://first.example"})
    second = candidate().model_copy(update={"providerCode": "second", "baseUrl": "https://second.example"})

    async def invoke():
        def handler(request: httpx.Request) -> httpx.Response:
            """记录候选顺序，并仅允许第二个供应商成功。"""
            attempted_hosts.append(request.url.host)
            if request.url.host == "first.example":
                return httpx.Response(503, json={"error": "unavailable"})
            return httpx.Response(200, json={"data": [{"index": 0, "embedding": [1.0, 0.0]}]})

        client = LlmClient(settings())
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        try:
            return await client.embed(["参数"], [first, second])
        finally:
            await client.close()

    result = asyncio.run(invoke())

    assert attempted_hosts == ["first.example", "second.example"]
    assert result.model == "test-model"


def test_embedding_connection_test_returns_vector_dimension():
    """向量模型连接测试应走 embeddings 请求并返回输出维度。"""

    async def invoke():
        client = LlmClient(settings())

        async def embed(values, candidates):
            """隔离供应商调用，仅验证连接测试分支。"""
            assert values == ["embedding health check"]
            assert candidates == [candidate()]
            return EmbeddingResponse(embeddings=[[0.1, 0.2, 0.3]], model="test-model")

        client.embed = embed
        try:
            return await client.test(candidate(), embedding=True)
        finally:
            await client.close()

    result = asyncio.run(invoke())

    assert result["success"] is True
    assert result["dimension"] == 3
    assert result["model"] == "test-model"


@pytest.mark.parametrize(
    ("values", "candidates"),
    [
        ([], [candidate()]),
        (["   "], [candidate()]),
        (["x" * 501], [candidate()]),
        (["value"] * 257, [candidate()]),
        (["value"], []),
        (["value"], [candidate()] * 21),
    ],
)
def test_embedding_request_rejects_invalid_input(values, candidates):
    """向量请求应拒绝非法输入文本或候选数量。"""
    with pytest.raises(ValueError):
        EmbeddingRequest(input=values, candidates=candidates)


@pytest.mark.parametrize(
    "embeddings",
    [[], [[]], [[1.0], [1.0, 2.0]], [[float("nan")]]],
)
def test_embedding_response_rejects_invalid_vectors(embeddings):
    """向量响应应拒绝空结果、空向量、维度不一致和非有限数。"""
    with pytest.raises(ValueError):
        EmbeddingResponse(embeddings=embeddings, model="test-model")


@pytest.mark.parametrize(
    ("values", "body", "exception", "message"),
    [
        (["a"], {"data": []}, RuntimeError, "向量数量不匹配"),
        (["a", "b"], {"data": [
            {"index": 0, "embedding": [1.0]},
            {"index": 0, "embedding": [2.0]},
        ]}, RuntimeError, "向量索引或值无效"),
        (["a"], {"data": [{"index": 0, "embedding": ["bad"]}]}, RuntimeError, "向量索引或值无效"),
        (["a"], {"data": [{"index": False, "embedding": [1.0]}]}, RuntimeError, "向量格式无效"),
        (["a"], {"data": [{"index": 0, "embedding": [True]}]}, RuntimeError, "向量索引或值无效"),
        (["a"], {"data": [{"index": 0, "embedding": []}]}, ValueError, "向量结果为空"),
    ],
)
def test_embedding_rejects_invalid_provider_payload(values, body, exception, message):
    """供应商返回缺项、重复索引、非数值或空向量时必须受控失败。"""

    async def invoke():
        client = LlmClient(settings())
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(200, json=body)
        ))
        try:
            return await client._invoke_embedding(candidate(), "test-key", values)
        finally:
            await client.close()

    with pytest.raises(exception, match=message):
        asyncio.run(invoke())


def test_embedding_rejects_oversized_provider_response():
    """向量供应商响应超过统一上限时不得继续缓冲或解析。"""

    async def invoke():
        client = LlmClient(settings())
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda _: httpx.Response(200, content=b"x" * 1025, headers={"content-type": "application/json"})
        ))
        try:
            return await client._invoke_embedding(candidate(), "test-key", ["参数"])
        finally:
            await client.close()

    with pytest.raises(RuntimeError, match="响应超过大小限制"):
        asyncio.run(invoke())


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


def test_invoke_rejects_oversized_provider_response():
    """供应商响应超过配置上限时不得继续缓冲或解析。"""
    response = httpx.Response(200, content=b"x" * 1025, headers={"content-type": "application/json"})

    with pytest.raises(RuntimeError, match="响应超过大小限制"):
        asyncio.run(invoke_with(response))


@pytest.mark.parametrize("base_url", ["file:///etc/passwd", "https://user:secret@example.com", "https://example.com/path#part"])
def test_candidate_rejects_unsafe_provider_url(base_url):
    """模型供应商地址不得使用非 HTTP 协议、内嵌凭证或 URL 片段。"""
    payload = candidate().model_dump()
    payload["baseUrl"] = base_url
    with pytest.raises(ValueError):
        LlmCandidate(**payload)
