"""FastAPI 应用入口，定义 Worker 的健康检查、模型调用和追踪端点。"""

import logging
import os

from fastapi import FastAPI, HTTPException

from app.config import load_settings, validate_settings
from app.llm import LlmClient
from app.logging_config import setup_logging
from app.middleware import InternalAuthMiddleware, RequestSizeLimitMiddleware
from app.models import (AgentStepRequest, AgentStepResponse, ChatRequest, ChatResponse,
                        EmailSendRequest, EmbeddingRequest, EmbeddingResponse, LlmTestRequest)
from app.services.email_delivery import MailDeliveryError, send_email
from app.trace_runtime import JavaTraceReporter, TraceRuntimeRegistry

settings = load_settings()
validate_settings(settings)
log_shipper = setup_logging(settings)
logger = logging.getLogger(__name__)
llm_client = LlmClient(settings)
trace_registry = TraceRuntimeRegistry()
trace_reporter = JavaTraceReporter(settings)

app = FastAPI(title=f"{os.getenv('APP_PLATFORM_NAME_EN', 'AI Platform')} Worker", version="0.0.1")
app.add_middleware(InternalAuthMiddleware, settings=settings, registry=trace_registry, reporter=trace_reporter)
app.add_middleware(RequestSizeLimitMiddleware, max_bytes=settings.max_request_bytes)


@app.get("/health")
def health():
    """提供无需内部签名的容器存活检查。"""
    return {"status": "UP", "instanceId": settings.instance_id}


@app.post("/llm/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """执行受内部认证保护的通用模型调用。"""
    logger.info("event=worker_chat_started message_count=%d", len(request.messages))
    return await llm_client.chat(request.messages, request.temperature, request.candidates,
                                 request.enableThinking, request.model_type, request.routeConfigured)


@app.post("/llm/test")
async def test_llm(request: LlmTestRequest):
    """测试模型中心下发的单个候选配置。"""
    return await llm_client.test(request.candidate, request.enableThinking, request.embedding)


@app.post("/llm/embeddings", response_model=EmbeddingResponse)
async def embeddings(request: EmbeddingRequest):
    """通过候选向量模型调用 OpenAI 兼容 embeddings 接口。"""
    logger.info("event=worker_embeddings_started input_count=%d", len(request.input))
    return await llm_client.embed(request.input, request.candidates)


@app.post("/llm/agent-step", response_model=AgentStepResponse)
async def agent_step(request: AgentStepRequest):
    """执行一次受控工具选择，工具本身仍由 Java 工作流执行器调用。"""
    logger.info("event=worker_agent_step_started message_count=%d tool_count=%d",
                len(request.messages), len(request.tools))
    return await llm_client.agent_step(request.messages, request.tools, request.candidates,
                                       request.temperature, request.enableThinking)


@app.post("/email/send")
async def email_send(request: EmailSendRequest):
    """使用 Java 已解析的独立邮件路由配置发送一封内部测试邮件。"""
    try:
        return await send_email(request)
    except MailDeliveryError as exception:
        raise HTTPException(status_code=exception.status_code, detail=exception.detail) from exception


@app.post("/traces/{python_trace_id}/cancel")
async def cancel_trace(python_trace_id: str):
    """取消单个异步子任务，不影响 Worker 内其他任务。"""
    return {"cancelled": await trace_registry.cancel(python_trace_id)}


@app.on_event("shutdown")
async def shutdown_event():
    """应用退出时释放模型连接和日志线程。"""
    await llm_client.close()
    await trace_reporter.close()
    log_shipper.close()
