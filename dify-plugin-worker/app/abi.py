"""提供不依赖 Dify SDK 的最小插件 ABI 兼容对象。"""

from __future__ import annotations

import json
import importlib.abc
import importlib.util
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
        """接受第三方组件的宽松构造参数并初始化空运行时。"""
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
    "ModelFeature": ModelFeature,
    "EmbeddingInputType": EmbeddingInputType,
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
    "Datasource": Datasource,
    "Trigger": Trigger,
    "Endpoint": Endpoint,
    "ToolInvokeMessage": ToolInvokeMessage,
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
