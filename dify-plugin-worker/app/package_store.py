"""安全保存并解析 Dify 插件包声明。"""

from __future__ import annotations

import base64
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import tempfile
import time
import zipfile
from pathlib import Path
from typing import Any
from urllib.parse import urlparse

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

REQUIREMENT_NAME = re.compile(r"^([A-Za-z0-9][A-Za-z0-9._-]*)(?:\[[A-Za-z0-9_,.-]+\])?(.*)$")
FORBIDDEN_REQUIREMENTS = {"dify-plugin", "dify_plugin"}
HOST_ABI_VERSION = 7


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
        self.install_timeout = int(os.getenv("PLUGIN_DEPENDENCY_INSTALL_TIMEOUT_SECONDS", "180"))
        self.probe_timeout = int(os.getenv("PLUGIN_PROBE_TIMEOUT_SECONDS", "20"))
        self.pip_cache = Path(os.getenv("PLUGIN_PIP_CACHE_DIR", str(self.root / ".pip-cache"))).resolve()
        if self.root not in self.pip_cache.parents:
            raise RuntimeError("PLUGIN_PIP_CACHE_DIR 必须位于插件持久化目录内")
        self.root.mkdir(parents=True, exist_ok=True)
        self.pip_cache.mkdir(parents=True, exist_ok=True)

    def install(self, request: dict[str, Any]) -> dict[str, Any]:
        """校验、解压并解析一个 Base64 编码插件包。"""
        started = time.monotonic()
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
            cached = json.loads(metadata_file.read_text(encoding="utf-8"))
            reasons = {str(item.get("compatibilityReason", "")) for item in cached.get("components", [])}
            if cached.get("hostAbiVersion") != HOST_ABI_VERSION or (
                reasons and reasons.issubset({"DEPENDENCY_INSTALL_FAILED", "DEPENDENCY_INSTALL_TIMEOUT"})
            ):
                shutil.rmtree(target)
            else:
                self._log_timing(fingerprint, True, 0, 0, started, len(cached.get("components", [])))
                return cached
        with tempfile.TemporaryDirectory(prefix="dify-plugin-", dir=self.root) as temporary:
            extracted = Path(temporary)
            self._extract(archive, extracted)
            dependency_started = time.monotonic()
            dependency_error = self._install_dependencies(extracted)
            dependency_milliseconds = round((time.monotonic() - dependency_started) * 1000)
            probe_started = time.monotonic()
            metadata = self._metadata(extracted, request, fingerprint, dependency_error)
            probe_milliseconds = round((time.monotonic() - probe_started) * 1000)
            metadata_file = extracted / ".base-ai-metadata.json"
            metadata_file.write_text(json.dumps(metadata, ensure_ascii=False, separators=(",", ":")), encoding="utf-8")
            os.replace(extracted, target)
        self._log_timing(fingerprint, False, dependency_milliseconds, probe_milliseconds, started,
                         len(metadata["components"]))
        return metadata

    def _log_timing(self, fingerprint: str, cache_hit: bool, dependency_milliseconds: int,
                    probe_milliseconds: int, started: float, components: int) -> None:
        """记录不含插件内容和凭据的阶段耗时，便于区分缓存、依赖和组件加载瓶颈。"""
        total_milliseconds = round((time.monotonic() - started) * 1000)
        sys.stderr.write(
            f"plugin_probe fingerprint={fingerprint[:12]} cache_hit={str(cache_hit).lower()} "
            f"dependencies_ms={dependency_milliseconds} components_ms={probe_milliseconds} "
            f"components={components} total_ms={total_milliseconds}\n"
        )

    def metadata(self, fingerprint: str) -> tuple[Path, dict[str, Any]]:
        """读取已安装包目录和元数据。"""
        if not fingerprint or not fingerprint.isalnum() or len(fingerprint) != 64:
            raise PackageError("PACKAGE_NOT_FOUND")
        target = self.root / fingerprint
        metadata_file = target / ".base-ai-metadata.json"
        if not metadata_file.exists():
            raise PackageError("PACKAGE_NOT_FOUND")
        return target, json.loads(metadata_file.read_text(encoding="utf-8"))

    def remove(self, fingerprint: str) -> dict[str, bool]:
        """删除严格 SHA-256 指纹对应的未引用缓存包。"""
        if not re.fullmatch(r"[a-f0-9]{64}", fingerprint or ""):
            raise PackageError("PACKAGE_NOT_FOUND")
        target = (self.root / fingerprint).resolve()
        if self.root not in target.parents:
            raise PackageError("PACKAGE_NOT_FOUND")
        shutil.rmtree(target, ignore_errors=True)
        return {"removed": True}

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
                    if files > self.maximum_files:
                        raise PackageError("ARCHIVE_FILE_LIMIT")
                    if total > self.maximum_unpacked:
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

    def _metadata(self, root: Path, request: dict[str, Any], fingerprint: str,
                  dependency_error: str) -> dict[str, Any]:
        """把多类 Dify 声明转换为统一组件模型。"""
        manifest = self._yaml(root, "manifest.yaml")
        plugins = manifest.get("plugins") if isinstance(manifest.get("plugins"), dict) else {}
        components: list[dict[str, Any]] = []
        for key, component_type in TYPE_KEYS.items():
            references = plugins.get(key, []) if isinstance(plugins, dict) else []
            if not isinstance(references, list):
                continue
            for reference in references:
                components.extend(self._components(root, str(reference), component_type, dependency_error))
        if not components:
            raise PackageError("PLUGIN_COMPONENTS_MISSING")
        license_name, license_url = self._license(manifest.get("license"))
        return {
            "source": "DIFY",
            "packageId": str(request.get("packageId") or manifest.get("name") or ""),
            "version": str(request.get("version") or manifest.get("version") or ""),
            "fingerprint": fingerprint,
            "runtimeLanguage": "python",
            "hostAbiVersion": HOST_ABI_VERSION,
            "licenseName": license_name,
            "licenseUrl": license_url,
            "externalServices": self._external_services(root),
            "components": components,
        }

    def _license(self, raw: Any) -> tuple[str, str]:
        """只接收清单明确声明的许可证名称和 HTTPS 地址。"""
        value = raw[0] if isinstance(raw, list) and raw else raw
        if isinstance(value, str):
            return value.strip()[:160], ""
        if not isinstance(value, dict):
            return "", ""
        name = str(value.get("type") or value.get("name") or "").strip()[:160]
        candidate = str(value.get("url") or "").strip()
        parsed = urlparse(candidate)
        url = candidate[:500] if parsed.scheme == "https" and parsed.hostname and not parsed.username else ""
        return name, url

    def _external_services(self, root: Path) -> list[dict[str, str]]:
        """从 YAML 声明的固定 HTTPS 字面量提取待人工确认的服务域名。"""
        domains: set[str] = set()
        for path in sorted(root.rglob("*")):
            if path.suffix.lower() not in {".yaml", ".yml"} or not path.is_file():
                continue
            if path.name.lower() == "manifest.yaml":
                continue
            try:
                content = path.read_text(encoding="utf-8")
            except (OSError, UnicodeError):
                continue
            for candidate in re.findall(r"https://[^\s\"'<>)}\]]+", content, flags=re.IGNORECASE):
                parsed = urlparse(candidate)
                if parsed.hostname:
                    domains.add(parsed.hostname.lower())
        return [{"name": domain, "domain": domain} for domain in sorted(domains)[:64]]

    def _components(self, root: Path, reference: str, component_type: str,
                    dependency_error: str) -> list[dict[str, Any]]:
        """解析 Provider 声明及其子组件声明。"""
        provider = self._yaml(root, reference)
        credential_value = provider.get("credentials_for_provider", {})
        provider_schema = provider.get("provider_credential_schema")
        if not credential_value and isinstance(provider_schema, dict):
            credential_value = provider_schema.get("credential_form_schemas", [])
        credential_schema = self._fields(credential_value)
        if component_type == "MODEL":
            return self._model_components(root, reference, provider, credential_schema, dependency_error)
        child_key = {
            "TOOL": "tools", "AGENT_STRATEGY": "strategies", "DATASOURCE": "datasources",
            "TRIGGER": "triggers", "EXTENSION": "endpoints",
        }.get(component_type)
        children = provider.get(child_key, []) if child_key else []
        if isinstance(children, list) and children:
            return [self._component(root, str(child), component_type, credential_schema, dependency_error)
                    for child in children]
        return [self._component_from_data(root, reference, provider, component_type, credential_schema,
                                          dependency_error)]

    def _model_components(self, root: Path, reference: str, provider: dict[str, Any],
                          credential_schema: list[dict[str, Any]], dependency_error: str) -> list[dict[str, Any]]:
        """把 Dify 模型 Provider 展开为真正声明在 model_sources 中的可执行模型组件。"""
        extra = provider.get("extra") if isinstance(provider.get("extra"), dict) else {}
        python = extra.get("python") if isinstance(extra.get("python"), dict) else {}
        sources = python.get("model_sources", [])
        if not isinstance(sources, list) or not sources:
            return [self._component_from_data(root, reference, provider, "MODEL", credential_schema,
                                              dependency_error)]
        provider_id = str(provider.get("provider") or Path(reference).stem)
        label_localization = self._localization(provider.get("label"), provider_id, 160)
        description_localization = self._localization(provider.get("description"), "", 1000)
        label = label_localization["zh-CN"]
        description = description_localization["zh-CN"]
        supported = provider.get("supported_model_types", [])
        supported_types = [self._model_type(str(value)) for value in supported] if isinstance(supported, list) else []
        components = []
        for index, raw_source in enumerate(sources):
            source = str(raw_source).replace("\\", "/")
            model_type = self._model_type_from_source(source, supported_types, index)
            source_exists = bool(source) and (root / source).is_file()
            probe_error = self._probe(root, source, "MODEL") if source_exists and not dependency_error else dependency_error
            components.append({
                "externalId": f"{provider_id}.{model_type}",
                "name": f"{label} {self._model_type_label(model_type)}",
                "description": description,
                "localization": {
                    "name": {locale: f"{value} {self._model_type_label(model_type)}"
                             for locale, value in label_localization.items()},
                    "description": description_localization,
                },
                "componentType": "MODEL",
                "modelType": model_type,
                "schema": self._model_schema(model_type),
                "credentialSchema": credential_schema,
                "sourcePath": source,
                "compatibilityStatus": "SUPPORTED" if source_exists and not probe_error else "PARTIAL",
                "compatibilityReason": "" if source_exists and not probe_error
                else probe_error or "PYTHON_SOURCE_MISSING",
            })
        return components

    def _model_schema(self, model_type: str) -> list[dict[str, Any]]:
        """生成统一模型字段，并补充各协议调用所需的可配置参数。"""
        fields = [
            {"name": "model", "label": "模型", "description": "",
             "localization": {"label": {"zh-CN": "模型", "en-US": "Model"},
                              "description": {"zh-CN": "", "en-US": ""}}, "type": "string",
             "required": True, "default": None, "options": [], "secret": False},
            {"name": "model_parameters", "label": "模型参数", "description": "",
             "localization": {"label": {"zh-CN": "模型参数", "en-US": "Model Parameters"},
                              "description": {"zh-CN": "", "en-US": ""}}, "type": "object",
             "required": False, "default": {}, "options": [], "secret": False},
        ]
        if model_type == "llm":
            fields.extend([
                {"name": "tools", "label": "工具", "description": "",
                 "localization": {"label": {"zh-CN": "工具", "en-US": "Tools"},
                                  "description": {"zh-CN": "", "en-US": ""}},
                 "type": "array", "required": False,
                 "default": [], "options": [], "secret": False},
                {"name": "stop", "label": "停止词", "description": "",
                 "localization": {"label": {"zh-CN": "停止词", "en-US": "Stop Sequences"},
                                  "description": {"zh-CN": "", "en-US": ""}},
                 "type": "array", "required": False,
                 "default": [], "options": [], "secret": False},
            ])
        elif model_type == "text-embedding":
            fields.append({"name": "input_type", "label": "输入用途", "description": "",
                           "localization": {"label": {"zh-CN": "输入用途", "en-US": "Input Purpose"},
                                            "description": {"zh-CN": "", "en-US": ""}}, "type": "select",
                           "required": False, "default": "document", "options": [
                               {"value": "document", "label": {"zh_Hans": "文档", "en_US": "Document"}},
                               {"value": "query", "label": {"zh_Hans": "查询", "en_US": "Query"}}],
                           "secret": False})
        elif model_type == "tts":
            fields.append({"name": "voice", "label": "音色", "description": "",
                           "localization": {"label": {"zh-CN": "音色", "en-US": "Voice"},
                                            "description": {"zh-CN": "", "en-US": ""}}, "type": "string",
                           "required": True, "default": None, "options": [], "secret": False})
        return fields

    def _model_type_from_source(self, source: str, supported: list[str], index: int) -> str:
        """根据标准目录名识别模型协议，并在单源 Provider 中回退声明类型。"""
        normalized = source.lower().replace("-", "_")
        aliases = (("text_embedding", "text-embedding"), ("speech2text", "speech2text"),
                   ("speech_to_text", "speech2text"), ("moderation", "moderation"),
                   ("rerank", "rerank"), ("tts", "tts"), ("llm", "llm"))
        for needle, value in aliases:
            if needle in normalized:
                return value
        if len(supported) == 1:
            return supported[0]
        return supported[index] if index < len(supported) else f"model-{index + 1}"

    def _model_type(self, value: str) -> str:
        """规范化 Dify 模型类型名称。"""
        return value.strip().lower().replace("_", "-")

    def _model_type_label(self, value: str) -> str:
        """生成简短稳定的模型组件显示类型。"""
        return {"llm": "LLM", "text-embedding": "Embedding", "speech2text": "Speech To Text",
                "moderation": "Moderation", "tts": "Text To Speech", "rerank": "Rerank"}.get(
                    value, value.replace("-", " ").title())

    def _component(self, root: Path, reference: str, component_type: str,
                   credential_schema: list[dict[str, Any]], dependency_error: str) -> dict[str, Any]:
        """解析单个组件 YAML。"""
        return self._component_from_data(root, reference, self._yaml(root, reference), component_type,
                                         credential_schema, dependency_error)

    def _component_from_data(self, root: Path, reference: str, data: dict[str, Any], component_type: str,
                             credential_schema: list[dict[str, Any]], dependency_error: str) -> dict[str, Any]:
        """规范化组件身份、参数 Schema 和 Python 源文件。"""
        identity = data.get("identity") if isinstance(data.get("identity"), dict) else {}
        source = self._source(data)
        name = str(identity.get("name") or data.get("name") or Path(reference).stem)
        name_localization = self._localization(identity.get("label"), name, 160)
        description = data.get("description") if isinstance(data.get("description"), dict) else {}
        human = description.get("human") if isinstance(description, dict) else description
        description_localization = self._localization(human, "", 1000)
        source_exists = bool(source) and (root / source).is_file()
        probe_error = self._probe(root, source, component_type) if source_exists and not dependency_error else dependency_error
        return {
            "externalId": name,
            "name": name_localization["zh-CN"],
            "description": description_localization["zh-CN"],
            "localization": {"name": name_localization, "description": description_localization},
            "componentType": component_type,
            "schema": self._fields(data.get("parameters", [])),
            "credentialSchema": credential_schema,
            "sourcePath": source,
            "compatibilityStatus": "SUPPORTED" if source_exists and not probe_error else "PARTIAL",
            "compatibilityReason": "" if source_exists and not probe_error
            else probe_error or "PYTHON_SOURCE_MISSING",
        }

    def _install_dependencies(self, root: Path) -> str:
        """安装插件声明的第三方 Python 依赖，同时排除 Dify SDK 和非注册表来源。"""
        requirements = root / "requirements.txt"
        if not requirements.is_file():
            return ""
        try:
            lines = self._safe_requirements(requirements.read_text(encoding="utf-8"))
        except (OSError, UnicodeError, PackageError) as exception:
            return str(exception)
        if not lines:
            return ""
        sanitized = root / ".base-ai-requirements.txt"
        sanitized.write_text("\n".join(lines) + "\n", encoding="utf-8")
        dependencies = root / ".deps"
        try:
            with tempfile.TemporaryDirectory(prefix=".pip-", dir=root) as pip_temporary:
                environment = {
                    "PATH": os.getenv("PATH", ""), "HOME": pip_temporary, "TMPDIR": pip_temporary,
                    "PIP_INDEX_URL": os.getenv("PIP_INDEX_URL", "https://pypi.org/simple"),
                    "PIP_CACHE_DIR": str(self.pip_cache), "PIP_DISABLE_PIP_VERSION_CHECK": "1",
                    "PYTHONDONTWRITEBYTECODE": "1", "LANG": "C.UTF-8",
                    "HTTP_PROXY": os.getenv("HTTP_PROXY", ""), "HTTPS_PROXY": os.getenv("HTTPS_PROXY", ""),
                    "NO_PROXY": os.getenv("NO_PROXY", ""),
                }
                result = subprocess.run(
                    [sys.executable, "-m", "pip", "install", "--no-input", "--no-compile",
                     "--target", str(dependencies), "-r", str(sanitized)],
                    capture_output=True, text=True, timeout=max(10, min(self.install_timeout, 600)),
                    env=environment, check=False,
                )
        except subprocess.TimeoutExpired:
            return "DEPENDENCY_INSTALL_TIMEOUT"
        finally:
            sanitized.unlink(missing_ok=True)
        if result.returncode != 0:
            return "DEPENDENCY_INSTALL_FAILED"
        self._remove_forbidden_sdk(dependencies)
        return ""

    def _safe_requirements(self, content: str) -> list[str]:
        """接受 PyPI 名称与版本约束，拒绝 URL、路径、选项和 Dify SDK。"""
        if len(content.encode("utf-8")) > 128 * 1024:
            raise PackageError("REQUIREMENTS_SIZE_INVALID")
        result: list[str] = []
        for raw in content.splitlines():
            line = raw.split("#", 1)[0].strip()
            if not line:
                continue
            if line.startswith("-") or " @ " in line or "://" in line or line.startswith((".", "/")):
                raise PackageError("DEPENDENCY_SOURCE_FORBIDDEN")
            requirement = line.split(";", 1)[0].strip()
            matched = REQUIREMENT_NAME.fullmatch(requirement)
            if not matched:
                raise PackageError("DEPENDENCY_DECLARATION_INVALID")
            name = matched.group(1).lower().replace("_", "-")
            if name in {item.replace("_", "-") for item in FORBIDDEN_REQUIREMENTS}:
                continue
            if not re.fullmatch(r"==\d+(?:\.\d+)+(?:[A-Za-z0-9.-]*)", matched.group(2).strip()):
                raise PackageError("DEPENDENCY_VERSION_NOT_PINNED")
            result.append(line)
            if len(result) > 128:
                raise PackageError("DEPENDENCY_COUNT_INVALID")
        return result

    def _remove_forbidden_sdk(self, dependencies: Path) -> None:
        """防止传递依赖把被禁止的 Dify SDK 带入插件运行目录。"""
        for child in dependencies.iterdir() if dependencies.exists() else []:
            normalized = child.name.lower().replace("_", "-")
            if normalized == "dify-plugin" or normalized.startswith("dify-plugin-"):
                if child.is_dir():
                    shutil.rmtree(child)
                else:
                    child.unlink(missing_ok=True)

    def _probe(self, root: Path, source: str, component_type: str) -> str:
        """在短生命周期子进程中实际导入并构造组件，避免仅凭文件存在误报。"""
        environment = {
            "PATH": os.getenv("PATH", ""), "PYTHONPATH": str(Path(__file__).resolve().parent.parent),
            "PYTHONDONTWRITEBYTECODE": "1", "PYTHONUNBUFFERED": "1", "LANG": "C.UTF-8",
        }
        try:
            result = subprocess.run(
                [sys.executable, "-m", "app.probe_child", str(root), source, component_type], capture_output=True, text=True,
                timeout=max(1, min(self.probe_timeout, 60)), env=environment, check=False,
            )
        except subprocess.TimeoutExpired:
            return "PLUGIN_PROBE_TIMEOUT"
        if result.returncode == 0:
            return ""
        try:
            error = str(json.loads(result.stdout).get("error", "PLUGIN_IMPORT_FAILED"))
        except Exception:
            error = "PLUGIN_IMPORT_FAILED"
        return error[:300]

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
            name = str(item.get("name") or item.get("variable") or key)
            label_localization = self._localization(item.get("label"), name, 160)
            description_localization = self._localization(item.get("human_description"), "", 1000)
            fields.append({
                "name": name,
                "label": label_localization["zh-CN"],
                "description": description_localization["zh-CN"],
                "localization": {"label": label_localization, "description": description_localization},
                "type": str(item.get("type") or "string"),
                "required": bool(item.get("required", False)),
                "default": item.get("default"),
                "options": self._options(item.get("options")),
                "secret": str(item.get("type", "")).lower() in {"secret-input", "password"},
            })
        return fields

    def _options(self, value: Any) -> list[Any]:
        """保留枚举稳定值，并把 Dify 多语言标签转换为统一展示元数据。"""
        if not isinstance(value, list):
            return []
        result = []
        for raw in value:
            if not isinstance(raw, dict):
                result.append(raw)
                continue
            item = dict(raw)
            fallback = str(item.get("value") or item.get("name") or "")
            label_localization = self._localization(item.get("label"), fallback, 160)
            item["label"] = label_localization["zh-CN"]
            item["localization"] = {"label": label_localization}
            result.append(item)
        return result

    def _source(self, data: dict[str, Any]) -> str:
        """读取声明中的 Python 源路径。"""
        extra = data.get("extra") if isinstance(data.get("extra"), dict) else {}
        python = extra.get("python") if isinstance(extra.get("python"), dict) else {}
        return str(python.get("source") or python.get("provider_source") or "").replace("\\", "/")

    def _localized(self, value: Any, fallback: str) -> str:
        """优先返回中文再回退英文和默认值。"""
        return self._localization(value, fallback, 1000)["zh-CN"]

    def _localization(self, value: Any, fallback: str, maximum: int) -> dict[str, str]:
        """规范双语声明；任一语言缺失时回退另一语言和稳定技术名称。"""
        if isinstance(value, str):
            zh = en = value.strip()
        elif isinstance(value, dict):
            zh = str(value.get("zh_Hans") or value.get("zh-CN") or "").strip()
            en = str(value.get("en_US") or value.get("en-US") or "").strip()
        else:
            zh = en = ""
        fallback_value = str(fallback or "").strip()
        zh = (zh or en or fallback_value)[:maximum]
        en = (en or zh or fallback_value)[:maximum]
        return {"zh-CN": zh, "en-US": en}
