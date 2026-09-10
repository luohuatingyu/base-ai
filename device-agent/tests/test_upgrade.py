"""设备 Agent 自升级测试。"""

from __future__ import annotations

import hashlib
import io
import tarfile
from pathlib import Path
from types import SimpleNamespace

from device_agent import upgrade as module


def test_upgrade_installs_verified_package_before_switching(tmp_path, monkeypatch) -> None:
    """校验后的新包必须先装入当前虚拟环境，再切换版本链接。"""
    support = tmp_path / "support"
    versions = support / "versions"
    current = support / "current"
    archive = _archive()
    checksum = hashlib.sha256(archive).hexdigest()
    manifest = f"AGENT_CODE_VERSION=1.2.3\nAGENT_PACKAGE_SHA256={checksum}\n".encode()
    calls: list[list[str]] = []

    monkeypatch.setattr(module, "SUPPORT_DIR", support)
    monkeypatch.setattr(module, "VERSIONS_DIR", versions)
    monkeypatch.setattr(module, "CURRENT_LINK", current)
    monkeypatch.setattr(module, "_download", lambda url: manifest if url.endswith("manifest.env") else archive)
    monkeypatch.setattr(module.subprocess, "run", lambda command, **_kwargs: (
        calls.append(command) or SimpleNamespace(returncode=0)))

    version = module.upgrade("https://base.example.com")

    assert version == "1.2.3"
    assert current.resolve() == versions / "1.2.3"
    assert (current / "device_agent" / "__init__.py").is_file()
    assert calls and calls[0][-1] == str(versions / "1.2.3")
    assert "--no-deps" in calls[0]


def _archive() -> bytes:
    """构造最小可安装目录结构的内存归档。"""
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as package:
        payload = b'__version__ = "1.2.3"\n'
        info = tarfile.TarInfo("device_agent/__init__.py")
        info.size = len(payload)
        package.addfile(info, io.BytesIO(payload))
    return output.getvalue()
