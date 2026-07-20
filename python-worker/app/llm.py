import asyncio
import hashlib
import itertools
import logging
import time

import httpx

from app.config import Settings
from app.models import ChatMessage, ChatResponse

logger = logging.getLogger(__name__)


class LlmClient:
    """复用连接池并按 API Key 轮询调用 OpenAI-compatible 接口。"""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.semaphore = asyncio.Semaphore(settings.llm_concurrency)
        self.key_cycle = itertools.cycle(settings.llm_api_keys) if settings.llm_api_keys else None
        self.key_lock = asyncio.Lock()
        self.client = httpx.AsyncClient(
            timeout=settings.llm_timeout_seconds,
            limits=httpx.Limits(max_connections=100, max_keepalive_connections=20),
        )

    async def chat(self, messages: list[ChatMessage], temperature: float) -> ChatResponse:
        """限流调用模型并返回统一 token 统计。"""
        self._validate_configuration()
        async with self.semaphore:
            api_key = await self._next_key()
            payload = {
                "model": self.settings.llm_model,
                "temperature": temperature,
                "messages": [message.model_dump() for message in messages],
            }
            started_at = time.perf_counter()
            try:
                response = await self.client.post(
                    f"{self.settings.llm_base_url}/chat/completions",
                    headers={"Authorization": f"Bearer {api_key}"},
                    json=payload,
                )
                response.raise_for_status()
                data = response.json()
                content = data["choices"][0]["message"]["content"]
                usage = data.get("usage", {})
                input_tokens = int(usage.get("prompt_tokens", 0) or 0)
                output_tokens = int(usage.get("completion_tokens", 0) or 0)
                total_tokens = int(usage.get("total_tokens", input_tokens + output_tokens) or 0)
            except Exception:
                logger.exception("event=llm_call_failed model=%s duration_ms=%.2f", self.settings.llm_model, self._elapsed(started_at))
                raise
            self._log_success(messages, content, input_tokens, output_tokens, total_tokens, started_at)
            return ChatResponse(
                content=content,
                model=self.settings.llm_model,
                inputTokens=input_tokens,
                outputTokens=output_tokens,
                totalTokens=total_tokens,
            )

    async def close(self) -> None:
        """关闭共享 HTTP 连接池。"""
        await self.client.aclose()

    async def _next_key(self) -> str:
        """并发安全地轮询下一个 API Key。"""
        async with self.key_lock:
            return next(self.key_cycle)

    def _validate_configuration(self) -> None:
        """在模型调用前检查统一环境变量是否完整。"""
        if not self.settings.llm_base_url or not self.settings.llm_model or not self.settings.llm_api_keys:
            raise RuntimeError("LLM_BASE_URL、LLM_API_KEYS 和 LLM_MODEL 必须配置")

    def _log_success(self, messages, content, input_tokens, output_tokens, total_tokens, started_at) -> None:
        """默认只记录摘要，显式开启后才记录完整模型内容。"""
        fields = (
            self.settings.llm_model,
            input_tokens,
            output_tokens,
            total_tokens,
            self._elapsed(started_at),
        )
        logger.info("event=llm_call_succeeded model=%s input_tokens=%d output_tokens=%d total_tokens=%d duration_ms=%.2f", *fields)
        if self.settings.llm_log_content:
            logger.info("event=llm_content messages=%s response=%s", [item.model_dump() for item in messages], content)
        else:
            digest = hashlib.sha256(content.encode("utf-8")).hexdigest()[:16]
            logger.info("event=llm_content_redacted response_sha256=%s", digest)

    def _elapsed(self, started_at: float) -> float:
        """计算模型请求耗时毫秒数。"""
        return (time.perf_counter() - started_at) * 1000
