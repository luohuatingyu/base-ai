"""按模型接入协议拆分 Worker 的健康检查选择。"""

from __future__ import annotations

from dataclasses import dataclass
from typing import TYPE_CHECKING

from app.models import LlmCandidate

if TYPE_CHECKING:
    from app.llm import LlmClient


class LlmProtocolAdapter:
    """定义一种模型协议的最小健康检查行为。"""

    name: str

    async def test(self, client: "LlmClient", candidate: LlmCandidate,
                   enable_thinking: bool) -> dict:
        """使用协议最小请求验证供应商连接。"""
        raise NotImplementedError


@dataclass(frozen=True)
class ChatProtocolAdapter(LlmProtocolAdapter):
    """聊天、视觉和历史自定义聊天类型共用的对话协议。"""

    name: str = "chat"

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
