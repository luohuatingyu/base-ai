"""设备 Agent 自包含安装契约测试。"""

from __future__ import annotations

from pathlib import Path


ROOT = Path(__file__).parents[1]


def test_bootstrap_verifies_python_node_and_agent_archives() -> None:
    """Bootstrap 必须按架构下载并校验全部三类归档。"""
    script = (ROOT / "bootstrap.sh").read_text(encoding="utf-8")

    assert "PYTHON_ARM64_SHA256" in script
    assert "NODE_ARM64_SHA256" in script
    assert 'verify_file "$WORK_DIR/node.tar.gz" "$NODE_SHA"' in script
    assert 'verify_file "$WORK_DIR/agent.tar.gz" "$AGENT_SHA"' in script


def test_installer_pins_appium_and_installs_root_owned_registry() -> None:
    """安装器必须使用固定清单并将特权副本收敛为 root 所有。"""
    script = (ROOT / "install.sh").read_text(encoding="utf-8")
    dockerfile = (ROOT.parent / "caddy" / "Dockerfile").read_text(encoding="utf-8")

    assert "AGENT_APPIUM_SPEC=appium@3.7.0" in dockerfile
    assert "AGENT_XCUITEST_SPEC=xcuitest@12.11.1" in dockerfile
    assert 'sudo chown -R root:wheel "$REGISTRY_STAGE"' in script
    assert "com.baseai.device-agent-registry.plist" in script
    assert "driver install" in script
    assert "wecom" not in script.lower()
