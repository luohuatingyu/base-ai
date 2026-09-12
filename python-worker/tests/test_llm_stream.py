"""真实流式协议、故障切换和资源回收测试。"""

import asyncio
import json
import httpx
import pytest
from app.llm import LlmClient
from app.llm_stream import stream_chat, stream_events
from app.models import ChatRequest, ChatMessage
from app.middleware import RequestSizeLimitMiddleware
from test_llm import settings, candidate


class Chunks(httpx.AsyncByteStream):
    """逐字节输出 UTF-8，验证网络分片不会破坏中文。"""

    def __init__(self, content):
        """记录响应及关闭状态。"""
        self.content = content.encode()
        self.closed = False

    async def __aiter__(self):
        """逐字节模拟上游网络。"""
        for value in self.content:
            yield bytes([value])

    async def aclose(self):
        """记录连接已回收。"""
        self.closed = True


def frame(data):
    """编码供应商 SSE 数据帧。"""
    return "data: " + json.dumps(data, ensure_ascii=False) + "\r\n\r\n"


def success():
    """生成含统计和结束标记的响应。"""
    return (frame({"choices": [{"delta": {"content": "你好"}}]}) +
            frame({"choices": [{"delta": {}, "finish_reason": "stop"}]}) +
            frame({"choices": [], "usage": {"prompt_tokens": 2, "completion_tokens": 1, "total_tokens": 3}}) +
            "data: [DONE]\r\n\r\n")


async def collect(body, fail_first=False):
    """仅隔离上游 HTTP，运行真实策略、并发控制和解析器。"""
    client = LlmClient(settings())
    await client.client.aclose()
    calls = []
    chunks = Chunks(body)

    async def handler(request):
        """校验实际发出的流式请求参数。"""
        calls.append(json.loads(request.content))
        if fail_first and len(calls) == 1:
            return httpx.Response(503)
        return httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=chunks)

    client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
    request = ChatRequest(messages=[ChatMessage(role="user", content="hello")],
                          candidates=[candidate(), candidate()])
    events = []
    try:
        async for event in stream_chat(client, request):
            events.append(event)
        return events, calls, chunks
    finally:
        await client.close()


@pytest.mark.parametrize("fail_first", [False, True])
def test_streams_utf8_and_usage_and_fails_over_before_output(fail_first):
    """首段之前切换候选，完整响应逐段交付并保留统计。"""
    events, calls, chunks = asyncio.run(collect(success(), fail_first))
    assert events == [{"type": "delta", "content": "你好"},
                      {"type": "done", "model": "test-model", "inputTokens": 2, "outputTokens": 1, "totalTokens": 3}]
    assert len(calls) == (2 if fail_first else 1)
    assert all(call["stream"] is True for call in calls)
    assert chunks.closed


@pytest.mark.parametrize("body", ["data: nope\n\n", "data: [DONE]\n\n", "data: " + "x" * 2000 + "\n\n"])
def test_rejects_invalid_missing_finish_and_oversize(body):
    """畸形响应、缺少结束原因及超限不能返回成功。"""
    with pytest.raises(RuntimeError):
        asyncio.run(collect(body))


def test_does_not_fail_over_after_first_delta():
    """已输出一段后中断应失败，不能拼接下一候选的回答。"""
    async def run():
        client = LlmClient(settings())
        await client.client.aclose()
        calls = []
        async def handler(request):
            """上游输出文本后直接断开。"""
            calls.append(request)
            return httpx.Response(200, headers={"content-type": "text/event-stream"},
                                  stream=Chunks(frame({"choices": [{"delta": {"content": "partial"}}]})))
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        request = ChatRequest(messages=[ChatMessage(role="user", content="hello")], candidates=[candidate(), candidate()])
        generator = stream_chat(client, request)
        try:
            assert (await anext(generator))["content"] == "partial"
            with pytest.raises(RuntimeError):
                await anext(generator)
            assert len(calls) == 1
        finally:
            await generator.aclose()
            await client.close()
    asyncio.run(run())


def test_size_middleware_waits_for_real_disconnect():
    """正文重放后继续等待真实断连，不伪造断连取消流式响应。"""
    async def run():
        count = 0
        async def receive():
            """分别返回请求正文和后续真实传输消息。"""
            nonlocal count
            count += 1
            return {"type": "http.request", "body": b"{}", "more_body": False} if count == 1 else {"type": "real.disconnect"}
        async def downstream(scope, replay, send):
            """第二次读取必须透传原始 receive。"""
            assert (await replay())["type"] == "http.request"
            assert (await replay())["type"] == "real.disconnect"
        await RequestSizeLimitMiddleware(downstream, 100)({"type": "http", "headers": []}, receive, None)
        assert count == 2
    asyncio.run(run())


def test_generator_tracks_until_done_and_closes():
    """成功状态只在完整响应结束时上报，注册表最终清理。"""
    class Registry:
        """内存模拟外部任务登记。"""
        def __init__(self):
            """初始化活动任务集合。"""
            self.active = {}

        async def register(self, key, task):
            """登记当前活动任务。"""
            self.active[key] = task

        async def remove(self, key):
            """移除完成的任务。"""
            self.active.pop(key)
    class Reporter:
        """记录外部追踪请求。"""
        def __init__(self):
            """初始化上报状态集合。"""
            self.states = []

        async def report(self, key, state):
            """记录任务状态上报。"""
            self.states.append(state)
    async def run():
        client = LlmClient(settings())
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=Chunks(success()))))
        registry, reporter = Registry(), Reporter()
        request = ChatRequest(messages=[ChatMessage(role="user", content="hello")], candidates=[candidate()])
        generator = stream_events(client, request, "trace", registry, reporter)
        try:
            assert "delta" in await anext(generator)
            assert reporter.states == ["RUNNING"]
            assert registry.active
            assert "done" in await anext(generator)
            with pytest.raises(StopAsyncIteration): await anext(generator)
            assert reporter.states == ["RUNNING", "SUCCESS"]
            assert not registry.active
        finally:
            await generator.aclose()
            await client.close()
    asyncio.run(run())


def test_signed_asgi_stream_survives_middleware_and_rejects_unsigned():
    """真实 FastAPI/ASGI 中间件链保留流式正文并验证内部签名。"""
    from fastapi import FastAPI
    from fastapi.responses import StreamingResponse
    from app.context import current_context
    from app.internal_auth import signed_headers
    from app.middleware import InternalAuthMiddleware
    from app.trace_runtime import TraceRuntimeRegistry

    class Reporter:
        """隔离外部追踪 HTTP 并保留状态顺序。"""
        def __init__(self):
            """初始化追踪事件集合。"""
            self.states = []

        async def report(self, key, state):
            """记录外部上报副作用。"""
            self.states.append(state)

    async def run():
        """通过 ASGI HTTP 客户端验证完整正文和鉴权。"""
        client = LlmClient(settings())
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(
            lambda request: httpx.Response(200, headers={"content-type": "text/event-stream"}, stream=Chunks(success()))))
        registry, reporter = TraceRuntimeRegistry(), Reporter()
        app = FastAPI()
        app.add_middleware(InternalAuthMiddleware, settings=settings(), registry=registry, reporter=reporter)
        app.add_middleware(RequestSizeLimitMiddleware, max_bytes=10000)

        @app.post("/llm/chat/stream")
        async def endpoint(request: ChatRequest):
            """使用生产流式生成器和请求追踪上下文。"""
            return StreamingResponse(stream_events(client, request, current_context().python_trace_id, registry, reporter),
                                     media_type="text/event-stream")

        request = ChatRequest(messages=[ChatMessage(role="user", content="hello")], candidates=[candidate()])
        body = request.model_dump_json().encode()
        headers = signed_headers(settings().internal_token, "POST", "/llm/chat/stream", body)
        headers["Content-Type"] = "application/json"
        try:
            async with httpx.AsyncClient(transport=httpx.ASGITransport(app), base_url="http://localhost") as transport:
                rejected = await transport.post("/llm/chat/stream", content=body)
                assert rejected.status_code == 401
                accepted = await transport.post("/llm/chat/stream", content=body, headers=headers)
                assert accepted.status_code == 200
                assert '"content": "你好"' in accepted.text
                assert '"type": "done"' in accepted.text
                assert reporter.states == ["RUNNING", "SUCCESS"]
                assert not registry._tasks
        finally:
            await client.close()
    asyncio.run(run())
