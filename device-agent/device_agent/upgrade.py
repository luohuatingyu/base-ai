"""校验和保护的 Agent 代码包升级。"""

from __future__ import annotations

import hashlib
import io
import os
import shutil
import subprocess
import sys
import tarfile
import tempfile
import urllib.request
from pathlib import Path


SUPPORT_DIR = Path.home() / "Library" / "Application Support" / "BaseAI" / "DeviceAgent"
VERSIONS_DIR = SUPPORT_DIR / "versions"
CURRENT_LINK = SUPPORT_DIR / "current"


def upgrade(backend_url: str) -> str:
    """下载清单和代码包，校验 SHA-256 后原子切换版本链接。"""
    base = backend_url.rstrip("/") + "/agent-dist/"
    manifest = _manifest(_download(base + "manifest.env"))
    version = manifest.get("AGENT_CODE_VERSION", "")
    expected = manifest.get("AGENT_PACKAGE_SHA256", "")
    if not version or any(value in version for value in ("/", "\\", "..")) or len(expected) != 64:
        raise RuntimeError("UPGRADE_MANIFEST_INVALID")
    archive = _download(base + "device-agent.tar.gz")
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
    _install(target)
    temporary = CURRENT_LINK.with_name("current.next")
    temporary.unlink(missing_ok=True)
    temporary.symlink_to(target)
    os.replace(temporary, CURRENT_LINK)
    return version


def _install(target: Path) -> None:
    """使用当前虚拟环境原子重装已校验版本，不解析任何远端依赖。"""
    try:
        subprocess.run([
            sys.executable, "-m", "pip", "install", "--disable-pip-version-check",
            "--no-deps", "--force-reinstall", str(target),
        ], check=True, capture_output=True, text=True, timeout=120)
    except (OSError, subprocess.SubprocessError) as exception:
        raise RuntimeError("UPGRADE_INSTALL_FAILED") from exception


def _download(url: str) -> bytes:
    """从同一后端固定路径下载升级资源。"""
    with urllib.request.urlopen(url, timeout=120) as response:
        return response.read(100 * 1024 * 1024)


def _manifest(payload: bytes) -> dict[str, str]:
    """解析 KEY=VALUE 运行时清单。"""
    values: dict[str, str] = {}
    for line in payload.decode("utf-8").splitlines():
        key, separator, value = line.partition("=")
        if separator:
            values[key.strip()] = value.strip()
    return values
