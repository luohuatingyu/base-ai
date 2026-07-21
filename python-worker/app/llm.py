import asyncio
import hashlib
import logging
import time
from collections import defaultdict

import httpx

from app.config import Settings
from app.models import ChatMessage, ChatResponse, LlmCandidate

logger = logging.getLogger(__name__)


class LlmClient:
    """按功能候选模型执行并发控制、Key 轮换和故障切换。"""

    def __init__(self, settings: Settings):
        self.settings = settings
        self.client = httpx.AsyncClient(limits=httpx.Limits(max_connections=200, max_keepalive_connections=50))
        self._semaphores: dict[str, asyncio.Semaphore] = {}
        self._key_offsets: defaultdict[str, int] = defaultdict(int)
        self._lock = asyncio.Lock()

    async def chat(self, messages: list[ChatMessage], temperature: float,
                   candidates: list[LlmCandidate] | None = None, enable_thinking: bool = False) -> ChatResponse:
        """依次尝试候选模型和 API Key，首个成功结果立即返回。"""
        configured = candidates or self._fallback_candidates()
        if not configured:
            raise RuntimeError("未配置可用的模型能力路由")
        failures: list[str] = []
        for candidate in configured:
            for api_key in await self._ordered_keys(candidate):
                try:
                    return await self._invoke(candidate, api_key, messages, temperature, enable_thinking)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    failures.append(f"{candidate.providerCode}/{candidate.model}: {type(exception).__name__}")
                    logger.warning("event=llm_candidate_failed provider=%s model=%s error=%s", candidate.providerCode, candidate.model, exception)
        raise RuntimeError("全部候选模型调用失败: " + "; ".join(failures[-10:]))

    async def test(self, candidate: LlmCandidate) -> dict:
        """使用最小请求测试模型连接并返回耗时。"""
        started = time.perf_counter()
        result = await self.chat([ChatMessage(role="user", content="reply OK")], 0, [candidate], False)
        return {"success": True, "model": result.model, "durationMs": round(self._elapsed(started), 2)}

    async def close(self) -> None:
        """关闭共享 HTTP 连接池。"""
        await self.client.aclose()

    async def _invoke(self, candidate: LlmCandidate, api_key: str, messages: list[ChatMessage],
                      temperature: float, enable_thinking: bool) -> ChatResponse:
        """在供应商或 API Key 对应的信号量内执行单次请求。"""
        semaphore = await self._semaphore(candidate, api_key)
        async with semaphore:
            payload = {
                "model": candidate.model,
                "temperature": temperature,
                "messages": [message.model_dump() for message in messages],
                "enable_thinking": enable_thinking,
            }
            started_at = time.perf_counter()
            response = await self.client.post(
                f"{candidate.baseUrl.rstrip('/')}/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"},
                json=payload,
                timeout=candidate.timeoutSeconds,
            )
            response.raise_for_status()
            data = response.json()
            content = data["choices"][0]["message"]["content"]
            usage = data.get("usage", {})
            input_tokens = int(usage.get("prompt_tokens", 0) or 0)
            output_tokens = int(usage.get("completion_tokens", 0) or 0)
            total_tokens = int(usage.get("total_tokens", input_tokens + output_tokens) or 0)
            self._log_success(candidate.model, messages, content, input_tokens, output_tokens, total_tokens, started_at)
            return ChatResponse(content=content, model=candidate.model, inputTokens=input_tokens,
                                outputTokens=output_tokens, totalTokens=total_tokens)

    async def _ordered_keys(self, candidate: LlmCandidate) -> list[str]:
        """并发安全地轮换候选供应商的 API Key 起点。"""
        async with self._lock:
            keys = list(candidate.apiKeys)
            offset = self._key_offsets[candidate.providerCode] % len(keys)
            self._key_offsets[candidate.providerCode] += 1
            return keys[offset:] + keys[:offset]

    async def _semaphore(self, candidate: LlmCandidate, api_key: str) -> asyncio.Semaphore:
        """按供应商或 API Key 维度创建并复用并发信号量。"""
        digest = hashlib.sha256(api_key.encode()).hexdigest()[:12]
        key = candidate.providerCode if candidate.concurrencyLevel == "PROVIDER" else f"{candidate.providerCode}:{digest}"
        async with self._lock:
            return self._semaphores.setdefault(key, asyncio.Semaphore(candidate.concurrencyLimit))

    def _fallback_candidates(self) -> list[LlmCandidate]:
        """从 YAML 组池生成文本中等能力模型候选。"""
        candidates = []
        for pool in self.settings.ai_group_pools:
            candidates.append(LlmCandidate(
                providerCode=pool["pool_id"], baseUrl=pool["base_url"], apiKeys=list(pool["api_keys"]),
                model=pool["model"], concurrencyLimit=pool["concurrency"],
                concurrencyLevel=pool["concurrency_level"],
                timeoutSeconds=max(1, int(self.settings.llm_timeout_seconds)),
            ))
        return candidates

    def _log_success(self, model, messages, content, input_tokens, output_tokens, total_tokens, started_at) -> None:
        """记录模型耗时和 Token，默认不记录完整内容。"""
        logger.info("event=llm_call_succeeded model=%s input_tokens=%d output_tokens=%d total_tokens=%d duration_ms=%.2f",
                    model, input_tokens, output_tokens, total_tokens, self._elapsed(started_at))
        if self.settings.llm_log_content:
            logger.info("event=llm_content messages=%s response=%s", [item.model_dump() for item in messages], content)
        else:
            logger.info("event=llm_content_redacted response_sha256=%s", hashlib.sha256(content.encode()).hexdigest()[:16])

    def _elapsed(self, started_at: float) -> float:
        """计算请求耗时毫秒。"""
        return (time.perf_counter() - started_at) * 1000
