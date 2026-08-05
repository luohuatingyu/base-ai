"""FastAPI 中间件，处理请求标识、内部认证和异常追踪。"""

import asyncio
import hmac
import logging
import re
import time
import uuid

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from app.config import Settings
from app.context import RequestContext, reset_context, set_context
from app.trace_runtime import JavaTraceReporter, TraceRuntimeRegistry, stop_heartbeat

logger = logging.getLogger(__name__)
IDENTIFIER_PATTERN = re.compile(r"^[A-Za-z0-9._:-]{1,64}$")
HOST_PATTERN = re.compile(r"^(?:[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\[[0-9A-Fa-f:.]+\])(?::\d{1,5})?$")


class RequestSizeLimitMiddleware:
    """在进入 FastAPI 解析前缓存并限制完整 ASGI 请求体。"""

    def __init__(self, app, max_bytes: int):
        self.app = app
        self.max_bytes = max(1, max_bytes)

    async def __call__(self, scope, receive, send):
        """同时处理声明长度和分块传输，超限时返回 413。"""
        if scope.get("type") != "http":
            await self.app(scope, receive, send)
            return
        headers = {key.lower(): value for key, value in scope.get("headers", [])}
        declared = headers.get(b"content-length")
        if declared:
            try:
                if int(declared) > self.max_bytes:
                    await self._reject(scope, receive, send)
                    return
            except ValueError:
                await self._reject(scope, receive, send)
                return
        messages = []
        total = 0
        while True:
            message = await receive()
            messages.append(message)
            if message.get("type") != "http.request":
                break
            total += len(message.get("body", b""))
            if total > self.max_bytes:
                await self._reject(scope, receive, send)
                return
            if not message.get("more_body", False):
                break

        async def replay_receive():
            """依次重放已验证的 ASGI 消息供下游正常解析。"""
            return messages.pop(0) if messages else {"type": "http.disconnect"}

        await self.app(scope, replay_receive, send)

    async def _reject(self, scope, receive, send):
        """返回不包含请求内容的固定超限响应。"""
        response = JSONResponse(status_code=413, content={"detail": "请求体超过大小限制"})
        await response(scope, receive, send)


class InternalAuthMiddleware(BaseHTTPMiddleware):
    """统一校验 Java 内部令牌并建立跨服务日志上下文。"""

    def __init__(self, app, settings: Settings, registry: TraceRuntimeRegistry | None = None, reporter: JavaTraceReporter | None = None):
        super().__init__(app)
        self.settings = settings
        self.registry = registry or TraceRuntimeRegistry()
        self.reporter = reporter or JavaTraceReporter(settings)

    async def dispatch(self, request: Request, call_next):
        """放行健康检查，其余接口仅允许持有共享令牌的内部调用。"""
        started_at = time.perf_counter()
        request_path = str(request.scope.get("path") or "")
        request_id = self._identifier(request.headers.get("X-Request-Id"), uuid.uuid4().hex)
        parent_trace_id = self._identifier(request.headers.get("X-Parent-Trace-Id"), "")
        python_trace_id = self._identifier(request.headers.get("X-Python-Trace-Id"), uuid.uuid4().hex)
        context_token = set_context(RequestContext(request_id, parent_trace_id, python_trace_id))
        tracked = request_path.startswith("/llm/")
        heartbeat_task = None
        status_code = 500
        try:
            if not self._host_allowed(request):
                status_code = 400
                logger.warning("event=worker_host_rejected method=%s path=%s", request.method, request_path)
                return JSONResponse(status_code=400, content={"detail": "请求 Host 无效"})
            if request_path != "/health":
                token = request.headers.get("X-Internal-Token", "")
                if not hmac.compare_digest(token, self.settings.internal_token):
                    status_code = 401
                    logger.warning("event=worker_auth_rejected method=%s path=%s", request.method, request_path)
                    return JSONResponse(status_code=401, content={"detail": "内部令牌无效"})
            if tracked:
                await self.registry.register(python_trace_id, asyncio.current_task())
                await self.reporter.report(python_trace_id, "RUNNING")
                heartbeat_task = asyncio.create_task(self.reporter.heartbeat(python_trace_id))
            response = await call_next(request)
            status_code = response.status_code
            if tracked:
                await self.reporter.report(python_trace_id, "SUCCESS" if response.status_code < 400 else "FAILED")
            response.headers["X-Request-Id"] = request_id
            response.headers["X-Python-Trace-Id"] = python_trace_id
            return response
        except asyncio.CancelledError:
            status_code = 499
            if tracked:
                await self.reporter.report(python_trace_id, "CANCELLED", "任务已取消")
            logger.warning("event=worker_request_cancelled method=%s path=%s", request.method, request_path)
            raise
        except Exception as exception:
            if tracked:
                await self.reporter.report(python_trace_id, "FAILED", str(exception))
            logger.exception("event=worker_request_failed method=%s path=%s", request.method, request_path)
            raise
        finally:
            await stop_heartbeat(heartbeat_task)
            if tracked:
                await self.registry.remove(python_trace_id)
            request_logger = logger.debug if request_path == "/health" else logger.info
            request_logger("event=worker_http_request method=%s path=%s status=%d duration_ms=%.2f",
                           request.method, request_path, status_code, (time.perf_counter() - started_at) * 1000)
            reset_context(context_token)

    def _host_allowed(self, request: Request) -> bool:
        """仅接受配置的内部 Host，避免畸形 Host 污染 URL 安全判断。"""
        raw_host = request.headers.get("host")
        if not raw_host:
            server = request.scope.get("server")
            raw_host = str(server[0]) if server else ""
        if not raw_host or not HOST_PATTERN.fullmatch(raw_host):
            return False
        host = raw_host.rsplit(":", 1)[0].strip("[]").lower()
        return host in self.settings.allowed_hosts

    def _identifier(self, value: str | None, fallback: str) -> str:
        """校验跨服务标识，避免超长或控制字符污染日志。"""
        return value if value and IDENTIFIER_PATTERN.fullmatch(value) else fallback
