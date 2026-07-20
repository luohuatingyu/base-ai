import hmac
import uuid

from fastapi import Request
from fastapi.responses import JSONResponse
from starlette.middleware.base import BaseHTTPMiddleware

from app.config import Settings
from app.context import RequestContext, reset_context, set_context


class InternalAuthMiddleware(BaseHTTPMiddleware):
    """统一校验 Java 内部令牌并建立跨服务日志上下文。"""

    def __init__(self, app, settings: Settings):
        super().__init__(app)
        self.settings = settings

    async def dispatch(self, request: Request, call_next):
        """放行健康检查，其余接口仅允许持有共享令牌的内部调用。"""
        if request.url.path == "/health":
            return await call_next(request)
        token = request.headers.get("X-Internal-Token", "")
        if not hmac.compare_digest(token, self.settings.internal_token):
            return JSONResponse(status_code=401, content={"detail": "内部令牌无效"})
        request_id = request.headers.get("X-Request-Id") or uuid.uuid4().hex
        parent_job_id = request.headers.get("X-Parent-Job-Id", "")
        python_job_id = uuid.uuid4().hex
        context_token = set_context(RequestContext(request_id, parent_job_id, python_job_id))
        try:
            response = await call_next(request)
            response.headers["X-Request-Id"] = request_id
            response.headers["X-Python-Job-Id"] = python_job_id
            return response
        finally:
            reset_context(context_token)
