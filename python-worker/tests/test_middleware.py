import asyncio

from fastapi import FastAPI
from fastapi.testclient import TestClient

from app.config import Settings
from app.middleware import InternalAuthMiddleware


def test_internal_token_is_required():
    """验证非健康接口必须携带内部令牌。"""
    settings = Settings("http://backend", "x" * 32, "worker", "", (), 10, False, "INFO")
    app = FastAPI()
    app.add_middleware(InternalAuthMiddleware, settings=settings)

    @app.get("/private")
    async def private():
        await asyncio.sleep(0)
        return {"ok": True}

    client = TestClient(app)
    assert client.get("/private").status_code == 401
    assert client.get("/private", headers={"X-Internal-Token": "x" * 32}).status_code == 200
