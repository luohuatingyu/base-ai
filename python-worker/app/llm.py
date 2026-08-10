import asyncio
import hashlib
import json
import logging
import time
from collections import defaultdict

import httpx

from app.config import Settings
from app.logging_config import sanitize_log_text
from app.models import (AgentMessage, AgentStepResponse, AgentToolCall, ChatMessage,
                        ChatResponse, EmbeddingResponse, LlmCandidate, ToolDefinition)

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
                   candidates: list[LlmCandidate] | None = None, enable_thinking: bool | None = None,
                   model_type: str = "text_model", route_configured: bool = False) -> ChatResponse:
        """依次尝试候选模型和 API Key，首个成功结果立即返回。"""
        configured = candidates
        thinking = bool(enable_thinking)
        if not configured:
            raise RuntimeError("未配置可用的模型能力路由")
        failures: list[str] = []
        for candidate in configured:
            for api_key in await self._ordered_keys(candidate):
                try:
                    return await self._invoke(candidate, api_key, messages, temperature, thinking)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    failures.append(f"{candidate.providerCode}/{candidate.model}: {type(exception).__name__}")
                    logger.warning("event=llm_candidate_failed provider=%s model=%s error=%s",
                                   candidate.providerCode, candidate.model, sanitize_log_text(exception, 1000), exc_info=True)
        raise RuntimeError("全部候选模型调用失败: " + "; ".join(failures[-10:]))

    async def test(self, candidate: LlmCandidate, enable_thinking: bool = False,
                   embedding: bool = False) -> dict:
        """使用最小请求测试模型连接并返回耗时。"""
        started = time.perf_counter()
        if embedding:
            result = await self.embed(["embedding health check"], [candidate])
            return {"success": True, "model": result.model, "dimension": len(result.embeddings[0]),
                    "durationMs": round(self._elapsed(started), 2)}
        result = await self.chat([ChatMessage(role="user", content="reply OK")], 0, [candidate], enable_thinking, route_configured=True)
        return {"success": True, "model": result.model, "durationMs": round(self._elapsed(started), 2)}

    async def embed(self, values: list[str], candidates: list[LlmCandidate]) -> EmbeddingResponse:
        """依次尝试候选及密钥，调用 OpenAI 兼容 embeddings 接口并保持输入顺序。"""
        failures: list[str] = []
        for candidate in candidates:
            for api_key in await self._ordered_keys(candidate):
                try:
                    return await self._invoke_embedding(candidate, api_key, values)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    failures.append(f"{candidate.providerCode}/{candidate.model}: {type(exception).__name__}")
                    logger.warning("event=embedding_candidate_failed provider=%s model=%s error=%s",
                                   candidate.providerCode, candidate.model,
                                   sanitize_log_text(exception, 1000), exc_info=True)
        raise RuntimeError("全部候选向量模型调用失败: " + "; ".join(failures[-10:]))

    async def agent_step(self, messages: list[AgentMessage], tools: list[ToolDefinition],
                         candidates: list[LlmCandidate], temperature: float = 0,
                         enable_thinking: bool = False) -> AgentStepResponse:
        """依次尝试候选模型，返回内容或结构化工具调用而不在 Worker 执行工具。"""
        failures: list[str] = []
        for candidate in candidates:
            for api_key in await self._ordered_keys(candidate):
                try:
                    return await self._invoke_agent(candidate, api_key, messages, tools,
                                                    temperature, enable_thinking)
                except asyncio.CancelledError:
                    raise
                except Exception as exception:
                    failures.append(f"{candidate.providerCode}/{candidate.model}: {type(exception).__name__}")
                    logger.warning("event=agent_candidate_failed provider=%s model=%s error=%s",
                                   candidate.providerCode, candidate.model,
                                   sanitize_log_text(exception, 1000), exc_info=True)
        raise RuntimeError("全部 Agent 候选模型调用失败: " + "; ".join(failures[-10:]))

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
                "messages": [message.model_dump(exclude_none=True) for message in messages],
                "enable_thinking": enable_thinking,
                "stream": False,
            }
            if enable_thinking and candidate.thinkingParameter and candidate.thinkingValue:
                payload[candidate.thinkingParameter] = candidate.thinkingValue
            started_at = time.perf_counter()
            async with self.client.stream(
                "POST", f"{candidate.baseUrl.rstrip('/')}/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"}, json=payload,
                timeout=candidate.timeoutSeconds,
            ) as response:
                response.raise_for_status()
                response_body = bytearray()
                async for chunk in response.aiter_bytes():
                    response_body.extend(chunk)
                    if len(response_body) > self.settings.llm_response_max_bytes:
                        raise RuntimeError(f"供应商 {candidate.providerCode} 响应超过大小限制")
                data = self._parse_response(bytes(response_body), response.status_code,
                                            response.headers.get("content-type", "未提供"), candidate)
            content = self._extract_content(data, candidate)
            usage = data.get("usage", {})
            input_tokens = int(usage.get("prompt_tokens", 0) or 0)
            output_tokens = int(usage.get("completion_tokens", 0) or 0)
            total_tokens = int(usage.get("total_tokens", input_tokens + output_tokens) or 0)
            self._log_success(candidate.model, messages, content, input_tokens, output_tokens, total_tokens, started_at)
            return ChatResponse(content=content, model=candidate.model, inputTokens=input_tokens,
                                outputTokens=output_tokens, totalTokens=total_tokens)

    async def _invoke_agent(self, candidate: LlmCandidate, api_key: str,
                            messages: list[AgentMessage], tools: list[ToolDefinition],
                            temperature: float, enable_thinking: bool) -> AgentStepResponse:
        """发送 OpenAI-compatible tools 请求并严格解析首个消息。"""
        semaphore = await self._semaphore(candidate, api_key)
        async with semaphore:
            payload = {
                "model": candidate.model,
                "temperature": temperature,
                "messages": [message.model_dump(exclude_none=True) for message in messages],
                "tools": [{"type": "function", "function": tool.model_dump()} for tool in tools],
                "tool_choice": "auto",
                "enable_thinking": enable_thinking,
                "stream": False,
            }
            if enable_thinking and candidate.thinkingParameter and candidate.thinkingValue:
                payload[candidate.thinkingParameter] = candidate.thinkingValue
            async with self.client.stream(
                "POST", f"{candidate.baseUrl.rstrip('/')}/chat/completions",
                headers={"Authorization": f"Bearer {api_key}"}, json=payload,
                timeout=candidate.timeoutSeconds,
            ) as response:
                response.raise_for_status()
                response_body = bytearray()
                async for chunk in response.aiter_bytes():
                    response_body.extend(chunk)
                    if len(response_body) > self.settings.llm_response_max_bytes:
                        raise RuntimeError(f"供应商 {candidate.providerCode} 响应超过大小限制")
                data = self._parse_response(bytes(response_body), response.status_code,
                                            response.headers.get("content-type", "未提供"), candidate)
            try:
                message = data["choices"][0]["message"]
            except (IndexError, KeyError, TypeError) as exception:
                raise RuntimeError("Agent 响应缺少 choices[0].message") from exception
            content = message.get("content") or ""
            if not isinstance(content, str):
                raise RuntimeError("Agent 响应 content 不是字符串")
            calls: list[AgentToolCall] = []
            raw_calls = message.get("tool_calls") or []
            if not isinstance(raw_calls, list) or len(raw_calls) > 20:
                raise RuntimeError("Agent 工具调用格式或数量无效")
            for raw_call in raw_calls:
                try:
                    call_id = str(raw_call["id"])
                    function = raw_call["function"]
                    name = str(function["name"])
                    arguments = json.loads(function.get("arguments") or "{}")
                except (KeyError, TypeError, json.JSONDecodeError) as exception:
                    raise RuntimeError("Agent 工具调用参数无效") from exception
                if not isinstance(arguments, dict):
                    raise RuntimeError("Agent 工具调用参数必须是对象")
                calls.append(AgentToolCall(id=call_id, name=name, arguments=arguments))
            if not content and not calls:
                raise RuntimeError("Agent 响应既无内容也无工具调用")
            logger.info("event=agent_step_succeeded model=%s tool_call_count=%d", candidate.model, len(calls))
            return AgentStepResponse(content=content, toolCalls=calls, model=candidate.model)

    async def _invoke_embedding(self, candidate: LlmCandidate, api_key: str,
                                values: list[str]) -> EmbeddingResponse:
        """解析 OpenAI embeddings 响应，并确保结果完整对应原始输入。"""
        semaphore = await self._semaphore(candidate, api_key)
        async with semaphore:
            payload = {"model": candidate.model, "input": values}
            async with self.client.stream(
                "POST", f"{candidate.baseUrl.rstrip('/')}/embeddings",
                headers={"Authorization": f"Bearer {api_key}"}, json=payload,
                timeout=candidate.timeoutSeconds,
            ) as response:
                response.raise_for_status()
                response_body = bytearray()
                async for chunk in response.aiter_bytes():
                    response_body.extend(chunk)
                    if len(response_body) > self.settings.llm_response_max_bytes:
                        raise RuntimeError(f"供应商 {candidate.providerCode} 响应超过大小限制")
                data = self._parse_response(bytes(response_body), response.status_code,
                                            response.headers.get("content-type", "未提供"), candidate)
        raw_items = data.get("data")
        if not isinstance(raw_items, list) or len(raw_items) != len(values):
            raise RuntimeError(f"供应商 {candidate.providerCode} 返回的向量数量不匹配")
        indexed: dict[int, list[float]] = {}
        for item in raw_items:
            if (not isinstance(item, dict) or isinstance(item.get("index"), bool)
                    or not isinstance(item.get("index"), int)
                    or not isinstance(item.get("embedding"), list)):
                raise RuntimeError(f"供应商 {candidate.providerCode} 返回的向量格式无效")
            index = item["index"]
            embedding = item["embedding"]
            if (index < 0 or index >= len(values) or index in indexed
                    or not all(not isinstance(value, bool) and isinstance(value, (int, float))
                               for value in embedding)):
                raise RuntimeError(f"供应商 {candidate.providerCode} 返回的向量索引或值无效")
            indexed[index] = [float(value) for value in embedding]
        if len(indexed) != len(values):
            raise RuntimeError(f"供应商 {candidate.providerCode} 返回的向量索引不完整")
        result = EmbeddingResponse(
            embeddings=[indexed[index] for index in range(len(values))],
            model=candidate.model,
        )
        logger.info("event=embedding_succeeded model=%s input_count=%d dimension=%d", candidate.model,
                    len(values), len(result.embeddings[0]))
        return result

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

    def _fallback_candidates(self, feature: dict | None = None) -> list[LlmCandidate]:
        """按业务功能配置从 YAML 组池生成模型候选。"""
        selected = feature or self._feature_config("text_model")
        candidates = []
        for pool in self.settings.ai_group_pools:
            model_config = pool["models"].get(selected["model_type"], {}).get(selected["capability_level"])
            if not model_config:
                continue
            model = model_config["model"] if isinstance(model_config, dict) else model_config
            thinking_value = model_config.get("thinking_levels", {}).get(selected["thinking_level"]) if isinstance(model_config, dict) else None
            if selected["enable_thinking"] and not thinking_value:
                continue
            candidates.append(LlmCandidate(
                providerCode=pool["pool_id"], baseUrl=pool["base_url"], apiKeys=list(pool["api_keys"]),
                model=model, concurrencyLimit=pool["concurrency"],
                concurrencyLevel=pool["concurrency_level"],
                timeoutSeconds=max(1, int(self.settings.llm_timeout_seconds)),
                thinkingParameter=pool.get("thinking_parameter", "reasoning_effort") if selected["enable_thinking"] else None,
                thinkingValue=thinking_value,
            ))
        return candidates

    def _feature_config(self, model_type: str) -> dict:
        """合并通用业务配置和请求指定的动态模型类型。"""
        configured = self.settings.ai_features.get("model", {})
        return {
            "model_type": str(model_type or "").strip() or "text_model",
            "capability_level": configured.get("capability_level", "middle"),
            "enable_thinking": configured.get("enable_thinking", False),
            "thinking_level": configured.get("thinking_level"),
        }

    def _parse_response(self, body: bytes, status_code: int, content_type: str, candidate: LlmCandidate) -> dict:
        """校验供应商响应为 JSON 对象，避免空响应被误报为 Worker 内部异常。"""
        try:
            data = json.loads(body)
        except (json.JSONDecodeError, UnicodeDecodeError) as exception:
            raise RuntimeError(
                f"供应商 {candidate.providerCode} 返回非 JSON 或空响应"
                f"（HTTP {status_code}，Content-Type: {content_type}）"
            ) from exception
        if not isinstance(data, dict):
            raise RuntimeError(f"供应商 {candidate.providerCode} 返回的 JSON 根节点不是对象")
        return data

    def _extract_content(self, data: dict, candidate: LlmCandidate) -> str:
        """提取 OpenAI 兼容响应中的首个消息内容并校验必要字段。"""
        try:
            content = data["choices"][0]["message"]["content"]
        except (IndexError, KeyError, TypeError) as exception:
            raise RuntimeError(
                f"供应商 {candidate.providerCode} 返回的 JSON 缺少 choices[0].message.content"
            ) from exception
        if not isinstance(content, str):
            raise RuntimeError(f"供应商 {candidate.providerCode} 返回的消息内容不是字符串")
        return content

    def _log_success(self, model, messages, content, input_tokens, output_tokens, total_tokens, started_at) -> None:
        """记录模型耗时和 Token，默认不记录完整内容。"""
        logger.info("event=llm_call_succeeded model=%s input_tokens=%d output_tokens=%d total_tokens=%d duration_ms=%.2f",
                    model, input_tokens, output_tokens, total_tokens, self._elapsed(started_at))
        if self.settings.llm_log_content:
            # 分离系统提示词、用户提示词和助手消息
            system_messages = [msg for msg in messages if msg.role == "system"]
            user_messages = [msg for msg in messages if msg.role == "user"]
            assistant_messages = [msg for msg in messages if msg.role == "assistant"]

            # 记录系统提示词（即使为空也记录）
            if system_messages:
                system_content = "\n\n".join([self._message_log_content(msg) for msg in system_messages])
                logger.info("event=llm_system_prompt content=%s", sanitize_log_text(system_content, 4000))
            else:
                logger.info("event=llm_system_prompt content=")

            # 记录用户提示词
            if user_messages:
                user_content = "\n\n".join([self._message_log_content(msg) for msg in user_messages])
                logger.info("event=llm_user_prompt content=%s", sanitize_log_text(user_content, 4000))

            # 记录之前的助手消息（对话历史）
            if assistant_messages:
                for idx, msg in enumerate(assistant_messages[-5:]):  # 最多记录最近5条
                    logger.info("event=llm_assistant_history index=%d content=%s", idx,
                                sanitize_log_text(self._message_log_content(msg), 2000))

            # 记录模型响应
            logger.info("event=llm_model_response content=%s", sanitize_log_text(content, 4000))
        else:
            logger.info("event=llm_content_redacted response_sha256=%s", hashlib.sha256(content.encode()).hexdigest()[:16])

    def _message_log_content(self, message: ChatMessage) -> str:
        """将消息转换为不泄露 Base64 图片内容的日志摘要。"""
        if isinstance(message.content, str):
            return message.content
        parts = []
        for part in message.content:
            if part.type == "text":
                parts.append(part.text or "")
            else:
                parts.append("[image omitted]")
        return " ".join(parts)

    def _elapsed(self, started_at: float) -> float:
        """计算请求耗时毫秒。"""
        return (time.perf_counter() - started_at) * 1000
