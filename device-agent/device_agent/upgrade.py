"""校验和保护的 Agent 代码包升级。"""

from __future__ import annotations

import hashlib
import io
import os
import shutil
import ssl
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path


SUPPORT_DIR = Path.home() / "Library" / "Application Support" / "BaseAI" / "DeviceAgent"
VERSIONS_DIR = SUPPORT_DIR / "versions"
CURRENT_LINK = SUPPORT_DIR / "current"


def upgrade(
    backend_url: str, target_version: str | None = None, ca_file: str | None = None,
) -> str:
    """升级到最新包，或切换到本机已校验保留的指定版本。"""
    if target_version:
        _validate_version(target_version)
        target = VERSIONS_DIR / target_version
        if not target.is_dir():
            raise RuntimeError("UPGRADE_TARGET_NOT_AVAILABLE")
        _activate(target)
        return target_version
    base = backend_url.rstrip("/") + "/agent-dist/"
    manifest = _manifest(_download(base + "manifest.env", ca_file))
    version = manifest.get("AGENT_CODE_VERSION", "")
    expected = manifest.get("AGENT_PACKAGE_SHA256", "")
    if not version or len(expected) != 64:
        raise RuntimeError("UPGRADE_MANIFEST_INVALID")
    _validate_version(version)
    archive = _download(base + "device-agent.tar.gz", ca_file)
    if hashlib.sha256(archive).hexdigest() != expected:
        raise RuntimeError("UPGRADE_CHECKSUM_MISMATCH")
    VERSIONS_DIR.mkdir(parents=True, exist_ok=True)
    target = VERSIONS_DIR / version
    if not target.exists():
        staging = Path(tempfile.mkdtemp(prefix="upgrade-", dir=SUPPORT_DIR))
        try:
            with tarfile.open(fileobj=io.BytesIO(archive), mode="r:gz") as package:
                package.extractall(staging, filter="data")
            staging.replace(target)
        except Exception:
            shutil.rmtree(staging, ignore_errors=True)
            raise
    _activate(target)
    return version


def active_version(fallback: str) -> str:
    """返回 current 指向的可信发布版本，不可用时回退静态包版本。"""
    try:
        versions = VERSIONS_DIR.resolve(strict=True)
        target = CURRENT_LINK.resolve(strict=True)
        if target.parent != versions or not target.is_dir():
            return fallback
        _validate_version(target.name)
        return target.name
    except (OSError, RuntimeError):
        return fallback


def available_versions() -> list[str]:
    """返回本机实际保留且名称合法的 Agent 版本。"""
    if not VERSIONS_DIR.is_dir():
        return []
    versions: list[str] = []
    for path in VERSIONS_DIR.iterdir():
        try:
            _validate_version(path.name)
        except RuntimeError:
            continue
        if path.is_dir():
            versions.append(path.name)
    return sorted(versions, reverse=True)[:20]


def _activate(target: Path) -> None:
    """重装并原子切换 current 符号链接。"""
    _install(target)
    temporary = CURRENT_LINK.with_name("current.next")
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(target)
    os.replace(temporary, CURRENT_LINK)


def _validate_version(version: str) -> None:
    """拒绝路径字符和异常长度进入本地版本目录。"""
    if not version or len(version) > 80 or any(value in version for value in ("/", "\\", "..")):
        raise RuntimeError("UPGRADE_TARGET_INVALID")


def _install(target: Path) -> None:
    """使用当前虚拟环境原子重装已校验版本，不解析任何远端依赖。"""
    try:
        subprocess.run([
            sys.executable, "-m", "pip", "install", "--disable-pip-version-check",
            "--no-deps", "--force-reinstall", str(target),
        ], check=True, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError) as exception:
        raise RuntimeError("UPGRADE_INSTALL_FAILED") from exception


def _download(url: str, ca_file: str | None = None) -> bytes:
    """从同一后端固定路径下载升级资源，保留系统根证书并追加平台自签根证书。"""
    context = ssl.create_default_context()
    if ca_file:
        context.load_verify_locations(cafile=ca_file)
    with urllib.request.urlopen(url, timeout=120, context=context) as response:
        return response.read(100 * 1024 * 1024)


def _manifest(payload: bytes) -> dict[str, str]:
    """解析 KEY=VALUE 运行时清单。"""
    values: dict[str, str] = {}
    for line in payload.decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key.strip()] = value.strip()
    return values
