"""设备 Agent 命令生命周期测试。"""

from __future__ import annotations

from types import SimpleNamespace

import pytest

from device_agent import main as module
from device_agent.device_detect import DeviceCandidate
from device_agent.main import AgentRuntime
from device_agent.wda import WdaError


def test_health_reports_active_release_version(monkeypatch) -> None:
    """健康心跳必须上报 current 指向的发布版本而非固定包版本。"""
    reports: list[dict[str, object]] = []
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.backend = SimpleNamespace(health=lambda payload: reports.append(payload))
    monkeypatch.setattr(module, "active_version", lambda fallback: "20260911.0123+abcdef123456")
    monkeypatch.setattr(module, "available_versions", lambda: ["20260911.0123+abcdef123456"])
    monkeypatch.setattr(module, "xcuitest_driver_version", lambda: "12.11.1")

    runtime.report_health()

    assert reports == [{
        "status": "ONLINE",
        "agentVersion": "20260911.0123+abcdef123456",
        "iosVersion": None,
        "xcuitestDriverVersion": "12.11.1",
        "lastErrorCode": None,
        "availableVersions": ["20260911.0123+abcdef123456"],
    }]


def test_successful_upgrade_stops_loop_after_reporting(monkeypatch) -> None:
    """升级成功须先上报命令终态，再退出轮询等待 LaunchAgent 重启。"""
    reports: list[tuple[object, ...]] = []
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.running = True
    runtime.backend = SimpleNamespace(report_command=lambda *values: reports.append(values))
    runtime.wda = SimpleNamespace(fail=lambda *_values: None)
    monkeypatch.setattr(runtime, "_dispatch", lambda _command, _params, _target: "upgraded")

    runtime.execute({
        "commandId": 9,
        "leaseToken": "lease-test",
        "commandType": "UPGRADE",
        "commandParams": {},
    })

    assert reports == [(9, "lease-test", "COMPLETED", "upgraded")]
    assert runtime.running is False


def test_failed_upgrade_keeps_current_process_running(monkeypatch) -> None:
    """升级失败不得退出仍可服务的当前 Agent 进程。"""
    reports: list[tuple[object, ...]] = []
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.running = True
    runtime.backend = SimpleNamespace(report_command=lambda *values: reports.append(values))
    runtime.wda = SimpleNamespace(fail=lambda *_values: None)

    def fail(_command, _params, _target):
        raise RuntimeError("UPGRADE_INSTALL_FAILED")

    monkeypatch.setattr(runtime, "_dispatch", fail)
    runtime.execute({
        "commandId": 10,
        "leaseToken": "lease-test",
        "commandType": "UPGRADE",
        "commandParams": {},
    })

    assert reports == [(10, "lease-test", "FAILED", "命令执行失败", "UPGRADE_INSTALL_FAILED")]
    assert runtime.running is True


def test_setup_wda_prechecks_signing_configuration() -> None:
    """XCODEBUILD 模式缺签名时必须在发起 Appium 会话前回稳定错误码。"""
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.backend = SimpleNamespace(wda_config=lambda: {"launchMode": "XCODEBUILD"})

    with pytest.raises(WdaError, match="SIGNING_IDENTITY_MISSING"):
        runtime._dispatch("SETUP_WDA", {}, "device")


@pytest.mark.parametrize(("connected_states", "expected_udids"), [
    ([], ()),
    ([False], ("raw-udid-0",)),
    ([True], ("raw-udid-0",)),
    ([False, True], ("raw-udid-0", "raw-udid-1")),
])
def test_registry_receives_all_discovered_devices(
    connected_states: list[bool], expected_udids: tuple[str, ...],
) -> None:
    """Registry 必须接收未建隧道的候选设备，同时不得伪造设备在线状态。"""
    applied = []
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.config = SimpleNamespace(agent_id="ios-agent-test")
    runtime.backend = SimpleNamespace(registry_config=lambda: {
        "effectivePort": 42314,
        "desiredState": "ONLINE",
        "configVersion": 1,
    })
    runtime.registry = SimpleNamespace(
        apply=lambda config: applied.append(config) or {"state": "STARTING"},
    )
    devices = [
        DeviceCandidate(f"raw-udid-{index}", f"Device {index}", "iPhone", "18.0",
                        connected, "USB")
        for index, connected in enumerate(connected_states)
    ]

    status = runtime._apply_registry_config(devices)

    assert status == {"state": "STARTING"}
    assert applied[0].device_udids == expected_udids
    assert [device.report("ios-agent-test")["connected"] for device in devices] == connected_states
