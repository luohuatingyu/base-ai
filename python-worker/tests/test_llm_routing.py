import asyncio
import json

import httpx

from app.config import Settings
from app.llm import LlmClient
from app.models import ChatMessage, LlmCandidate


def test_llm_client_fails_over_to_next_candidate():
    """验证首个候选失败后自动切换到下一模型。"""
    async def scenario():
        settings = Settings("http://backend", "x" * 32, "worker", "", (), "", 1, 10, False, "INFO")
        client = LlmClient(settings)

        async def handler(request: httpx.Request):
            if "first.example" in str(request.url):
                return httpx.Response(500, json={"error": "failed"})
            return httpx.Response(200, content=json.dumps({
                "choices": [{"message": {"content": "ok"}}],
                "usage": {"prompt_tokens": 1, "completion_tokens": 1, "total_tokens": 2},
            }).encode())

        await client.client.aclose()
        client.client = httpx.AsyncClient(transport=httpx.MockTransport(handler))
        candidates = [
            LlmCandidate(providerCode="first", baseUrl="https://first.example/v1", apiKeys=["key-1"], model="m1"),
            LlmCandidate(providerCode="second", baseUrl="https://second.example/v1", apiKeys=["key-2"], model="m2"),
        ]
        result = await client.chat([ChatMessage(role="user", content="hello")], 0, candidates)
        assert result.model == "m2"
        assert result.content == "ok"
        await client.close()

    asyncio.run(scenario())
