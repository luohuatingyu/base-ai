import asyncio
import json
import logging
import queue
import sys

import pytest

from fastapi import Request
from starlette.responses import Response

from app.config import Settings, load_settings
from app.context import RequestContext, reset_context, set_context
from app.internal_auth import signed_headers
from app.logging_config import ContextFilter, JavaLogShipHandler, JsonLogFormatter, sanitize_log_text
from app.middleware import InternalAuthMiddleware, RequestSizeLimitMiddleware


class InspectableTraceHandler(JavaLogShipHandler):
    """提供不启动后台线程的日志负载检查器。"""

    def __init__(self) -> None:
        """初始化测试所需的最小有界队列。"""
        logging.Handler.__init__(self)
        self.items = queue.Queue(maxsize=1)
        self._dropped_count = 0

    def close(self) -> None:
        """仅释放 Logging Handler 注册，不执行后台线程清理。"""
        logging.Handler.close(self)


def settings() -> Settings:
    """构造不访问外部服务的 Worker 测试配置。"""
    return Settings(
        backend_url="http://backend:8080",
        internal_token="x" * 24,
        instance_id="worker-test",
        llm_timeout_seconds=10,
        llm_log_content=False,
        persist_level="INFO",
        allowed_hosts=("worker", "python-worker", "localhost", "127.0.0.1"),
    )


def test_middleware_propagates_request_and_trace_headers():
    """健康请求应分别传播 Request ID 和 Python Trace ID。"""
    middleware = InternalAuthMiddleware(object(), settings(), registry=object(), reporter=object())
    request = Request({
        "type": "http",
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/health",
        "raw_path": b"/health",
        "query_string": b"",
        "headers": [
            (b"x-request-id", b"request-1"),
            (b"x-python-trace-id", b"python-trace-1"),
        ],
        "client": ("127.0.0.1", 1234),
        "server": ("worker", 8000),
    })

    async def call_next(_: Request) -> Response:
        """返回最小健康响应供中间件补充响应头。"""
        return Response(status_code=200)

    response = asyncio.run(middleware.dispatch(request, call_next))

    assert response.headers["X-Request-Id"] == "request-1"
    assert response.headers["X-Python-Trace-Id"] == "python-trace-1"


def test_malformed_host_cannot_bypass_internal_authentication():
    """恶意 Host 即使把 request.url.path 污染成健康路径也不得执行真实业务端点。"""
    middleware = InternalAuthMiddleware(object(), settings(), registry=object(), reporter=object())
    request = Request({
        "type": "http",
        "http_version": "1.1",
        "method": "POST",
        "scheme": "http",
        "path": "/email/send",
        "raw_path": b"/email/send",
        "query_string": b"",
        "headers": [(b"host", b"example.com/health?ignored=")],
        "client": ("127.0.0.1", 1234),
        "server": ("worker", 8000),
    })
    called = False

    async def call_next(_: Request) -> Response:
        """记录业务端点是否被错误执行。"""
        nonlocal called
        called = True
        return Response(status_code=200)

    response = asyncio.run(middleware.dispatch(request, call_next))

    assert response.status_code == 400
    assert called is False


def test_similar_health_path_still_requires_internal_token():
    """只有精确健康路径可以免认证，相似路径不得扩大公开范围。"""
    middleware = InternalAuthMiddleware(object(), settings(), registry=object(), reporter=object())
    request = Request({
        "type": "http",
        "http_version": "1.1",
        "method": "GET",
        "scheme": "http",
        "path": "/health/ready",
        "raw_path": b"/health/ready",
        "query_string": b"",
        "headers": [(b"host", b"python-worker:8000")],
        "client": ("127.0.0.1", 1234),
        "server": ("worker", 8000),
    })

    response = asyncio.run(middleware.dispatch(request, lambda _: Response(status_code=200)))

    assert response.status_code == 401


def signed_request(path: str, body: bytes, headers: dict[str, str]) -> Request:
    """创建携带一次性 ASGI 正文和内部签名的请求。"""
    messages = [{"type": "http.request", "body": body, "more_body": False}]

    async def receive():
        """只返回一次完整正文。"""
        return messages.pop(0) if messages else {"type": "http.disconnect"}

    raw_headers = [(b"host", b"python-worker:8000")]
    raw_headers.extend((name.lower().encode("ascii"), value.encode("ascii")) for name, value in headers.items())
    return Request({
        "type": "http", "http_version": "1.1", "method": "POST", "scheme": "http",
        "path": path, "raw_path": path.encode("ascii"), "query_string": b"",
        "headers": raw_headers, "client": ("127.0.0.1", 1234), "server": ("python-worker", 8000),
    }, receive)


def test_internal_signature_binds_body_and_rejects_replay():
    """有效正文只执行一次，相同签名重放或篡改正文均返回 401。"""
    middleware = InternalAuthMiddleware(object(), settings(), registry=object(), reporter=object())
    body = b'{"value":1}'
    headers = signed_headers(settings().internal_token, "POST", "/email/send", body)
    called = 0

    async def call_next(_: Request) -> Response:
        """记录真正进入业务端点的次数。"""
        nonlocal called
        called += 1
        return Response(status_code=200)

    accepted = asyncio.run(middleware.dispatch(signed_request("/email/send", body, headers), call_next))
    replay = asyncio.run(middleware.dispatch(signed_request("/email/send", body, headers), call_next))
    tampered_headers = signed_headers(settings().internal_token, "POST", "/email/send", body,
                                      nonce="abcdef0123456789abcdef0123456789")
    tampered = asyncio.run(middleware.dispatch(
        signed_request("/email/send", b'{"value":2}', tampered_headers), call_next))

    assert accepted.status_code == 200
    assert replay.status_code == 401
    assert tampered.status_code == 401
    assert called == 1


def test_request_size_middleware_rejects_chunked_body_over_limit():
    """即使没有 Content-Length，累计正文超限也必须在进入应用前返回 413。"""
    called = False
    sent = []
    messages = [
        {"type": "http.request", "body": b"1234", "more_body": True},
        {"type": "http.request", "body": b"5", "more_body": False},
    ]

    async def downstream(scope, receive, send):
        nonlocal called
        called = True

    async def receive():
        return messages.pop(0)

    async def send(message):
        sent.append(message)

    scope = {"type": "http", "method": "POST", "path": "/llm/chat", "headers": []}
    middleware = RequestSizeLimitMiddleware(downstream, max_bytes=4)
    asyncio.run(middleware(scope, receive, send))

    assert called is False
    assert sent[0]["status"] == 413


def test_structured_and_shipped_logs_only_use_trace_fields():
    """控制台 JSON 和回传负载都应使用 Trace 字段。"""
    token = set_context(RequestContext("request-1", "trace-1", "python-trace-1"))
    try:
        record = logging.LogRecord("worker.test", logging.INFO, __file__, 1, "started", (), None)
        assert ContextFilter().filter(record)
        payload = json.loads(JsonLogFormatter().format(record))

        handler = InspectableTraceHandler()
        handler.emit(record)
        shipped = handler.items.get_nowait()
    finally:
        reset_context(token)

    assert payload["requestId"] == "request-1"
    assert payload["traceId"] == "trace-1"
    assert payload["pythonTraceId"] == "python-trace-1"
    assert shipped["traceId"] == "trace-1"
    assert shipped["pythonTraceId"] == "python-trace-1"
    handler.close()


@pytest.mark.parametrize(
    ("raw", "secret"),
    [
        ('{"api_key": "provider-secret"}', "provider-secret"),
        ("{'token': 'session-secret'}", "session-secret"),
        ("Authorization: Bearer bearer-secret", "bearer-secret"),
        ("password=plain-secret", "plain-secret"),
        ('{"access_token": "access-secret"}', "access-secret"),
        ('{"refresh-token": "refresh-secret"}', "refresh-secret"),
        ('{"client_secret": "client-secret-value"}', "client-secret-value"),
        ("Cookie: session=cookie-secret", "cookie-secret"),
        ("Set-Cookie: BAI_SESSION=session-secret-value", "session-secret-value"),
        ("X-CSRF-Token: csrf-secret", "csrf-secret"),
    ],
)
def test_log_sanitizer_redacts_credential_formats(raw, secret):
    """常见文本、Header 和 JSON 凭据格式都必须脱敏且保留日志主体。"""
    sanitized = sanitize_log_text(f"event=model_call payload={raw}")

    assert "event=model_call" in sanitized
    assert secret not in sanitized
    assert "***" in sanitized


def test_shipped_logs_redact_credentials_from_message_and_exception():
    """回传 Java 的消息和异常堆栈不得绕过统一凭据脱敏。"""
    token = set_context(RequestContext("request-1", "trace-1", "python-trace-1"))
    handler = InspectableTraceHandler()
    try:
        try:
            raise RuntimeError('{"api_key": "exception-secret"}')
        except RuntimeError:
            record = logging.LogRecord(
                "worker.test",
                logging.ERROR,
                __file__,
                1,
                'event=model_call authorization="Bearer message-secret"',
                (),
                sys.exc_info(),
            )
        handler.emit(record)
        shipped = handler.items.get_nowait()
    finally:
        reset_context(token)
        handler.close()

    assert "event=model_call" in shipped["message"]
    assert "message-secret" not in shipped["message"]
    assert "exception-secret" not in shipped["throwable"]
    assert "***" in shipped["message"]
    assert "***" in shipped["throwable"]


def test_trace_log_environment_variable_controls_persist_level(monkeypatch):
    """Worker 只读取新的 Trace 日志级别环境变量。"""
    monkeypatch.setenv("TRACE_LOG_PERSIST_LEVEL", "warn")

    assert load_settings().persist_level == "WARN"


def test_llm_content_logging_is_disabled_by_default(monkeypatch):
    """未显式配置时不得持久化提示词和模型响应正文。"""
    monkeypatch.delenv("LLM_LOG_CONTENT", raising=False)

    assert load_settings().llm_log_content is False
