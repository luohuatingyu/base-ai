from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    """单条 OpenAI-compatible 对话消息。"""

    role: str = Field(pattern="^(system|user|assistant)$")
    content: str = Field(min_length=1, max_length=100000)


class ChatRequest(BaseModel):
    """通用模型对话请求。"""

    messages: list[ChatMessage] = Field(min_length=1, max_length=100)
    temperature: float = Field(default=0, ge=0, le=2)


class ChatResponse(BaseModel):
    """通用模型对话及 token 统计响应。"""

    content: str
    model: str
    inputTokens: int
    outputTokens: int
    totalTokens: int
