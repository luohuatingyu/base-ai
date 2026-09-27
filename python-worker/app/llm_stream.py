"""真实流式对话，复用供应商策略、网络策略及并发额度。"""

import asyncio
import json
import logging
from contextlib import aclosing

from app.llm import usage_tokens
from app.llm_provider import strategy_for


logger = logging.getLogger(__name__)


async def stream_chat(client, request):
    """输出前可故障切换，输出后失败立即终止，防止拼接不同回答。"""
    emitted = False
    for candidate in request.candidates or []:
        for api_key in await client._ordered_keys(candidate):
            try:
                semaphore = await client._semaphore(candidate, api_key)
                async with semaphore:
                    payload = strategy_for(candidate).build_chat_payload(
                        candidate, request.messages, request.temperature, bool(request.enableThinking))
                    payload["stream"] = True
                    payload["stream_options"] = {"include_usage": True}
                    async with client.client.stream(
                        "POST", f"{candidate.baseUrl.rstrip('/')}/chat/completions",
                        headers={"Authorization": f"Bearer {api_key}"}, json=payload,
                        timeout=candidate.timeoutSeconds,
                    ) as response:
                        await client._raise_for_status(response, candidate)
                        if "text/event-stream" not in response.headers.get("content-type", ""):
                            raise RuntimeError("供应商不支持流式响应")
                        buffer = bytearray()
                        total = 0
                        usage = {}
                        emitted_usage = None
                        async for chunk in response.aiter_bytes():
                            total += len(chunk)
                            if total > client.settings.llm_response_max_bytes:
                                raise RuntimeError("流式响应超过大小限制")
                            buffer.extend(chunk)
                            while b"\n" in buffer:
                                raw, _, remainder = buffer.partition(b"\n")
                                buffer = bytearray(remainder)
                                line = raw.rstrip(b"\r").decode("utf-8")
                                if not line.startswith("data:"):
                                    continue
                                data = line[5:].strip()
                                if data == "[DONE]":
                                    input_tokens, output_tokens, total_tokens = usage_tokens(usage)
                                    yield {"type": "done", "model": candidate.model,
                                           "inputTokens": input_tokens,
                                           "outputTokens": output_tokens,
                                           "totalTokens": total_tokens}
                                    return
                                item = json.loads(data)
                                if item.get("error"):
                                    raise RuntimeError("供应商流式调用失败")
                                if isinstance(item.get("usage"), dict):
                                    usage.update(item["usage"])
                                    input_tokens, output_tokens, total_tokens = usage_tokens(usage)
                                    usage_event = (input_tokens, output_tokens, total_tokens)
                                    if usage_event != emitted_usage:
                                        emitted_usage = usage_event
                                        yield {"type": "usage", "model": candidate.model,
                                               "inputTokens": input_tokens, "outputTokens": output_tokens,
                                               "totalTokens": total_tokens}
                                choices = item.get("choices") or []
                                if not choices:
                                    continue
                                choice = choices[0]
                                content = choice.get("delta", {}).get("content")
                                if content is not None and not isinstance(content, str):
                                    raise RuntimeError("流式文本格式无效")
                                if content:
                                    emitted = True
                                    yield {"type": "delta", "content": content}
                        # 部分 OpenAI 兼容服务会在连接关闭前省略最后一个换行，
                        # 但仍会发送完整的 data: [DONE] 终止帧；不能丢弃该帧。
                        tail = bytes(buffer).rstrip(b"\r")
                        if tail:
                            line = tail.decode("utf-8")
                            if line.startswith("data:") and line[5:].strip() == "[DONE]":
                                input_tokens, output_tokens, total_tokens = usage_tokens(usage)
                                yield {"type": "done", "model": candidate.model,
                                       "inputTokens": input_tokens,
                                       "outputTokens": output_tokens,
                                       "totalTokens": total_tokens}
                                return
                        raise RuntimeError("供应商流式响应意外中断")
            except asyncio.CancelledError:
                raise
            except Exception as exception:
                logger.warning(
                    "event=llm_stream_failed emitted=%s error_type=%s",
                    emitted, type(exception).__name__, exc_info=True,
                )
                if emitted:
                    raise RuntimeError("回答生成中断") from None
    raise RuntimeError("没有可用的流式模型")


async def stream_events(client, request, trace_id, registry, reporter):
    """在完整响应生命周期登记追踪，心跳维持连接，断连时回收生成任务。"""
    queue = asyncio.Queue(maxsize=16)

    async def produce():
        """生产增量事件，有界队列将下游背压传递给供应商。"""
        try:
            async with asyncio.timeout(600):
                async with aclosing(stream_chat(client, request)) as events:
                    async for event in events:
                        await queue.put(event)
        except asyncio.CancelledError:
            raise
        except Exception:
            await queue.put({"type": "error", "message": "ai.serviceCallFailed"})

    producer = asyncio.create_task(produce())
    await registry.register(trace_id, asyncio.current_task())
    status = "CANCELLED"
    first_event = True
    try:
        while True:
            try:
                event = await asyncio.wait_for(queue.get(), timeout=10)
            except TimeoutError:
                # 状态回报不能阻塞首个模型事件或后续增量读取。
                asyncio.create_task(reporter.report(trace_id, "RUNNING"))
                event = {"type": "heartbeat"}
            yield "data: " + json.dumps(event, ensure_ascii=False) + "\n\n"
            if first_event:
                first_event = False
                # 首帧已经交付后再异步回报 RUNNING，避免后端状态接口慢时推迟首字。
                asyncio.create_task(reporter.report(trace_id, "RUNNING"))
            if event["type"] in {"done", "error"}:
                status = "SUCCESS" if event["type"] == "done" else "FAILED"
                return
    finally:
        producer.cancel()
        try:
            await producer
        except asyncio.CancelledError:
            pass
        await registry.remove(trace_id)
        await reporter.report(trace_id, status)
