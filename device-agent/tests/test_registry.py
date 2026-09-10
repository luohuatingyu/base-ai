"""Remote XPC Registry 本地安全协议测试。"""

from __future__ import annotations

import pytest

from device_agent.registry import RegistryConfig, RegistryError, RegistrySupervisor


class Process:
    """模拟仍在运行的受管 Registry 进程。"""

    pid = 123

    def poll(self):
        """返回空值表示进程存活。"""
        return None


def test_registry_supervisor_uses_fixed_command_and_root_owned_paths(tmp_path, monkeypatch) -> None:
    """特权进程只能使用固定 Appium 子命令和经校验的端口、设备参数。"""
    calls = []
    root = tmp_path / "registry"
    appium = root / "runtime" / "appium" / "bin" / "appium"
    appium.parent.mkdir(parents=True)
    appium.write_text("appium", encoding="utf-8")
    supervisor = RegistrySupervisor(root, popen=lambda arguments, **kwargs: calls.append(
        (arguments, kwargs)) or Process())
    monkeypatch.setattr("device_agent.registry._probe", lambda _port: 1)

    status = supervisor.handle({"operation": "APPLY_CONFIG", "config": {
        "agentId": "ios-agent-test", "effectivePort": 42314, "desiredState": "ONLINE",
        "configVersion": 2, "deviceUdids": ["raw-udid-test"],
    }})

    assert status["state"] == "ONLINE"
    assert calls[0][0][1:5] == ["driver", "run", "xcuitest", "tunnel-creation"]
    assert calls[0][0][5] == "--"
    assert calls[0][0][-4:] == ["--udid", "raw-udid-test",
                                "--disconnect-retry-max-attempts", "0"]


@pytest.mark.parametrize("payload", [
    {"agentId": "bad id", "effectivePort": 42314, "desiredState": "ONLINE",
     "configVersion": 1, "deviceUdids": []},
    {"agentId": "ios-agent-test", "effectivePort": 80, "desiredState": "ONLINE",
     "configVersion": 1, "deviceUdids": []},
    {"agentId": "ios-agent-test", "effectivePort": 42314, "desiredState": "ONLINE",
     "configVersion": 1, "deviceUdids": ["bad udid with spaces"]},
])
def test_registry_config_rejects_unsafe_values(payload) -> None:
    """非法 Agent、特权端口和恶意设备参数不得进入 root 子进程。"""
    with pytest.raises(RegistryError, match="REGISTRY_CONFIG_INVALID"):
        RegistryConfig.from_payload(payload)
