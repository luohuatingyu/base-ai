"""设备 Agent 非敏感配置测试。"""

from __future__ import annotations

import json
import stat

import pytest

from device_agent.config import AgentConfig, ConfigError


def test_config_round_trip_contains_no_device_identifier(tmp_path) -> None:
    """配置往返只保留 Agent 身份和地址，不落盘设备原始标识。"""
    path = tmp_path / "config.json"
    config = AgentConfig("https://base.example.com", "ios-agent-test", "installation-test")

    config.save(path)

    payload = json.loads(path.read_text(encoding="utf-8"))
    assert AgentConfig.load(path) == config
    assert set(payload) == {"backend_url", "agent_id", "installation_id"}
    assert stat.S_IMODE(path.stat().st_mode) == 0o600


@pytest.mark.parametrize("payload", [{}, {"backend_url": "file:///tmp", "agent_id": "agent"}])
def test_config_rejects_invalid_payload(tmp_path, payload) -> None:
    """空配置和非 HTTP 回连地址均不得加载。"""
    path = tmp_path / "config.json"
    path.write_text(json.dumps(payload), encoding="utf-8")

    with pytest.raises(ConfigError, match="AGENT_CONFIG_INVALID"):
        AgentConfig.load(path)
