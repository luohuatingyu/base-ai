"""Worker 请求、响应和追踪数据模型定义。"""

from typing import Literal

from pydantic import BaseModel, Field, model_validator


class ChatMessage(BaseModel):
    """单条 OpenAI-compatible 文本或多模态对话消息。"""

    role: str = Field(pattern="^(system|user|assistant)$")
    content: str | list["ContentPart"]

    @model_validator(mode="after")
    def validate_content(self):
        """校验文本和图片内容，限制图片只使用受支持的 Data URL。"""
        if isinstance(self.content, str):
            if not self.content.strip() or len(self.content) > 100000:
                raise ValueError("消息文本不能为空且不能超过 100000 个字符")
            return self
        if not self.content or len(self.content) > 5:
            raise ValueError("多模态消息至少包含一个内容片段且最多包含 5 个片段")
        if not any(part.type == "text" and part.text and part.text.strip() for part in self.content):
            if not any(part.type == "image_url" for part in self.content):
                raise ValueError("多模态消息必须包含文本或图片")
        return self


class ImageUrl(BaseModel):
    """OpenAI-compatible 图片地址。"""

    url: str = Field(min_length=20, max_length=15_000_000)

    @model_validator(mode="after")
    def validate_url(self):
        """仅允许 HTTP(S) 地址或受支持图片格式的 Data URL。"""
        if self.url.startswith(("https://", "http://")):
            return self
        if not self.url.startswith(("data:image/png;base64,", "data:image/jpeg;base64,", "data:image/webp;base64,")):
            raise ValueError("图片必须使用 HTTP(S) 地址或 PNG、JPEG、WEBP Data URL")
        return self


class ContentPart(BaseModel):
    """多模态消息中的文本或图片片段。"""

    type: Literal["text", "image_url"]
    text: str | None = Field(default=None, max_length=100000)
    image_url: ImageUrl | None = None

    @model_validator(mode="after")
    def validate_part(self):
        """保证文本片段和图片片段分别携带正确的数据。"""
        if self.type == "text" and (self.text is None or not self.text.strip() or self.image_url is not None):
            raise ValueError("文本片段必须包含文本且不能包含图片地址")
        if self.type == "image_url" and (self.image_url is None or self.text is not None):
            raise ValueError("图片片段必须包含图片地址且不能包含文本")
        return self


class LlmCandidate(BaseModel):
    """Java 模型中心下发的单个候选模型配置。"""

    providerCode: str
    baseUrl: str
    apiKeys: list[str] = Field(min_length=1)
    model: str
    concurrencyLimit: int = Field(default=4, ge=1, le=1000)
    concurrencyLevel: str = Field(default="PROVIDER", pattern="^(PROVIDER|API_KEY)$")
    timeoutSeconds: int = Field(default=60, ge=1, le=600)
    thinkingParameter: str | None = Field(default=None, max_length=64)
    thinkingValue: str | None = Field(default=None, max_length=100)


class ChatRequest(BaseModel):
    """通用模型对话请求。"""

    featureCode: str = "chat"
    model_type: str = "text_model"
    messages: list[ChatMessage] = Field(min_length=1, max_length=100)
    temperature: float = Field(default=0, ge=0, le=2)
    candidates: list[LlmCandidate] = Field(default_factory=list, max_length=20)
    enableThinking: bool | None = None
    routeConfigured: bool = False


class LlmTestRequest(BaseModel):
    """模型连接测试请求。"""

    candidate: LlmCandidate
    enableThinking: bool = False


class ChatResponse(BaseModel):
    """通用模型对话及 token 统计响应。"""

    content: str
    model: str
    inputTokens: int
    outputTokens: int
    totalTokens: int
