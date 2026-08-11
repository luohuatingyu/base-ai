"""安全保存并解析 Dify 插件包声明。"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import shutil
import stat
import tempfile
import zipfile
from pathlib import Path
from typing import Any

import yaml


TYPE_KEYS = {
    "tools": "TOOL",
    "models": "MODEL",
    "agent_strategies": "AGENT_STRATEGY",
    "datasources": "DATASOURCE",
    "triggers": "TRIGGER",
    "endpoints": "EXTENSION",
    "extensions": "EXTENSION",
}


class PackageError(ValueError):
    """表示插件包未通过安全或结构校验。"""


class PackageStore:
    """在受限目录中原子保存插件包及规范化元数据。"""

    def __init__(self) -> None:
        """从环境变量读取存储路径和硬限制。"""
        self.root = Path(os.getenv("PLUGIN_PACKAGE_ROOT", "/data/packages")).resolve()
        self.maximum_archive = int(os.getenv("PLUGIN_MAX_PACKAGE_BYTES", str(5 * 1024 * 1024)))
        self.maximum_unpacked = int(os.getenv("PLUGIN_MAX_UNPACKED_BYTES", str(20 * 1024 * 1024)))
        self.maximum_files = int(os.getenv("PLUGIN_MAX_PACKAGE_FILES", "512"))
        self.root.mkdir(parents=True, exist_ok=True)

    def install(self, request: dict[str, Any]) -> dict[str, Any]:
        """校验、解压并解析一个 Base64 编码插件包。"""
        try:
            archive = base64.b64decode(str(request.get("archiveBase64", "")), validate=True)
        except Exception as exception:
            raise PackageError("ARCHIVE_BASE64_INVALID") from exception
        if not archive or len(archive) > self.maximum_archive:
            raise PackageError("ARCHIVE_SIZE_INVALID")
        fingerprint = hashlib.sha256(archive).hexdigest()
        expected = str(request.get("fingerprint", "")).lower()
        if expected and expected != fingerprint:
            raise PackageError("ARCHIVE_FINGERPRINT_MISMATCH")
        target = self.root / fingerprint
        metadata_file = target / ".base-ai-metadata.json"
        if metadata_file.exists():
            return json.loads(metadata_file.read_text(encoding="utf-8"))
        with tempfile.TemporaryDirectory(prefix="dify-plugin-", dir=self.root) as temporary:
            extracted = Path(temporary)
            self._extract(archive, extracted)
            metadata = self._metadata(extracted, request, fingerprint)
            metadata_file = extracted / ".base-ai-metadata.json"
            metadata_file.write_text(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            os.replace(extracted, target)
        return metadata

    def metadata(self, fingerprint: str) -> tuple[Path, dict[str, Any]]:
        """读取已安装包目录和元数据。"""
        if not fingerprint or not fingerprint.isalnum() or len(fingerprint) != 64:
            raise PackageError("PACKAGE_NOT_FOUND")
        target = self.root / fingerprint
        metadata_file = target / ".base-ai-metadata.json"
        if not metadata_file.exists():
            raise PackageError("PACKAGE_NOT_FOUND")
        return target, json.loads(metadata_file.read_text(encoding="utf-8"))

    def _extract(self, archive: bytes, target: Path) -> None:
        """拒绝路径穿越、链接、文件数和解压体积超限。"""
        archive_file = target / "package.difypkg"
        archive_file.write_bytes(archive)
        total = 0
        files = 0
        try:
            with zipfile.ZipFile(archive_file) as package:
                for entry in package.infolist():
                    normalized = Path(entry.filename.replace("\\", "/"))
                    if normalized.is_absolute() or ".." in normalized.parts:
                        raise PackageError("ARCHIVE_PATH_INVALID")
                    if stat.S_ISLNK(entry.external_attr >> 16):
                        raise PackageError("ARCHIVE_LINK_FORBIDDEN")
                    if entry.is_dir():
                        continue
                    files += 1
                    total += entry.file_size
                    if files > self.maximum_files or total > self.maximum_unpacked:
                        raise PackageError("ARCHIVE_UNPACKED_LIMIT")
                    destination = (target / normalized).resolve()
                    if target not in destination.parents:
                        raise PackageError("ARCHIVE_PATH_INVALID")
                    destination.parent.mkdir(parents=True, exist_ok=True)
                    with package.open(entry) as source, destination.open("wb") as output:
                        shutil.copyfileobj(source, output, length=64 * 1024)
        except zipfile.BadZipFile as exception:
            raise PackageError("ARCHIVE_FORMAT_INVALID") from exception
        archive_file.unlink(missing_ok=True)

    def _metadata(self, root: Path, request: dict[str, Any], fingerprint: str) -> dict[str, Any]:
        """把多类 Dify 声明转换为统一组件模型。"""
        manifest = self._yaml(root, "manifest.yaml")
        plugins = manifest.get("plugins") if isinstance(manifest.get("plugins"), dict) else {}
        components: list[dict[str, Any]] = []
        for key, component_type in TYPE_KEYS.items():
            references = plugins.get(key, []) if isinstance(plugins, dict) else []
            if not isinstance(references, list):
                continue
            for reference in references:
                components.extend(self._components(root, str(reference), component_type))
        if not components:
            raise PackageError("PLUGIN_COMPONENTS_MISSING")
        return {
            "source": "DIFY",
            "packageId": str(request.get("packageId") or manifest.get("name") or ""),
            "version": str(request.get("version") or manifest.get("version") or ""),
            "fingerprint": fingerprint,
            "runtimeLanguage": "python",
            "components": components,
        }

    def _components(self, root: Path, reference: str, component_type: str) -> list[dict[str, Any]]:
        """解析 Provider 声明及其子组件声明。"""
        provider = self._yaml(root, reference)
        credential_schema = self._fields(provider.get("credentials_for_provider", {}))
        child_key = {
            "TOOL": "tools", "AGENT_STRATEGY": "strategies", "DATASOURCE": "datasources",
            "TRIGGER": "triggers", "EXTENSION": "endpoints",
        }.get(component_type)
        children = provider.get(child_key, []) if child_key else []
        if isinstance(children, list) and children:
            return [self._component(root, str(child), component_type, credential_schema) for child in children]
        return [self._component_from_data(root, reference, provider, component_type, credential_schema)]

    def _component(self, root: Path, reference: str, component_type: str,
                   credential_schema: list[dict[str, Any]]) -> dict[str, Any]:
        """解析单个组件 YAML。"""
        return self._component_from_data(root, reference, self._yaml(root, reference), component_type, credential_schema)

    def _component_from_data(self, root: Path, reference: str, data: dict[str, Any], component_type: str,
                             credential_schema: list[dict[str, Any]]) -> dict[str, Any]:
        """规范化组件身份、参数 Schema 和 Python 源文件。"""
        identity = data.get("identity") if isinstance(data.get("identity"), dict) else {}
        source = self._source(data)
        name = str(identity.get("name") or data.get("name") or Path(reference).stem)
        label = self._localized(identity.get("label"), name)
        description = data.get("description") if isinstance(data.get("description"), dict) else {}
        human = description.get("human") if isinstance(description, dict) else description
        source_exists = bool(source) and (root / source).is_file()
        return {
            "externalId": name,
            "name": label,
            "description": self._localized(human, ""),
            "componentType": component_type,
            "schema": self._fields(data.get("parameters", [])),
            "credentialSchema": credential_schema,
            "sourcePath": source,
            "compatibilityStatus": "SUPPORTED" if source_exists else "PARTIAL",
            "compatibilityReason": "" if source_exists else "PYTHON_SOURCE_MISSING",
        }

    def _yaml(self, root: Path, reference: str) -> dict[str, Any]:
        """安全读取包内 YAML 映射。"""
        path = (root / reference).resolve()
        if root not in path.parents or not path.is_file() or path.stat().st_size > 512 * 1024:
            raise PackageError("DECLARATION_INVALID")
        try:
            value = yaml.safe_load(path.read_text(encoding="utf-8"))
        except Exception as exception:
            raise PackageError("DECLARATION_INVALID") from exception
        if not isinstance(value, dict):
            raise PackageError("DECLARATION_INVALID")
        return value

    def _fields(self, value: Any) -> list[dict[str, Any]]:
        """把列表或凭据映射转换为动态表单字段。"""
        entries = value.items() if isinstance(value, dict) else enumerate(value) if isinstance(value, list) else []
        fields = []
        for key, raw in entries:
            item = raw if isinstance(raw, dict) else {}
            name = str(item.get("name") or key)
            fields.append({
                "name": name,
                "label": self._localized(item.get("label"), name),
                "description": self._localized(item.get("human_description"), ""),
                "type": str(item.get("type") or "string"),
                "required": bool(item.get("required", False)),
                "default": item.get("default"),
                "options": item.get("options", []),
                "secret": str(item.get("type", "")).lower() in {"secret-input", "password"},
            })
        return fields

    def _source(self, data: dict[str, Any]) -> str:
        """读取声明中的 Python 源路径。"""
        extra = data.get("extra") if isinstance(data.get("extra"), dict) else {}
        python = extra.get("python") if isinstance(extra.get("python"), dict) else {}
        return str(python.get("source") or python.get("provider_source") or "").replace("\\", "/")

    def _localized(self, value: Any, fallback: str) -> str:
        """优先返回中文再回退英文和默认值。"""
        if isinstance(value, str):
            return value
        if isinstance(value, dict):
            return str(value.get("zh_Hans") or value.get("en_US") or fallback)
        return fallback
