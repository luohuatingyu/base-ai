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
from app.package_store import PackageError, PackageStore


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

    def test_installs_dependencies_without_tmpfs_cache(self) -> None:
        """依赖安装必须禁用 pip 缓存，并把临时文件限制在插件持久化目录。"""
        root = Path(self.temporary.name) / "package"
        root.mkdir()
        (root / "requirements.txt").write_text("requests==2.32.5\n", encoding="utf-8")
        completed = subprocess.CompletedProcess([], 0, "", "")

        with patch("app.package_store.subprocess.run", return_value=completed) as run:
            self.assertEqual("", self.store._install_dependencies(root))

        command = run.call_args.args[0]
        environment = run.call_args.kwargs["env"]
        self.assertIn("--no-cache-dir", command)
        self.assertEqual("1", environment["PIP_NO_CACHE_DIR"])
        self.assertTrue(Path(environment["TMPDIR"]).is_relative_to(root))
        self.assertFalse(Path(environment["TMPDIR"]).exists())

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
        self.assertEqual(2, second["hostAbiVersion"])


if __name__ == "__main__":
    unittest.main()
