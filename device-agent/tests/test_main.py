"""设备 Agent 命令生命周期测试。"""

from __future__ import annotations

from types import SimpleNamespace

from device_agent.main import AgentRuntime


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
