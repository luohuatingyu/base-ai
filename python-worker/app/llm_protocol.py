"""按模型接入协议拆分 Worker 的健康检查选择。"""

from __future__ import annotations

import asyncio
import logging
from dataclasses import dataclass
from typing import TYPE_CHECKING

from app.logging_config import sanitize_log_text
from app.models import AgentMessage, ChatMessage, ChatResponse, EmbeddingResponse, LlmCandidate

logger = logging.getLogger(__name__)

if TYPE_CHECKING:
    from app.llm import LlmClient


class LlmProtocolAdapter:
    """定义一种模型协议的最小健康检查行为。"""

    name: str

    async def call(self, client: "LlmClient", *args, **kwargs):
        """执行协议调用；具体适配器负责候选遍历和故障切换。"""
        raise NotImplementedError

    async def test(self, client: "LlmClient", candidate: LlmCandidate,
                   enable_thinking: bool) -> dict:
        """使用协议最小请求验证供应商连接。"""
        raise NotImplementedError


@dataclass(frozen=True)
class ChatProtocolAdapter(LlmProtocolAdapter):
    """聊天、视觉和历史自定义聊天类型共用的对话协议。"""

    name: str = "chat"

    async def call(self, client: "LlmClient", messages: list[ChatMessage], temperature: float,
                   candidates: list[LlmCandidate] | None, enable_thinking: bool | None = None,
                   model_type: str = "text_model", route_configured: bool = False) -> ChatResponse:
        """依次调用聊天候选，隔离聊天协议的故障切换逻辑。"""
        configured = candidates
        if not configured:
            raise RuntimeError("未配置可用的模型能力路由")
        failures: list[str] = []
        for candidate in configured:
            for api_key in await client._ordered_keys(candidate):
                try:
                    return await client._invoke(candidate, api_key, messages, temperature, bool(enable_thinking))
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    failures.append(f"{candidate.providerCode}/{candidate.model}: {type(exception).__name__}")
                    logger.warning("event=llm_candidate_failed provider=%s model=%s error=%s",
                                   candidate.providerCode, candidate.model,
                                   sanitize_log_text(exception, 1000), exc_info=True)
        raise RuntimeError("全部候选模型调用失败: " + "; ".join(failures[-10:]))

    async def test(self, client: "LlmClient", candidate: LlmCandidate,
                   enable_thinking: bool) -> dict:
        """发送最小聊天请求，不触碰向量协议。"""
        result = await client.chat(
            client.health_chat_messages(), 0, [candidate], enable_thinking,
            model_type="text_model", route_configured=True,
        )
        return {"success": True, "model": result.model}


@dataclass(frozen=True)
class EmbeddingProtocolAdapter(LlmProtocolAdapter):
    """OpenAI-compatible embeddings 协议。"""

    name: str = "embedding"

    async def call(self, client: "LlmClient", values: list[str],
                   candidates: list[LlmCandidate]) -> EmbeddingResponse:
        """依次调用向量候选，隔离 embeddings 协议的故障切换逻辑。"""
        failures: list[str] = []
        for candidate in candidates:
            for api_key in await client._ordered_keys(candidate):
                try:
                    return await client._invoke_embedding(candidate, api_key, values)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    failures.append(f"{candidate.providerCode}/{candidate.model}: {type(exception).__name__}")
                    logger.warning("event=embedding_candidate_failed provider=%s model=%s error=%s",
                                   candidate.providerCode, candidate.model,
                                   sanitize_log_text(exception, 1000), exc_info=True)
        raise RuntimeError("全部候选向量模型调用失败: " + "; ".join(failures[-10:]))

    async def test(self, client: "LlmClient", candidate: LlmCandidate,
                   enable_thinking: bool) -> dict:
        """发送最小向量请求并返回维度，不调用聊天端点。"""
        result = await client.embed(["embedding health check"], [candidate])
        return {"success": True, "model": result.model, "dimension": len(result.embeddings[0])}


class LlmProtocolRegistry:
    """将协议名称映射到独立适配器，兼容旧版 embedding 布尔参数。"""

    def __init__(self, adapters: list[LlmProtocolAdapter] | None = None):
        configured = adapters or [ChatProtocolAdapter(), EmbeddingProtocolAdapter()]
        self._adapters = {adapter.name: adapter for adapter in configured}

    def resolve(self, protocol: str | None = None, legacy_embedding: bool = False) -> LlmProtocolAdapter:
        """解析显式协议；旧请求只在 embedding=true 时选择向量协议。"""
        name = (protocol or ("embedding" if legacy_embedding else "chat")).strip().lower()
        try:
            return self._adapters[name]
        except KeyError as exception:
            raise ValueError(f"不支持的模型接入协议: {name}") from exception
