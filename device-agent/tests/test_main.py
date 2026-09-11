"""设备 Agent 命令生命周期测试。"""

from __future__ import annotations

import hashlib
import json
from pathlib import Path
from types import SimpleNamespace

import pytest

from device_agent import device_detect
from device_agent import main as module
from device_agent.device_detect import DeviceCandidate
from device_agent.main import AgentRuntime
from device_agent.ida import IdaError, IdaRuntime


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
    runtime.ida = SimpleNamespace(fail=lambda *_values: None)
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
    runtime.ida = SimpleNamespace(fail=lambda *_values: None)

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


def test_setup_ida_prechecks_signing_configuration() -> None:
    """XCODEBUILD 模式缺签名时必须在发起 Appium 会话前回稳定错误码。"""
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.backend = SimpleNamespace(ida_config=lambda: {"launchMode": "XCODEBUILD"})

    with pytest.raises(IdaError, match="SIGNING_IDENTITY_MISSING"):
        runtime._dispatch("SETUP_IDA", {}, "device")


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


@pytest.mark.parametrize("trigger", ["heartbeat", "DETECT_DEVICE"])
def test_usb_online_inventory_reaches_backend_without_marking_ida_ready(
    monkeypatch, trigger,
) -> None:
    """周期同步和即时检测均上报 USB 在线，保持匿名标识及独立 IDA 状态。"""
    agent_id = "ios-agent-test"
    udid = "raw-usb-phone"
    device_id = hashlib.sha256(f"{agent_id}:{udid}".encode()).hexdigest()
    inventories, registry_reports = [], []

    def run(command, **_kwargs):
        """模拟隧道断开的 devicectl 设备记录。"""
        Path(command[-1]).write_text(json.dumps({"result": {"devices": [{
            "hardwareProperties": {"udid": udid, "platform": "ios"},
            "deviceProperties": {"name": "Phone"},
            "connectionProperties": {"tunnelState": "disconnected", "transportType": "wired"},
        }]}}), encoding="utf-8")
        return SimpleNamespace(returncode=0)

    def synchronize(devices):
        """捕获实际后端报告并返回目标设备端口分配。"""
        inventories.append(devices)
        return {"devices": [{"deviceId": device_id, "idaLocalPort": 8100}]}

    monkeypatch.setattr(device_detect, "_connected_usb_udids", lambda: {udid})
    monkeypatch.setattr(module, "detect_devices", lambda: device_detect.detect_devices(run))
    runtime = AgentRuntime.__new__(AgentRuntime)
    runtime.config = SimpleNamespace(agent_id=agent_id)
    runtime.ida = IdaRuntime()
    runtime.backend = SimpleNamespace(
        synchronize_devices=synchronize,
        registry_config=lambda: {
            "effectivePort": 42314, "desiredState": "OFFLINE", "configVersion": 1,
        },
        registry_status=registry_reports.append,
    )
    runtime.registry = SimpleNamespace(apply=lambda _config: {"state": "OFFLINE"})

    if trigger == "heartbeat":
        assert runtime.synchronize_devices() == 1
    else:
        assert json.loads(runtime._dispatch(trigger, {})) == {
            "synchronized": True, "deviceCount": 1,
        }

    assert len(inventories) == 1
    assert len(inventories[0]) == 1
    report = inventories[0][0]
    assert report["deviceId"] == device_id
    assert report["connected"] is True
    assert report["connectionType"] == "USB"
    assert report["status"] == "AVAILABLE"
    assert report["idaStatus"] == "UNKNOWN"
    assert report["idaRunning"] is False
    assert udid not in json.dumps(inventories)
    assert runtime.ida.ports == {device_id: 8100}
    assert runtime.ida.sessions == {}
    assert registry_reports == [{"state": "OFFLINE"}]
