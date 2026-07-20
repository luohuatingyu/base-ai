import contextvars
from dataclasses import dataclass


@dataclass(frozen=True)
class RequestContext:
    """保存跨服务请求和任务标识。"""

    request_id: str
    parent_job_id: str
    python_job_id: str


_current_context: contextvars.ContextVar[RequestContext | None] = contextvars.ContextVar(
    "request_context", default=None
)


def set_context(context: RequestContext):
    """设置当前异步调用链上下文并返回重置令牌。"""
    return _current_context.set(context)


def reset_context(token) -> None:
    """恢复进入请求前的上下文。"""
    _current_context.reset(token)


def current_context() -> RequestContext | None:
    """读取当前请求上下文。"""
    return _current_context.get()
