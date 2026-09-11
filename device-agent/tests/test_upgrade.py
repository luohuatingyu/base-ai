"""设备 Agent 自升级测试。"""

from __future__ import annotations

import hashlib
import io
import tarfile
from pathlib import Path
from types import SimpleNamespace

import pytest

from device_agent import upgrade as module


def test_active_version_uses_current_release_directory(tmp_path, monkeypatch) -> None:
    """健康上报版本必须来自 current 实际指向的已保留发布目录。"""
    support = tmp_path / "support"
    versions = support / "versions"
    target = versions / "20260911.0123+abcdef123456"
    target.mkdir(parents=True)
    current = support / "current"
    current.symlink_to(target)
    monkeypatch.setattr(module, "VERSIONS_DIR", versions)
    monkeypatch.setattr(module, "CURRENT_LINK", current)

    assert module.active_version("1.0.0") == target.name


@pytest.mark.parametrize("link_state", ["missing", "broken", "outside", "invalid"])
def test_active_version_falls_back_for_untrusted_current_link(
    tmp_path, monkeypatch, link_state: str,
) -> None:
    """current 不可信或不可用时必须安全回退静态包版本。"""
    support = tmp_path / "support"
    versions = support / "versions"
    versions.mkdir(parents=True)
    current = support / "current"
    if link_state == "broken":
        current.symlink_to(versions / "missing")
    elif link_state == "outside":
        target = tmp_path / "outside" / "20260911.0123+abcdef123456"
        target.mkdir(parents=True)
        current.symlink_to(target)
    elif link_state == "invalid":
        target = versions / "invalid..version"
        target.mkdir()
        current.symlink_to(target)
    monkeypatch.setattr(module, "VERSIONS_DIR", versions)
    monkeypatch.setattr(module, "CURRENT_LINK", current)

    assert module.active_version("1.0.0") == "1.0.0"


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
    monkeypatch.setattr(
        module, "_download",
        lambda url, _ca_file=None: manifest if url.endswith("manifest.env") else archive,
    )
    monkeypatch.setattr(module.subprocess, "run", lambda command, **_kwargs: (
        calls.append(command) or SimpleNamespace(returncode=0)))

    version = module.upgrade("https://base.example.com")

    assert version == "1.2.3"
    assert current.resolve() == versions / "1.2.3"
    assert (current / "device_agent" / "__init__.py").is_file()
    assert calls and calls[0][-1] == str(versions / "1.2.3")
    assert "--no-deps" in calls[0]


def test_upgrade_can_switch_to_retained_version_without_network(tmp_path, monkeypatch) -> None:
    """指定已保留版本时应直接原子回退，不得访问远端清单。"""
    support = tmp_path / "support"
    target = support / "versions" / "1.1.0"
    target.mkdir(parents=True)
    (target / "pyproject.toml").write_text("[build-system]\n", encoding="utf-8")
    calls: list[list[str]] = []
    monkeypatch.setattr(module, "SUPPORT_DIR", support)
    monkeypatch.setattr(module, "VERSIONS_DIR", support / "versions")
    monkeypatch.setattr(module, "CURRENT_LINK", support / "current")
    monkeypatch.setattr(module, "_download", lambda *_args: pytest.fail("不应下载远端文件"))
    monkeypatch.setattr(module.subprocess, "run", lambda command, **_kwargs: (
        calls.append(command) or SimpleNamespace(returncode=0)))

    version = module.upgrade("https://base.example.com", "1.1.0")

    assert version == "1.1.0"
    assert module.CURRENT_LINK.resolve() == target
    assert calls


def _archive() -> bytes:
    """构造最小可安装目录结构的内存归档。"""
    output = io.BytesIO()
    with tarfile.open(fileobj=output, mode="w:gz") as package:
        payload = b'__version__ = "1.2.3"\n'
        info = tarfile.TarInfo("device_agent/__init__.py")
        info.size = len(payload)
        package.addfile(info, io.BytesIO(payload))
    return output.getvalue()
