"""Worker 请求、响应和追踪数据模型定义。"""

from math import isfinite
from typing import Any, Literal
from urllib.parse import urlsplit

from pydantic import BaseModel, Field, field_validator, model_validator


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

    @field_validator("baseUrl")
    @classmethod
    def validate_base_url(cls, value: str) -> str:
        """仅允许无凭证和片段的绝对 HTTP(S) 供应商地址。"""
        parsed = urlsplit(value.strip())
        if parsed.scheme not in {"http", "https"} or not parsed.hostname or parsed.username or parsed.password or parsed.fragment:
            raise ValueError("模型供应商地址必须是安全的 HTTP(S) URL")
        if parsed.port == 0:
            raise ValueError("模型供应商地址端口无效")
        return value.strip().rstrip("/")


class ChatRequest(BaseModel):
    """通用模型对话请求。"""

    featureCode: str = "chat"
    model_type: str = "text_model"
    messages: list[ChatMessage] = Field(min_length=1, max_length=100)
    temperature: float = Field(default=0, ge=0, le=2)
    candidates: list[LlmCandidate] = Field(default_factory=list, max_length=20)
    enableThinking: bool | None = None
    routeConfigured: bool = False


class AgentMessage(BaseModel):
    """Agent 对话消息，允许 OpenAI-compatible 工具调用结果角色。"""

    role: Literal["system", "user", "assistant", "tool"]
    content: str | None = Field(default=None, max_length=100000)
    tool_calls: list[dict[str, Any]] | None = Field(default=None, max_length=20)
    tool_call_id: str | None = Field(default=None, max_length=128)
    name: str | None = Field(default=None, max_length=64)

    @model_validator(mode="after")
    def validate_agent_message(self):
        """保证普通消息有内容、助手可携带调用、工具结果关联调用 ID。"""
        if self.role in {"system", "user"} and not (self.content or "").strip():
            raise ValueError("Agent 系统和用户消息不能为空")
        if self.role == "assistant" and not (self.content or "").strip() and not self.tool_calls:
            raise ValueError("Agent 助手消息必须包含内容或工具调用")
        if self.role == "tool" and (not self.tool_call_id or self.content is None):
            raise ValueError("Agent 工具结果必须包含调用 ID 和内容")
        return self


class ToolDefinition(BaseModel):
    """由 Java 执行器授权给模型的单个工具定义。"""

    name: str = Field(pattern=r"^[A-Za-z_][A-Za-z0-9_-]{0,63}$")
    description: str = Field(min_length=1, max_length=1000)
    parameters: dict[str, Any]

    @model_validator(mode="after")
    def validate_parameters(self):
        """工具参数必须是对象 JSON Schema。"""
        if self.parameters.get("type", "object") != "object":
            raise ValueError("工具参数 Schema 顶层类型必须是 object")
        return self


class AgentStepRequest(BaseModel):
    """执行一次模型工具选择的内部请求。"""

    messages: list[AgentMessage] = Field(min_length=1, max_length=100)
    tools: list[ToolDefinition] = Field(min_length=1, max_length=20)
    candidates: list[LlmCandidate] = Field(min_length=1, max_length=20)
    temperature: float = Field(default=0, ge=0, le=2)
    enableThinking: bool = False

    @model_validator(mode="after")
    def unique_tools(self):
        """拒绝重复工具名，避免模型选择结果产生歧义。"""
        names = [tool.name for tool in self.tools]
        if len(names) != len(set(names)):
            raise ValueError("Agent 工具名称不能重复")
        return self


class AgentToolCall(BaseModel):
    """模型返回的单个工具调用。"""

    id: str
    name: str
    arguments: dict[str, Any]


class AgentStepResponse(BaseModel):
    """一次 Agent 模型决策结果。"""

    content: str = ""
    toolCalls: list[AgentToolCall] = Field(default_factory=list)
    model: str


class LlmTestRequest(BaseModel):
    """模型连接测试请求。"""

    candidate: LlmCandidate
    enableThinking: bool = False
    embedding: bool = False


class EmbeddingRequest(BaseModel):
    """Java 后端下发的 OpenAI 兼容向量化请求。"""

    input: list[str] = Field(min_length=1, max_length=256)
    candidates: list[LlmCandidate] = Field(min_length=1, max_length=20)

    @field_validator("input")
    @classmethod
    def validate_input(cls, values: list[str]) -> list[str]:
        """限制单条参数文本，避免把整份文档或异常内容发送给模型服务。"""
        normalized = [value.strip() for value in values]
        if any(not value or len(value) > 500 for value in normalized):
            raise ValueError("向量文本不能为空且不能超过 500 个字符")
        return normalized


class EmbeddingResponse(BaseModel):
    """向 Java 返回与输入顺序一致的向量和实际使用模型。"""

    embeddings: list[list[float]] = Field(min_length=1, max_length=256)
    model: str = Field(min_length=1, max_length=160)

    @model_validator(mode="after")
    def validate_embeddings(self):
        """拒绝维度不一致、空向量或非有限数，避免污染调用方的向量索引。"""
        dimension = len(self.embeddings[0]) if self.embeddings else 0
        if dimension == 0 or any(
            len(item) != dimension or not all(isfinite(value) for value in item)
            for item in self.embeddings
        ):
            raise ValueError("向量结果为空、维度不一致或包含非法数值")
        return self


class SmtpConfig(BaseModel):
    """Java 后端解析后的单次 SMTP 发送配置。"""

    host: str = Field(min_length=1, max_length=255)
    port: int = Field(ge=1, le=65535)
    username: str = Field(min_length=1, max_length=255)
    fromAddress: str = Field(min_length=3, max_length=255)
    tlsMode: str = Field(pattern="^(NONE|STARTTLS|SSL)$")
    password: str = Field(min_length=1, max_length=4096)

    @field_validator("host", "username", "fromAddress")
    @classmethod
    def reject_header_injection(cls, value: str) -> str:
        """拒绝 SMTP 配置字段中的换行注入。"""
        if "\r" in value or "\n" in value:
            raise ValueError("invalid smtp field")
        return value.strip()


class EmailSendRequest(BaseModel):
    """内部邮件发送请求，密码只在本次调用内使用。"""

    smtp: SmtpConfig
    toAddresses: list[str] = Field(min_length=1, max_length=500)
    ccAddresses: list[str] = Field(default_factory=list, max_length=500)
    subject: str = Field(min_length=1, max_length=255)
    body: str = Field(min_length=1, max_length=1_000_000)

    @field_validator("subject")
    @classmethod
    def reject_subject_injection(cls, value: str) -> str:
        """邮件主题禁止包含换行，避免注入额外头字段。"""
        if "\r" in value or "\n" in value:
            raise ValueError("invalid subject")
        return value.strip()

    @field_validator("toAddresses", "ccAddresses")
    @classmethod
    def validate_addresses(cls, values: list[str]) -> list[str]:
        """校验收件地址基本格式并拒绝邮件头注入。"""
        normalized: list[str] = []
        for value in values:
            address = value.strip()
            if "\r" in address or "\n" in address or address.count("@") != 1:
                raise ValueError("invalid email address")
            local, domain = address.split("@", 1)
            if not local or "." not in domain or domain.startswith(".") or domain.endswith("."):
                raise ValueError("invalid email address")
            if address not in normalized:
                normalized.append(address)
        return normalized


class ChatResponse(BaseModel):
    """通用模型对话及 token 统计响应。"""

    content: str
    model: str
    inputTokens: int
    outputTokens: int
    totalTokens: int
