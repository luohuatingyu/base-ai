import asyncio
import json

import httpx
import pytest

from app.config import Settings
from app.llm import LlmClient
from app.models import AgentMessage, AgentStepRequest, LlmCandidate, ToolDefinition


def candidate():
    """构造不访问真实供应商的 Agent 协议候选模型。"""
    return LlmCandidate(providerCode="test", baseUrl="https://example.com/v1", apiKeys=["key"], model="model")


def test_agent_step_request_requires_bounded_tools():
    """Agent 单步请求必须接受结构化工具并拒绝重复工具名。"""
    request = AgentStepRequest(
        messages=[AgentMessage(role="user", content="查询订单")],
        candidates=[candidate()],
        tools=[ToolDefinition(name="lookup", description="查询", parameters={"type": "object"})],
    )
    assert request.tools[0].name == "lookup"

    with pytest.raises(ValueError):
        AgentStepRequest(
            messages=[AgentMessage(role="user", content="查询订单")],
            candidates=[candidate()],
            tools=[
                ToolDefinition(name="lookup", description="查询", parameters={"type": "object"}),
                ToolDefinition(name="lookup", description="重复", parameters={"type": "object"}),
            ],
        )


def test_agent_step_sends_tools_and_parses_function_call():
    """Worker 必须按 OpenAI tools 格式发送并解析对象参数。"""
    request_holder = {}

    def handler(request: httpx.Request) -> httpx.Response:
        request_holder["body"] = json.loads(request.content)
        return httpx.Response(200, json={"choices": [{"message": {"content": None, "tool_calls": [{
            "id": "call-1", "type": "function",
            "function": {"name": "lookup", "arguments": "{\"orderId\":123}"},
        }]}}]})

    async def invoke():
        client = LlmClient(Settings(
            backend_url="http://backend:8080", internal_token="x" * 24, instance_id="worker-test",
            llm_timeout_seconds=10, llm_log_content=False, persist_level="INFO",
        ))
        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        try:
            return await client._invoke_agent(
                candidate(), "key", [AgentMessage(role="user", content="查询订单")],
                [ToolDefinition(name="lookup", description="查询", parameters={"type": "object"})], 0, False,
            )
        finally:
            await client.close()

    result = asyncio.run(invoke())

    assert request_holder["body"]["tools"][0]["function"]["name"] == "lookup"
    assert result.toolCalls[0].arguments == {"orderId": 123}
