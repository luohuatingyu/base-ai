"""提供不依赖 Dify SDK 的最小插件 ABI 兼容对象。"""

from __future__ import annotations

import json
import importlib.abc
import importlib.util
import logging
import sys
import types
from dataclasses import dataclass
from enum import Enum
from typing import Any


@dataclass
class Runtime:
    """保存一次调用可见的凭据与上下文。"""

    credentials: dict[str, Any]
    context: dict[str, Any]

    @property
    def user_id(self) -> str:
        """兼容插件从运行时直接读取当前用户标识。"""
        return str(self.context.get("userId") or self.context.get("workflowOwnerId") or "")


@dataclass
class ToolInvokeMessage:
    """表示插件产生的一条规范化消息。"""

    type: str
    value: Any

    @property
    def message(self) -> Any:
        """兼容读取历史 SDK 的 message 属性。"""
        return self.value


class StringEnum(str, Enum):
    """提供与 Dify/Pydantic 字符串枚举相近的比较和序列化语义。"""

    @classmethod
    def value_of(cls, value: Any) -> "StringEnum":
        """按字符串值查找枚举，兼容模型插件的动态模式选择。"""
        return cls(str(value))


class PromptMessageRole(StringEnum):
    """模型消息角色。"""

    SYSTEM = "system"
    DEVELOPER = "developer"
    USER = "user"
    ASSISTANT = "assistant"
    TOOL = "tool"


class PromptMessageContentType(StringEnum):
    """模型消息内容类型。"""

    TEXT = "text"
    IMAGE = "image"
    AUDIO = "audio"
    VIDEO = "video"
    DOCUMENT = "document"


class ModelType(StringEnum):
    """Dify 常用模型类型。"""

    LLM = "llm"
    TEXT_EMBEDDING = "text-embedding"
    RERANK = "rerank"
    SPEECH2TEXT = "speech2text"
    MODERATION = "moderation"
    TTS = "tts"


class LLMMode(StringEnum):
    """Dify LLM 的对话与续写模式。"""

    CHAT = "chat"
    COMPLETION = "completion"


class FetchFrom(StringEnum):
    """模型声明的预定义与用户自定义来源。"""

    PREDEFINED_MODEL = "predefined-model"
    CUSTOMIZABLE_MODEL = "customizable-model"


class DefaultParameterName(StringEnum):
    """Dify 模型通用采样参数名称。"""

    TEMPERATURE = "temperature"
    TOP_P = "top_p"
    MAX_TOKENS = "max_tokens"
    PRESENCE_PENALTY = "presence_penalty"
    FREQUENCY_PENALTY = "frequency_penalty"


class PriceType(StringEnum):
    """模型计费规则的输入与输出方向。"""

    INPUT = "input"
    OUTPUT = "output"


PARAMETER_RULE_TEMPLATE = {name: {} for name in DefaultParameterName}
"""保留通用参数模板键；具体模型插件可在空安全基线之上覆盖约束。"""


class ModelFeature(StringEnum):
    """真实模型插件在导入阶段读取的能力枚举。"""

    TOOL_CALL = "tool-call"
    MULTI_TOOL_CALL = "multi-tool-call"
    AGENT_THOUGHT = "agent-thought"
    VISION = "vision"
    STREAM_TOOL_CALL = "stream-tool-call"
    DOCUMENT = "document"
    VIDEO = "video"
    AUDIO = "audio"
    STRUCTURED_OUTPUT = "structured-output"


class ModelPropertyKey(StringEnum):
    """模型实体 properties 中使用的稳定字段键。"""

    MODE = "mode"
    CONTEXT_SIZE = "context_size"
    MAX_CHUNKS = "max_chunks"
    FILE_UPLOAD_LIMIT = "file_upload_limit"
    SUPPORTED_FILE_EXTENSIONS = "supported_file_extensions"
    AUDIO_TYPE = "audio_type"
    MAX_WORKERS = "max_workers"
    DEFAULT_VOICE = "default_voice"
    VOICES = "voices"
    WORD_LIMIT = "word_limit"


class EmbeddingInputType(StringEnum):
    """向量模型输入用途。"""

    DOCUMENT = "document"
    QUERY = "query"


class PromptMessage:
    """保存插件可读取的基础消息字段。"""

    role = PromptMessageRole.USER

    def __init__(self, content: Any = "", **kwargs: Any) -> None:
        """接受 Dify 消息实体的宽松关键字参数。"""
        self.content = content
        self.name = kwargs.pop("name", None)
        for key, value in kwargs.items():
            setattr(self, key, value)


class SystemPromptMessage(PromptMessage):
    """系统消息。"""
    role = PromptMessageRole.SYSTEM


class DeveloperPromptMessage(PromptMessage):
    """开发者消息。"""
    role = PromptMessageRole.DEVELOPER


class UserPromptMessage(PromptMessage):
    """用户消息。"""
    role = PromptMessageRole.USER


class AssistantPromptMessage(PromptMessage):
    """助手消息。"""
    role = PromptMessageRole.ASSISTANT

    class ToolCall:
        """保存助手工具调用。"""
        class ToolCallFunction:
            """保存工具函数名与参数。"""
            def __init__(self, **kwargs: Any) -> None:
                self.__dict__.update(kwargs)

        def __init__(self, **kwargs: Any) -> None:
            self.__dict__.update(kwargs)


class ToolPromptMessage(PromptMessage):
    """工具结果消息。"""
    role = PromptMessageRole.TOOL


class PromptMessageTool:
    """保存模型工具声明。"""
    def __init__(self, **kwargs: Any) -> None:
        self.__dict__.update(kwargs)


class PromptMessageContent:
    """保存多模态内容。"""
    type = PromptMessageContentType.TEXT

    def __init__(self, data: Any = None, **kwargs: Any) -> None:
        self.data = data
        self.__dict__.update(kwargs)


class TextPromptMessageContent(PromptMessageContent):
    """文本内容。"""
    type = PromptMessageContentType.TEXT


class ImagePromptMessageContent(PromptMessageContent):
    """图片内容。"""
    type = PromptMessageContentType.IMAGE


class AudioPromptMessageContent(PromptMessageContent):
    """音频内容。"""
    type = PromptMessageContentType.AUDIO


class VideoPromptMessageContent(PromptMessageContent):
    """视频内容。"""
    type = PromptMessageContentType.VIDEO


class DocumentPromptMessageContent(PromptMessageContent):
    """文档内容。"""
    type = PromptMessageContentType.DOCUMENT


def prompt_message(value: dict[str, Any]) -> PromptMessage:
    """把公开 JSON 消息转换为对应 Dify 消息实体。"""
    role = str(value.get("role", "user")).lower()
    message_type = {"system": SystemPromptMessage, "developer": DeveloperPromptMessage,
                    "assistant": AssistantPromptMessage, "tool": ToolPromptMessage}.get(role, UserPromptMessage)
    extra = {key: item for key, item in value.items() if key not in {"role", "content"}}
    return message_type(content=value.get("content", ""), **extra)


class PluginComponent:
    """为各类插件组件提供统一运行时和消息工厂。"""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        """接受宽松构造参数，并保留调用器在构造前注入的受控运行时。"""
        if not isinstance(getattr(self, "runtime", None), Runtime):
            self.runtime = Runtime({}, {})

    @classmethod
    def from_credentials(cls, credentials: dict[str, Any], user_id: str = "") -> "PluginComponent":
        """按插件常用工厂约定创建带凭据的组件。"""
        instance = cls()
        instance.runtime = Runtime(credentials or {}, {"user_id": user_id})
        return instance

    def create_text_message(self, text: str = "", **kwargs: Any) -> ToolInvokeMessage:
        """创建文本输出消息。"""
        return ToolInvokeMessage("text", text if text != "" else kwargs.get("text", ""))

    def create_json_message(self, json: Any = None, **kwargs: Any) -> ToolInvokeMessage:
        """创建 JSON 输出消息。"""
        return ToolInvokeMessage("json", json if json is not None else kwargs.get("json"))

    def create_blob_message(self, blob: bytes = b"", meta: dict[str, Any] | None = None,
                            **kwargs: Any) -> ToolInvokeMessage:
        """创建二进制输出消息并以十六进制安全传输。"""
        value = blob if blob else kwargs.get("blob", b"")
        return ToolInvokeMessage("blob", {"hex": bytes(value).hex(), "meta": meta or kwargs.get("meta", {})})

    def create_link_message(self, link: str = "", **kwargs: Any) -> ToolInvokeMessage:
        """创建链接输出消息。"""
        return ToolInvokeMessage("link", link if link else kwargs.get("link", ""))

    def create_variable_message(self, variable_name: str = "", variable_value: Any = None,
                                **kwargs: Any) -> ToolInvokeMessage:
        """创建变量输出消息。"""
        return ToolInvokeMessage("variable", {
            "name": variable_name or kwargs.get("variable_name", ""),
            "value": variable_value if variable_value is not None else kwargs.get("variable_value"),
        })


class Tool(PluginComponent):
    """兼容 Dify Tool 插件基类。"""


class ToolProvider(PluginComponent):
    """兼容 Dify ToolProvider 插件基类。"""


class ModelProvider(PluginComponent):
    """兼容 Dify ModelProvider 插件基类。"""


class LargeLanguageModel(PluginComponent):
    """兼容 Dify LLM 模型基类。"""


class TextEmbeddingModel(PluginComponent):
    """兼容 Dify向量模型基类。"""


class Speech2TextModel(PluginComponent):
    """兼容 Dify 语音转文字模型基类。"""


class ModerationModel(PluginComponent):
    """兼容 Dify审核模型基类。"""


class TTSModel(PluginComponent):
    """兼容 Dify 文本转语音模型基类。"""


class RerankModel(PluginComponent):
    """兼容 Dify 重排模型基类。"""


class AgentStrategy(PluginComponent):
    """兼容 Dify AgentStrategy 插件基类。"""


class Datasource(PluginComponent):
    """兼容 Dify Datasource 插件基类。"""


class Trigger(PluginComponent):
    """兼容 Dify Trigger 插件基类。"""


class Endpoint(PluginComponent):
    """兼容 Dify Extension/Endpoint 插件基类。"""


class Placeholder(PluginComponent):
    """承接只用于类型标注或非核心返回对象的 SDK 符号。"""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        """保存宽松构造参数，避免类型对象在导入阶段失败。"""
        super().__init__()
        self.args = args
        self.kwargs = kwargs
        for key, value in kwargs.items():
            setattr(self, key, value)

    @classmethod
    def __get_pydantic_core_schema__(cls, source_type: Any, handler: Any) -> Any:
        """向插件自带的 Pydantic 暴露仅构造普通 ABI 实体的安全 Schema。"""
        from pydantic_core import core_schema
        return core_schema.no_info_plain_validator_function(cls._pydantic_value)

    @classmethod
    def _pydantic_value(cls, value: Any) -> Any:
        """把 JSON 对象转换为受控 ABI 实体，其余标量保持原值供枚举式类型使用。"""
        if isinstance(value, cls):
            return value
        if isinstance(value, dict):
            return cls(**value)
        return value

    def model_dump(self, *args: Any, **kwargs: Any) -> dict[str, Any]:
        """兼容插件实体常用的 Pydantic 序列化入口。"""
        return {key: value for key, value in vars(self).items() if key not in {"runtime", "args", "kwargs"}}

    def to_dict(self) -> dict[str, Any]:
        """兼容 Agent 动作和模型实体的字典转换入口。"""
        return self.model_dump()


class AgentScratchpadUnit(Placeholder):
    """保存 ReAct Agent 的思考、动作与观察状态。"""

    class Action(Placeholder):
        """保存 Agent 选择的动作名和公开输入。"""


class AgentModelConfig(Placeholder):
    """承接 Agent 参数中的模型配置并允许 Pydantic 安全建模。"""


class AIModelEntity(Placeholder):
    """承接模型能力元数据并允许 Pydantic 安全建模。"""


class Plugin:
    """阻止插件自行启动 Dify 运行循环。"""

    def __init__(self, *args: Any, **kwargs: Any) -> None:
        """接受插件入口构造参数但不启动第三方引擎。"""
        self.args = args

    def run(self) -> None:
        """拒绝启动原 Dify 插件事件循环。"""
        raise RuntimeError("Base AI 不允许插件启动 Dify 运行引擎")


class DifyPluginEnv(Placeholder):
    """保存入口代码可能构造的非敏感环境选项。"""


class DynamicModule(types.ModuleType):
    """为未影响执行语义的 SDK 类型提供惰性占位符。"""

    def __getattr__(self, name: str) -> Any:
        """返回已知 ABI 对象或安全占位类型。"""
        known = ABI_EXPORTS.get(name)
        if known is not None:
            return known
        value = type(name, (Placeholder,), {})
        setattr(self, name, value)
        return value


class DifyModuleFinder(importlib.abc.MetaPathFinder, importlib.abc.Loader):
    """为插件导入的任意 dify_plugin 子模块提供受控惰性 ABI 模块。"""

    def find_spec(self, fullname: str, path: Any = None, target: Any = None) -> Any:
        """仅接管被禁止安装的 dify_plugin 命名空间。"""
        if fullname.startswith("dify_plugin.") and fullname not in sys.modules:
            return importlib.util.spec_from_loader(fullname, self, is_package=True)
        return None

    def create_module(self, spec: Any) -> DynamicModule:
        """创建具备惰性符号解析的模块。"""
        return DynamicModule(spec.name)

    def exec_module(self, module: DynamicModule) -> None:
        """标记模块为包，使更深层子模块仍可被解析。"""
        module.__package__ = module.__name__
        module.__path__ = []


ABI_EXPORTS = {
    "Plugin": Plugin,
    "DifyPluginEnv": DifyPluginEnv,
    "Tool": Tool,
    "ToolProvider": ToolProvider,
    "ModelProvider": ModelProvider,
    "LargeLanguageModel": LargeLanguageModel,
    "TextEmbeddingModel": TextEmbeddingModel,
    "Speech2TextModel": Speech2TextModel,
    "ModerationModel": ModerationModel,
    "TTSModel": TTSModel,
    "RerankModel": RerankModel,
    "ModelType": ModelType,
    "FetchFrom": FetchFrom,
    "DefaultParameterName": DefaultParameterName,
    "PriceType": PriceType,
    "PARAMETER_RULE_TEMPLATE": PARAMETER_RULE_TEMPLATE,
    "ModelFeature": ModelFeature,
    "ModelPropertyKey": ModelPropertyKey,
    "EmbeddingInputType": EmbeddingInputType,
    "AIModelEntity": AIModelEntity,
    "LLMMode": LLMMode,
    "PromptMessage": PromptMessage,
    "PromptMessageRole": PromptMessageRole,
    "PromptMessageContentType": PromptMessageContentType,
    "PromptMessageTool": PromptMessageTool,
    "SystemPromptMessage": SystemPromptMessage,
    "DeveloperPromptMessage": DeveloperPromptMessage,
    "UserPromptMessage": UserPromptMessage,
    "AssistantPromptMessage": AssistantPromptMessage,
    "ToolPromptMessage": ToolPromptMessage,
    "TextPromptMessageContent": TextPromptMessageContent,
    "ImagePromptMessageContent": ImagePromptMessageContent,
    "AudioPromptMessageContent": AudioPromptMessageContent,
    "VideoPromptMessageContent": VideoPromptMessageContent,
    "DocumentPromptMessageContent": DocumentPromptMessageContent,
    "AgentStrategy": AgentStrategy,
    "AgentModelConfig": AgentModelConfig,
    "AgentScratchpadUnit": AgentScratchpadUnit,
    "Datasource": Datasource,
    "Trigger": Trigger,
    "Endpoint": Endpoint,
    "ToolInvokeMessage": ToolInvokeMessage,
    "plugin_logger_handler": logging.NullHandler(),
}


def install_modules() -> None:
    """向当前子进程注册自研 dify_plugin 模块树。"""
    if not any(isinstance(finder, DifyModuleFinder) for finder in sys.meta_path):
        sys.meta_path.insert(0, DifyModuleFinder())
    paths = [
        "dify_plugin", "dify_plugin.entities", "dify_plugin.entities.tool", "dify_plugin.entities.model",
        "dify_plugin.entities.model.llm", "dify_plugin.entities.model.text_embedding",
        "dify_plugin.entities.model.rerank", "dify_plugin.entities.model.tts",
        "dify_plugin.entities.model.speech2text", "dify_plugin.entities.model.moderation",
        "dify_plugin.errors", "dify_plugin.errors.tool", "dify_plugin.errors.model",
        "dify_plugin.interfaces", "dify_plugin.interfaces.tool", "dify_plugin.interfaces.model",
        "dify_plugin.interfaces.agent", "dify_plugin.interfaces.datasource", "dify_plugin.interfaces.trigger",
    ]
    for path in paths:
        if path not in sys.modules:
            module = DynamicModule(path)
            module.__package__ = path
            module.__path__ = []
            sys.modules[path] = module
    root = sys.modules["dify_plugin"]
    for name, value in ABI_EXPORTS.items():
        setattr(root, name, value)


def normalize_output(value: Any) -> Any:
    """把插件返回值转换为可序列化且不泄露对象实现的结构。"""
    if isinstance(value, ToolInvokeMessage):
        return {"type": value.type, "value": normalize_output(value.value)}
    if isinstance(value, bytes):
        return {"type": "blob", "hex": value.hex()}
    if isinstance(value, dict):
        return {str(key): normalize_output(item) for key, item in value.items()}
    if isinstance(value, (list, tuple, set)):
        return [normalize_output(item) for item in value]
    if hasattr(value, "model_dump"):
        return normalize_output(value.model_dump())
    if hasattr(value, "__dict__") and not isinstance(value, type):
        return normalize_output(vars(value))
    try:
        json.dumps(value)
        return value
    except TypeError:
        return str(value)
