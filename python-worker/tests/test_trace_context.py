import asyncio
import json
import logging
import queue

from fastapi import Request
from starlette.responses import Response

from app.config import Settings, load_settings
from app.context import RequestContext, reset_context, set_context
from app.logging_config import ContextFilter, JavaLogShipHandler, JsonLogFormatter
from app.middleware import InternalAuthMiddleware


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
        ai_model_pools_file="/missing/pools.yml",
        ai_feature_routing_file="/missing/routes.yml",
        ai_group_pools=(),
        ai_features={},
        llm_timeout_seconds=10,
        llm_log_content=False,
        persist_level="INFO",
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


def test_trace_log_environment_variable_controls_persist_level(monkeypatch):
    """Worker 只读取新的 Trace 日志级别环境变量。"""
    monkeypatch.setenv("TRACE_LOG_PERSIST_LEVEL", "warn")

    assert load_settings().persist_level == "WARN"
