"""验证自研 Dify ABI 宿主的声明解析和安全边界。"""

import base64
import io
import os
import tempfile
import unittest
import zipfile
import json
import subprocess
import sys
from unittest.mock import patch
from pathlib import Path

from app.abi import Runtime, Tool, normalize_output
from app.package_store import HOST_ABI_VERSION, PackageError, PackageStore


def archive(entries: dict[str, str]) -> bytes:
    """构造仅供正式测试使用的内存插件包。"""
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w") as package:
        for name, content in entries.items():
            package.writestr(name, content)
    return output.getvalue()


class PackageStoreTest(unittest.TestCase):
    """覆盖全部声明类型和恶意压缩包。"""

    def setUp(self) -> None:
        """为每个用例创建隔离临时包目录。"""
        self.temporary = tempfile.TemporaryDirectory()
        os.environ["PLUGIN_PACKAGE_ROOT"] = self.temporary.name
        self.store = PackageStore()

    def tearDown(self) -> None:
        """清理测试产生的插件包。"""
        self.temporary.cleanup()

    def test_inspects_all_component_types_without_dify_sdk(self) -> None:
        """全部 Dify 插件类型都应转换为统一组件 Schema。"""
        plugins = {
            "tools": "provider/tool.yaml", "models": "provider/model.yaml",
            "agent_strategies": "provider/agent.yaml", "datasources": "provider/data.yaml",
            "triggers": "provider/trigger.yaml", "endpoints": "provider/endpoint.yaml",
        }
        entries = {"manifest.yaml": "plugins:\n" + "".join(f"  {key}: [{value}]\n" for key, value in plugins.items())}
        for index, (key, reference) in enumerate(plugins.items()):
            entries[reference] = f"identity:\n  name: {key}\n  label:\n    en_US: {key}\nextra:\n  python:\n    source: components/c{index}.py\nparameters:\n  - name: value\n    type: string\n    required: true\n"
            method = "_subscribe" if key == "triggers" else "_get_schema" if key == "endpoints" else "_invoke"
            entries[f"components/c{index}.py"] = (
                "from dify_plugin import Tool\nclass Component(Tool):\n"
                f"    def {method}(self, parameters=None, tool_parameters=None, **kwargs):\n"
                "        return {'ok': True}\n")
        raw = archive(entries)
        result = self.store.install({"packageId": "fixture/all", "version": "1", "archiveBase64": base64.b64encode(raw).decode()})
        self.assertEqual({"TOOL", "MODEL", "AGENT_STRATEGY", "DATASOURCE", "TRIGGER", "EXTENSION"},
                         {item["componentType"] for item in result["components"]})
        self.assertTrue(all(item["compatibilityStatus"] == "SUPPORTED" for item in result["components"]))
        root, _ = self.store.metadata(result["fingerprint"])
        for item in result["components"]:
            operation = "subscribe" if item["componentType"] == "TRIGGER" else "schema" if item["componentType"] == "EXTENSION" else "invoke"
            payload = {"root": str(root), "sourcePath": item["sourcePath"], "operation": operation,
                       "parameters": {}, "credentials": {}, "context": {}}
            invoked = subprocess.run([sys.executable, "-m", "app.invoke_child"], input=json.dumps(payload),
                                     text=True, capture_output=True, check=False)
            self.assertEqual(0, invoked.returncode, invoked.stdout)
            self.assertTrue(json.loads(invoked.stdout)["success"])

    def test_extracts_declared_admission_metadata_without_scanning_code(self) -> None:
        """仅从清单和 YAML 固定地址提取准入候选，不解析 Python 动态网络目标。"""
        raw = archive({
            "manifest.yaml": "license: {type: Apache-2.0, url: https://licenses.example.com/apache}\nplugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity: {name: fixture}\nendpoint: https://api.example.com/v1\nextra:\n  python:\n    source: tool.py\n",
            "tool.py": "from dify_plugin import Tool\nDYNAMIC='https://code.example.net'\nclass T(Tool):\n    def _invoke(self, tool_parameters): return {}\n",
        })
        result = self.store.install({"packageId": "fixture", "version": "1",
                                     "archiveBase64": base64.b64encode(raw).decode()})
        self.assertEqual("Apache-2.0", result["licenseName"])
        self.assertEqual("https://licenses.example.com/apache", result["licenseUrl"])
        self.assertEqual([{"name": "api.example.com", "domain": "api.example.com"}], result["externalServices"])

    def test_preserves_component_and_field_localizations(self) -> None:
        """组件、参数和凭据必须保留双语声明，并为缺失语言提供稳定回退。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": """
identity: {name: calendar}
credentials_for_provider:
  api_key:
    label: {zh_Hans: 密钥, en_US: API Key}
    human_description: {en_US: Provider credential}
    type: secret-input
    required: true
tools: [action.yaml]
""",
            "action.yaml": """
identity:
  name: add_attendees
  label: {zh_Hans: 添加日程参会人, en_US: Add Event Attendees}
description:
  human: {zh_Hans: 添加参会人, en_US: Add attendees to an event}
parameters:
  - name: event_id
    label: {zh_Hans: 日程 ID, en_US: Event ID}
    human_description: {zh_Hans: 目标日程}
    type: string
    required: true
  - name: mode
    label: {zh_Hans: 模式, en_US: Mode}
    type: select
    options:
      - value: basic
        label: {zh_Hans: 基础, en_US: Basic}
extra:
  python:
    source: action.py
""",
            "action.py": "from dify_plugin import Tool\nclass Action(Tool):\n    def _invoke(self, tool_parameters): return {}\n",
        })

        result = self.store.install({"packageId": "fixture/calendar", "version": "1",
                                     "archiveBase64": base64.b64encode(raw).decode()})

        self.assertEqual(6, result["hostAbiVersion"])
        component = result["components"][0]
        self.assertEqual("Add Event Attendees", component["localization"]["name"]["en-US"])
        self.assertEqual("添加日程参会人", component["localization"]["name"]["zh-CN"])
        self.assertEqual("目标日程", component["schema"][0]["localization"]["description"]["en-US"])
        self.assertEqual("Basic", component["schema"][1]["options"][0]["localization"]["label"]["en-US"])
        self.assertEqual("API Key", component["credentialSchema"][0]["localization"]["label"]["en-US"])

    def test_discovers_and_invokes_declared_model_source(self) -> None:
        """模型 Provider 必须使用 model_sources，而不是把凭据 Provider 误当成模型实现。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  models: [provider/model.yaml]\n",
            "provider/model.yaml": """
provider: fixture
label:
  en_US: Fixture Models
supported_model_types: [llm]
provider_credential_schema:
  credential_form_schemas:
    - variable: api_key
      label: {en_US: API Key}
      type: secret-input
      required: true
models:
  llm:
    predefined: [models/llm/*.yaml]
extra:
  python:
    provider_source: provider/model.py
    model_sources: [models/llm/llm.py]
""",
            "provider/model.py": "from dify_plugin import ModelProvider\nclass Provider(ModelProvider):\n    def validate_provider_credentials(self, credentials): return None\n",
            "models/llm/fixture.yaml": "model: fixture-chat\nlabel: {en_US: Fixture Chat}\nmodel_type: llm\n",
            "models/llm/llm.py": """
from dify_plugin.interfaces.model.large_language_model import LargeLanguageModel
class FixtureLlm(LargeLanguageModel):
    def _invoke(self, model, credentials, prompt_messages, model_parameters, tools=None,
                stop=None, stream=True, user=None):
        return {'model': model, 'api_key': credentials.get('api_key'),
                'messages': [{'role': item.role.value, 'content': item.content} for item in prompt_messages],
                'temperature': model_parameters.get('temperature'), 'stream': stream, 'user': user}
""",
        })

        result = self.store.install({"packageId": "fixture/model", "version": "1",
                                     "archiveBase64": base64.b64encode(raw).decode()})

        self.assertEqual(HOST_ABI_VERSION, result["hostAbiVersion"])
        self.assertEqual(1, len(result["components"]))
        item = result["components"][0]
        self.assertEqual("SUPPORTED", item["compatibilityStatus"])
        self.assertEqual("models/llm/llm.py", item["sourcePath"])
        self.assertEqual("llm", item["modelType"])
        root, _ = self.store.metadata(result["fingerprint"])
        payload = {
            "root": str(root), "sourcePath": item["sourcePath"], "componentType": "MODEL",
            "modelType": item["modelType"], "operation": "invoke",
            "parameters": {"model": "fixture-chat", "model_parameters": {"temperature": 0.2}},
            "credentials": {"api_key": "secret"},
            "input": {"messages": [{"role": "system", "content": "rules"},
                                     {"role": "user", "content": "hello"}]},
            "context": {"userId": "user-1"},
        }
        invoked = subprocess.run([sys.executable, "-m", "app.invoke_child"], input=json.dumps(payload),
                                 text=True, capture_output=True, check=False)
        self.assertEqual(0, invoked.returncode, invoked.stdout)
        output = json.loads(invoked.stdout)["output"]
        self.assertEqual("fixture-chat", output["model"])
        self.assertEqual(["system", "user"], [message["role"] for message in output["messages"]])
        self.assertFalse(output["stream"])
        self.assertEqual("user-1", output["user"])

    def test_loads_volcengine_pydantic_model_contract(self) -> None:
        """火山方舟使用的枚举和价格对象必须能够参与 Pydantic 建模。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  models: [provider/model.yaml]\n",
            "provider/model.yaml": """
provider: volcengine_fixture
label: {en_US: Volcengine Fixture}
supported_model_types: [llm]
extra:
  python:
    model_sources: [models/llm/llm.py]
""",
            "models/llm/llm.py": """
from decimal import Decimal
from pydantic import BaseModel
from dify_plugin.entities.model import ModelFeature, PriceConfig
from dify_plugin.entities.model.llm import LLMMode
from dify_plugin.interfaces.model.large_language_model import LargeLanguageModel

class ModelProperties(BaseModel):
    mode: LLMMode

class ModelConfig(BaseModel):
    properties: ModelProperties
    features: list[ModelFeature]
    pricing: PriceConfig | None = None

CONFIG = ModelConfig(
    properties=ModelProperties(mode=LLMMode.CHAT),
    features=[ModelFeature.STRUCTURED_OUTPUT],
    pricing=PriceConfig(input=Decimal('0.0032'), output=Decimal('0.0160'),
                        unit=Decimal('0.001'), currency='RMB'),
)

class VolcengineFixtureLlm(LargeLanguageModel):
    def _invoke(self, model, credentials, prompt_messages, model_parameters, **kwargs):
        return {'mode': CONFIG.properties.mode.value, 'currency': CONFIG.pricing.currency,
                'features': [feature.value for feature in CONFIG.features]}
""",
        })

        result = self.store.install({"packageId": "fixture/volcengine", "version": "1",
                                     "archiveBase64": base64.b64encode(raw).decode()})

        item = result["components"][0]
        self.assertEqual("SUPPORTED", item["compatibilityStatus"], item["compatibilityReason"])

    def test_reports_file_count_limit_separately_from_unpacked_size(self) -> None:
        """文件条目超限必须返回独立原因，不能误报为解压体积超限。"""
        self.store.maximum_files = 2
        raw = archive({"manifest.yaml": "plugins: {}\n", "first.txt": "1", "second.txt": "2"})

        with self.assertRaisesRegex(PackageError, "ARCHIVE_FILE_LIMIT"):
            self.store.install({"archiveBase64": base64.b64encode(raw).decode()})

    def test_loads_pydantic_agent_and_model_entities(self) -> None:
        """真实 Agent 与模型插件使用的 Pydantic 类型和嵌套动作必须能够安全建模。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  agent_strategies: [provider.yaml]\n",
            "provider.yaml": """
identity:
  name: agent_contract
strategies: [strategy.yaml]
""",
            "strategy.yaml": """
identity:
  name: agent_contract
extra:
  python:
    source: strategy.py
""",
            "strategy.py": """
from dify_plugin.entities.model import (PARAMETER_RULE_TEMPLATE, AIModelEntity, DefaultParameterName, FetchFrom,
                                        ModelFeature, ModelPropertyKey, PriceType)
from dify_plugin.entities.model.llm import LLMMode
from dify_plugin.entities.tool import ToolInvokeMessage
from dify_plugin.interfaces.agent import AgentModelConfig, AgentScratchpadUnit, AgentStrategy
from pydantic import BaseModel

class Contract(BaseModel):
    model: AgentModelConfig
    entity: AIModelEntity
    mode: LLMMode

class ContractAgent(AgentStrategy):
    action_type = AgentScratchpadUnit.Action
    fetch_from = FetchFrom.CUSTOMIZABLE_MODEL
    llm_mode = LLMMode.CHAT
    mode_key = ModelPropertyKey.MODE
    structured_output = ModelFeature.STRUCTURED_OUTPUT
    temperature = DefaultParameterName.TEMPERATURE
    price_type = PriceType.INPUT
    temperature_rule = {**PARAMETER_RULE_TEMPLATE[DefaultParameterName.TEMPERATURE]}
    log_status = ToolInvokeMessage.LogMessage.LogStatus.START
    def _invoke(self, parameters):
        action = self.action_type(action_name='finish', action_input={'answer': 'ok'})
        scratchpad = AgentScratchpadUnit(action=action, thought='done')
        return {'action': action.to_dict(), 'thought': scratchpad.thought}
""",
        })

        result = self.store.install({"packageId": "fixture/agent-contract", "version": "1",
                                     "archiveBase64": base64.b64encode(raw).decode()})

        item = result["components"][0]
        self.assertEqual("SUPPORTED", item["compatibilityStatus"], item["compatibilityReason"])
        root, _ = self.store.metadata(result["fingerprint"])
        invoked = subprocess.run([sys.executable, "-m", "app.invoke_child"], input=json.dumps({
            "root": str(root), "sourcePath": item["sourcePath"], "componentType": "AGENT_STRATEGY",
            "operation": "invoke", "parameters": {}, "credentials": {}, "input": {}, "context": {},
        }), text=True, capture_output=True, check=False)
        self.assertEqual(0, invoked.returncode, invoked.stdout)
        output = json.loads(invoked.stdout)["output"]
        self.assertEqual("finish", output["action"]["action_name"])
        self.assertEqual("done", output["thought"])

    def test_initializes_component_with_invocation_credentials(self) -> None:
        """探测不应使用空凭据执行构造器，真实调用必须在构造前注入已校验凭据。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity:\n  name: credential_init\nextra:\n  python:\n    source: tool.py\n",
            "tool.py": """
import logging
from dify_plugin import Tool
from dify_plugin.config.logger_format import plugin_logger_handler
class CredentialTool(Tool):
    def __init__(self):
        super().__init__()
        self.logger = logging.getLogger(__name__)
        self.logger.addHandler(plugin_logger_handler)
        self.endpoint = self.runtime.credentials.get('endpoint').rstrip('/')
    def _invoke(self, tool_parameters):
        return {'endpoint': self.endpoint, 'value': tool_parameters.get('value')}
""",
        })

        result = self.store.install({"packageId": "fixture/credential-init", "version": "1",
                                     "archiveBase64": base64.b64encode(raw).decode()})

        item = result["components"][0]
        self.assertEqual("SUPPORTED", item["compatibilityStatus"], item["compatibilityReason"])
        root, _ = self.store.metadata(result["fingerprint"])
        invoked = subprocess.run([sys.executable, "-m", "app.invoke_child"], input=json.dumps({
            "root": str(root), "sourcePath": item["sourcePath"], "componentType": "TOOL",
            "operation": "invoke", "parameters": {"value": "ok"},
            "credentials": {"endpoint": "https://api.example.com/"}, "input": {}, "context": {},
        }), text=True, capture_output=True, check=False)
        self.assertEqual(0, invoked.returncode, invoked.stdout)
        self.assertEqual({"endpoint": "https://api.example.com", "value": "ok"},
                         json.loads(invoked.stdout)["output"])

    def test_marks_component_partial_when_source_cannot_load(self) -> None:
        """组件依赖缺失时必须通过真实导入探测暴露为部分兼容。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity:\n  name: broken\nextra:\n  python:\n    source: broken.py\n",
            "broken.py": "import dependency_that_does_not_exist\nfrom dify_plugin import Tool\nclass Broken(Tool):\n    pass\n",
        })
        result = self.store.install({"archiveBase64": base64.b64encode(raw).decode()})
        self.assertEqual("PARTIAL", result["components"][0]["compatibilityStatus"])
        self.assertIn("dependency_that_does_not_exist", result["components"][0]["compatibilityReason"])

    def test_filters_dify_sdk_and_rejects_external_dependency_sources(self) -> None:
        """依赖清单不得安装 Dify SDK，也不得绕过 PyPI 使用任意代码源。"""
        self.assertEqual(["requests==2.32.5"], self.store._safe_requirements(
            "dify_plugin~=0.6.0\nrequests==2.32.5\n"))
        with self.assertRaisesRegex(PackageError, "DEPENDENCY_SOURCE_FORBIDDEN"):
            self.store._safe_requirements("sample @ https://example.com/sample.whl\n")

    def test_installs_dependencies_with_persistent_download_cache(self) -> None:
        """依赖安装应复用持久下载缓存，同时把临时文件限制在插件持久化目录。"""
        root = Path(self.temporary.name) / "package"
        root.mkdir()
        (root / "requirements.txt").write_text("requests==2.32.5\n", encoding="utf-8")
        completed = subprocess.CompletedProcess([], 0, "", "")

        with patch("app.package_store.subprocess.run", return_value=completed) as run:
            self.assertEqual("", self.store._install_dependencies(root))

        command = run.call_args.args[0]
        environment = run.call_args.kwargs["env"]
        self.assertNotIn("--no-cache-dir", command)
        self.assertEqual((Path(self.temporary.name) / ".pip-cache").resolve(),
                         Path(environment["PIP_CACHE_DIR"]).resolve())
        self.assertTrue((Path(self.temporary.name) / ".pip-cache").is_dir())
        self.assertTrue(Path(environment["TMPDIR"]).is_relative_to(root))
        self.assertFalse(Path(environment["TMPDIR"]).exists())

    def test_rejects_pip_cache_outside_package_volume(self) -> None:
        """pip 缓存不得借配置逃逸到插件持久化目录以外。"""
        os.environ["PLUGIN_PIP_CACHE_DIR"] = str(Path(self.temporary.name).parent / "outside-cache")
        try:
            with self.assertRaisesRegex(RuntimeError, "PLUGIN_PIP_CACHE_DIR"):
                PackageStore()
        finally:
            os.environ.pop("PLUGIN_PIP_CACHE_DIR", None)

    def test_reads_current_provider_credential_schema(self) -> None:
        """当前 Dify Provider 凭据声明中的 variable 必须成为动态连接字段名。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": """
identity:
  name: provider
provider_credential_schema:
  credential_form_schemas:
    - variable: api_key
      label:
        en_US: API Key
      type: secret-input
      required: true
extra:
  python:
    source: tool.py
""",
            "tool.py": "from dify_plugin import Tool\nclass ToolImpl(Tool):\n    def _invoke(self, tool_parameters):\n        return {}\n",
        })
        result = self.store.install({"archiveBase64": base64.b64encode(raw).decode()})
        self.assertEqual("api_key", result["components"][0]["credentialSchema"][0]["name"])
        self.assertTrue(result["components"][0]["credentialSchema"][0]["secret"])

    def test_invokes_extension_oauth_lifecycle_without_dify_sdk(self) -> None:
        """扩展组件应通过自研 ABI 接收 state、PKCE 和授权码。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  endpoints: [extension.yaml]\n",
            "extension.yaml": "identity:\n  name: oauth\nextra:\n  python:\n    source: oauth.py\n",
            "oauth.py": """
from dify_plugin import Endpoint
class OAuth(Endpoint):
    def _get_authorization_url(self, redirect_uri, state, code_verifier):
        return {'authorizationUrl': 'https://accounts.example.com/auth?state=' + state}
    def _get_credentials(self, code, code_verifier):
        return {'credentials': {'accessToken': code + ':' + code_verifier}}
""",
        })
        result = self.store.install({"archiveBase64": base64.b64encode(raw).decode()})
        item = result["components"][0]
        root, _ = self.store.metadata(result["fingerprint"])
        for operation in ("oauth_authorize", "oauth_exchange"):
            payload = {"root": str(root), "sourcePath": item["sourcePath"], "operation": operation,
                       "parameters": {}, "credentials": {}, "context": {}, "redirectUri": "https://base.test/cb",
                       "state": "state", "codeVerifier": "verifier", "code": "code"}
            invoked = subprocess.run([sys.executable, "-m", "app.invoke_child"], input=json.dumps(payload),
                                     text=True, capture_output=True, check=False)
            self.assertEqual(0, invoked.returncode, invoked.stdout)
            self.assertTrue(json.loads(invoked.stdout)["success"])

    def test_rejects_path_traversal_and_fingerprint_mismatch(self) -> None:
        """路径穿越和摘要不匹配必须在落盘前失败。"""
        malicious = archive({"../escape": "x", "manifest.yaml": "plugins: {}"})
        with self.assertRaisesRegex(PackageError, "ARCHIVE_PATH_INVALID"):
            self.store.install({"archiveBase64": base64.b64encode(malicious).decode()})
        valid = archive({"manifest.yaml": "plugins: {}"})
        with self.assertRaisesRegex(PackageError, "ARCHIVE_FINGERPRINT_MISMATCH"):
            self.store.install({"archiveBase64": base64.b64encode(valid).decode(), "fingerprint": "0" * 64})

    def test_normalizes_tool_messages(self) -> None:
        """自研 Tool ABI 应稳定输出文本、JSON 和二进制消息。"""
        tool = Tool()
        tool.runtime = Runtime({"token": "secret"}, {})
        result = normalize_output([tool.create_text_message("ok"), tool.create_json_message({"count": 1}),
                                   tool.create_blob_message(b"x")])
        self.assertEqual("ok", result[0]["value"])
        self.assertEqual(1, result[1]["value"]["count"])
        self.assertEqual("78", result[2]["value"]["hex"])

    def test_supports_unlisted_dify_sdk_import_paths_with_local_abi(self) -> None:
        """插件新增的 SDK 子模块路径应由本地惰性 ABI 接管而非安装官方 SDK。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity:\n  name: nested\nextra:\n  python:\n    source: nested.py\n",
            "nested.py": "from dify_plugin.future.deep.module import FutureType\nfrom dify_plugin import Tool\nclass Nested(Tool):\n    def _invoke(self, tool_parameters):\n        return FutureType()\n",
        })
        result = self.store.install({"archiveBase64": base64.b64encode(raw).decode()})
        self.assertEqual("SUPPORTED", result["components"][0]["compatibilityStatus"])

    def test_loads_component_with_relative_package_import(self) -> None:
        """位于子目录的组件必须能够相对导入同一插件包内模块。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity:\n  name: relative\nextra:\n  python:\n    source: tools/tool.py\n",
            "tools/helper.py": "VALUE = 'ok'\n",
            "tools/tool.py": "from .helper import VALUE\nfrom dify_plugin import Tool\nclass ToolImpl(Tool):\n    def _invoke(self, tool_parameters):\n        return {'value': VALUE}\n",
        })

        result = self.store.install({"archiveBase64": base64.b64encode(raw).decode()})

        self.assertEqual("SUPPORTED", result["components"][0]["compatibilityStatus"])

    def test_removes_only_strict_fingerprint_cache_directory(self) -> None:
        """缓存清理不得接受路径、短摘要或其他目录名称。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity:\n  name: removable\nextra:\n  python:\n    source: tool.py\n",
            "tool.py": "from dify_plugin import Tool\nclass ToolImpl(Tool):\n    def _invoke(self, tool_parameters):\n        return {}\n",
        })
        result = self.store.install({"archiveBase64": base64.b64encode(raw).decode()})
        with self.assertRaisesRegex(PackageError, "PACKAGE_NOT_FOUND"):
            self.store.remove("../escape")
        self.assertEqual({"removed": True}, self.store.remove(result["fingerprint"]))
        with self.assertRaisesRegex(PackageError, "PACKAGE_NOT_FOUND"):
            self.store.metadata(result["fingerprint"])

    def test_retries_cached_dependency_install_failure(self) -> None:
        """相同包的后台重试必须重新安装临时失败的依赖。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity:\n  name: retry\nextra:\n  python:\n    source: tool.py\n",
            "tool.py": "from dify_plugin import Tool\nclass ToolImpl(Tool):\n    def _invoke(self, tool_parameters):\n        return {}\n",
            "requirements.txt": "requests==2.32.5\n",
        })
        request = {"archiveBase64": base64.b64encode(raw).decode()}
        with patch.object(self.store, "_install_dependencies",
                          side_effect=["DEPENDENCY_INSTALL_FAILED", ""]):
            first = self.store.install(request)
            second = self.store.install(request)
        self.assertEqual("PARTIAL", first["components"][0]["compatibilityStatus"])
        self.assertEqual("SUPPORTED", second["components"][0]["compatibilityStatus"])

    def test_reinspects_cache_from_previous_host_abi(self) -> None:
        """宿主 ABI 升级后必须重新探测旧缓存，不能永久保留过时兼容结论。"""
        raw = archive({
            "manifest.yaml": "plugins:\n  tools: [provider.yaml]\n",
            "provider.yaml": "identity:\n  name: cached\nextra:\n  python:\n    source: tool.py\n",
            "tool.py": "from dify_plugin import Tool\nclass ToolImpl(Tool):\n    def _invoke(self, tool_parameters):\n        return {}\n",
        })
        request = {"archiveBase64": base64.b64encode(raw).decode()}
        first = self.store.install(request)
        root, _ = self.store.metadata(first["fingerprint"])
        metadata_file = root / ".base-ai-metadata.json"
        old_metadata = json.loads(metadata_file.read_text(encoding="utf-8"))
        old_metadata.pop("hostAbiVersion")
        metadata_file.write_text(json.dumps(old_metadata), encoding="utf-8")

        with patch.object(self.store, "_metadata", wraps=self.store._metadata) as metadata:
            second = self.store.install(request)

        metadata.assert_called_once()
        self.assertEqual(HOST_ABI_VERSION, second["hostAbiVersion"])


if __name__ == "__main__":
    unittest.main()
