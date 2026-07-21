import logging
import os

from fastapi import FastAPI

from app.config import load_settings, validate_settings
from app.llm import LlmClient
from app.logging_config import setup_logging
from app.middleware import InternalAuthMiddleware
from app.models import ChatRequest, ChatResponse, LlmTestRequest
from app.job_runtime import JavaJobReporter, JobRuntimeRegistry

settings = load_settings()
validate_settings(settings)
log_shipper = setup_logging(settings)
logger = logging.getLogger(__name__)
llm_client = LlmClient(settings)
job_registry = JobRuntimeRegistry()
job_reporter = JavaJobReporter(settings)

app = FastAPI(title=f"{os.getenv('APP_BRAND_NAME_EN', 'AI Platform')} Worker", version="0.0.1")
app.add_middleware(InternalAuthMiddleware, settings=settings, registry=job_registry, reporter=job_reporter)


@app.get("/health")
def health():
    """提供无需内部令牌的容器存活检查。"""
    return {"status": "UP", "instanceId": settings.instance_id}


@app.post("/llm/chat", response_model=ChatResponse)
async def chat(request: ChatRequest):
    """执行受内部认证保护的通用模型调用。"""
    logger.info("event=worker_chat_started message_count=%d", len(request.messages))
    return await llm_client.chat(request.messages, request.temperature, request.candidates,
                                 request.enableThinking, request.model_type)


@app.post("/llm/test")
async def test_llm(request: LlmTestRequest):
    """测试模型中心下发的单个候选配置。"""
    return await llm_client.test(request.candidate)


@app.post("/jobs/{python_job_id}/cancel")
async def cancel_job(python_job_id: str):
    """取消单个异步子任务，不影响 Worker 内其他任务。"""
    return {"cancelled": await job_registry.cancel(python_job_id)}


@app.on_event("shutdown")
async def shutdown_event():
    """应用退出时释放模型连接和日志线程。"""
    await llm_client.close()
    await job_reporter.close()
    log_shipper.close()
