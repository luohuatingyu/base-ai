"""模型协议注册与兼容映射测试。"""

import pytest

from app.llm_protocol import ChatProtocolAdapter, EmbeddingProtocolAdapter, LlmProtocolRegistry


def test_registry_separates_chat_and_embedding_protocols():
    """显式协议必须解析为对应适配器。"""
    registry = LlmProtocolRegistry()
    assert isinstance(registry.resolve("chat"), ChatProtocolAdapter)
    assert isinstance(registry.resolve("embedding"), EmbeddingProtocolAdapter)


def test_registry_keeps_legacy_embedding_flag_compatible():
    """旧版只传 embedding=true 的请求仍走向量适配器。"""
    registry = LlmProtocolRegistry()
    assert registry.resolve(None, True).name == "embedding"
    assert registry.resolve(None, False).name == "chat"


def test_registry_rejects_unknown_protocol():
    """未知协议不能静默回退到聊天，避免误调用供应商端点。"""
    with pytest.raises(ValueError, match="不支持的模型接入协议"):
        LlmProtocolRegistry().resolve("audio")
