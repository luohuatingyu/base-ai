from pydantic import BaseModel, Field


class ChatMessage(BaseModel):
    """单条 OpenAI-compatible 对话消息。"""

    role: str = Field(pattern="^(system|user|assistant)$")
    content: str = Field(min_length=1, max_length=100000)


class LlmCandidate(BaseModel):
    """Java 模型中心下发的单个候选模型配置。"""

    providerCode: str
    baseUrl: str
    apiKeys: list[str] = Field(min_length=1)
    model: str
    concurrencyLimit: int = Field(default=4, ge=1, le=1000)
    concurrencyLevel: str = Field(default="PROVIDER", pattern="^(PROVIDER|API_KEY)$")
    timeoutSeconds: int = Field(default=60, ge=1, le=600)


class ChatRequest(BaseModel):
    """通用模型对话请求。"""

    featureCode: str = "chat"
    model_type: str = "text_model"
    messages: list[ChatMessage] = Field(min_length=1, max_length=100)
    temperature: float = Field(default=0, ge=0, le=2)
    candidates: list[LlmCandidate] = Field(default_factory=list, max_length=20)
    enableThinking: bool | None = None


class LlmTestRequest(BaseModel):
    """模型连接测试请求。"""

    candidate: LlmCandidate


class ChatResponse(BaseModel):
    """通用模型对话及 token 统计响应。"""

    content: str
    model: str
    inputTokens: int
    outputTokens: int
    totalTokens: int
