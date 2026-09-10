"""只读环境诊断测试。"""

from __future__ import annotations

from types import SimpleNamespace

from device_agent.diagnostics import collect_diagnostics


def test_diagnostics_runs_only_version_queries() -> None:
    """诊断仅调用 macOS、Xcode 和 devicectl 的版本查询。"""
    commands: list[list[str]] = []

    def runner(command, **_kwargs):
        commands.append(command)
        return SimpleNamespace(returncode=0, stdout="version 1\n", stderr="")

    result = collect_diagnostics(runner)

    assert result["status"] == "PASS"
    assert commands == [
        ["/usr/bin/sw_vers", "-productVersion"],
        ["/usr/bin/xcodebuild", "-version"],
        ["/usr/bin/xcrun", "devicectl", "--version"],
    ]
