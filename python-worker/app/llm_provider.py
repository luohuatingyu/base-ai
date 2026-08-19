"""LLM 供应商请求策略，隔离不同 OpenAI-compatible API 的请求差异。"""

from abc import ABC, abstractmethod
from urllib.parse import urlsplit

from app.models import AgentMessage, ChatMessage, LlmCandidate, ToolDefinition


class LlmRequestStrategy(ABC):
    """定义供应商对话和 Agent 请求体的构造协议。"""

    @abstractmethod
    def build_chat_payload(self, candidate: LlmCandidate, messages: list[ChatMessage],
                           temperature: float, enable_thinking: bool) -> dict:
        """构造普通对话请求体。"""

    @abstractmethod
    def build_agent_payload(self, candidate: LlmCandidate, messages: list[AgentMessage],
                            tools: list[ToolDefinition], temperature: float,
                            enable_thinking: bool) -> dict:
        """构造工具调用请求体。"""


class OpenAiCompatibleRequestStrategy(LlmRequestStrategy):
    """保持平台原有请求格式的通用 OpenAI-compatible 策略。"""

    def build_chat_payload(self, candidate: LlmCandidate, messages: list[ChatMessage],
                           temperature: float, enable_thinking: bool) -> dict:
        """构造原有普通对话请求，不改变既有供应商行为。"""
        payload = self._base_payload(candidate, temperature, enable_thinking)
        payload["messages"] = [message.model_dump(exclude_none=True) for message in messages]
        return payload

    def build_agent_payload(self, candidate: LlmCandidate, messages: list[AgentMessage],
                            tools: list[ToolDefinition], temperature: float,
                            enable_thinking: bool) -> dict:
        """构造原有 Agent 请求，不改变既有供应商行为。"""
        payload = self._base_payload(candidate, temperature, enable_thinking)
        payload["messages"] = [message.model_dump(exclude_none=True) for message in messages]
        payload["tools"] = [{"type": "function", "function": tool.model_dump()} for tool in tools]
        payload["tool_choice"] = "auto"
        return payload

    def _base_payload(self, candidate: LlmCandidate, temperature: float,
                      enable_thinking: bool) -> dict:
        """构造通用字段及原有思考参数。"""
        payload = {
            "model": candidate.model,
            "temperature": temperature,
            "enable_thinking": enable_thinking,
            "stream": False,
        }
        if enable_thinking and candidate.thinkingParameter and candidate.thinkingValue:
            payload[candidate.thinkingParameter] = candidate.thinkingValue
        return payload


class NvidiaNemotronRequestStrategy(OpenAiCompatibleRequestStrategy):
    """适配 NVIDIA Nemotron 的思考参数，同时保留 OpenAI 请求结构。"""

    def build_chat_payload(self, candidate: LlmCandidate, messages: list[ChatMessage],
                           temperature: float, enable_thinking: bool) -> dict:
        """构造 NVIDIA 普通对话请求并展开 SDK extra_body 字段。"""
        payload = self._nvidia_base_payload(candidate, temperature, enable_thinking)
        payload["messages"] = [message.model_dump(exclude_none=True) for message in messages]
        return payload

    def build_agent_payload(self, candidate: LlmCandidate, messages: list[AgentMessage],
                            tools: list[ToolDefinition], temperature: float,
                            enable_thinking: bool) -> dict:
        """构造 NVIDIA 工具调用请求并展开 SDK extra_body 字段。"""
        payload = self._nvidia_base_payload(candidate, temperature, enable_thinking)
        payload["messages"] = [message.model_dump(exclude_none=True) for message in messages]
        payload["tools"] = [{"type": "function", "function": tool.model_dump()} for tool in tools]
        payload["tool_choice"] = "auto"
        return payload

    def _nvidia_base_payload(self, candidate: LlmCandidate, temperature: float,
                             enable_thinking: bool) -> dict:
        """构造 NVIDIA 请求字段，避免发送其不支持的顶层 enable_thinking。"""
        payload = {
            "model": candidate.model,
            "temperature": temperature,
            "stream": False,
        }
        if enable_thinking:
            payload["chat_template_kwargs"] = {"enable_thinking": True}
            payload["reasoning_budget"] = self._reasoning_budget(candidate)
        return payload

    def _reasoning_budget(self, candidate: LlmCandidate) -> int:
        """将模型思考等级映射解析为 NVIDIA 要求的正整数预算。"""
        raw_value = str(candidate.thinkingValue or "").strip()
        try:
            budget = int(raw_value)
        except (TypeError, ValueError) as exception:
            raise RuntimeError("NVIDIA 思考模式需要将模型思考等级映射为正整数 reasoning_budget") from exception
        if budget <= 0:
            raise RuntimeError("NVIDIA reasoning_budget 必须是正整数")
        return budget


_NVIDIA_HOSTS = frozenset({"integrate.api.nvidia.com"})


def strategy_for(candidate: LlmCandidate) -> LlmRequestStrategy:
    """根据供应商主机名选择请求策略，未知供应商使用通用策略。"""
    hostname = (urlsplit(candidate.baseUrl).hostname or "").lower()
    if hostname in _NVIDIA_HOSTS:
        return NvidiaNemotronRequestStrategy()
    return OpenAiCompatibleRequestStrategy()
