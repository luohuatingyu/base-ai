"""验证自研 Dify ABI 宿主的声明解析和安全边界。"""

import base64
import io
import os
import tempfile
import unittest
import zipfile
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
            entries[f"components/c{index}.py"] = "class Component:\n    pass\n"
        raw = archive(entries)
        result = self.store.install({"packageId": "fixture/all", "version": "1", "archiveBase64": base64.b64encode(raw).decode()})
        self.assertEqual({"TOOL", "MODEL", "AGENT_STRATEGY", "DATASOURCE", "TRIGGER", "EXTENSION"},
                         {item["componentType"] for item in result["components"]})
        self.assertTrue(all(item["compatibilityStatus"] == "SUPPORTED" for item in result["components"]))

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


if __name__ == "__main__":
    unittest.main()
