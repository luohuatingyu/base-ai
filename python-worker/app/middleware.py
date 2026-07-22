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
        request_id = self._identifier(request.headers.get("X-Request-Id"), uuid.uuid4().hex)
        parent_trace_id = self._identifier(request.headers.get("X-Parent-Trace-Id"), "")
        python_trace_id = self._identifier(request.headers.get("X-Python-Trace-Id"), uuid.uuid4().hex)
        context_token = set_context(RequestContext(request_id, parent_trace_id, python_trace_id))
        tracked = request.url.path.startswith("/llm/")
        heartbeat_task = None
        status_code = 500
        try:
            if request.url.path != "/health":
                token = request.headers.get("X-Internal-Token", "")
                if not hmac.compare_digest(token, self.settings.internal_token):
                    status_code = 401
                    logger.warning("event=worker_auth_rejected method=%s path=%s", request.method, request.url.path)
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
            logger.warning("event=worker_request_cancelled method=%s path=%s", request.method, request.url.path)
            raise
        except Exception as exception:
            if tracked:
                await self.reporter.report(python_trace_id, "FAILED", str(exception))
            logger.exception("event=worker_request_failed method=%s path=%s", request.method, request.url.path)
            raise
        finally:
            await stop_heartbeat(heartbeat_task)
            if tracked:
                await self.registry.remove(python_trace_id)
            request_logger = logger.debug if request.url.path == "/health" else logger.info
            request_logger("event=worker_http_request method=%s path=%s status=%d duration_ms=%.2f",
                           request.method, request.url.path, status_code, (time.perf_counter() - started_at) * 1000)
            reset_context(context_token)

    def _identifier(self, value: str | None, fallback: str) -> str:
        """校验跨服务标识，避免超长或控制字符污染日志。"""
        return value if value and IDENTIFIER_PATTERN.fullmatch(value) else fallback
