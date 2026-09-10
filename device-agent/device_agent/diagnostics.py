"""Agent 运行环境的只读诊断。"""

from __future__ import annotations

import platform
import subprocess
import sys
from typing import Any, Callable


def collect_diagnostics(runner: Callable[..., Any] = subprocess.run) -> dict[str, object]:
    """仅检查 Python、macOS、Xcode 和 devicectl，不启动设备会话。"""
    checks: list[dict[str, str]] = []
    _append(checks, "PYTHON", sys.version_info[:2] == (3, 12), platform.python_version())
    _append_command(checks, "MACOS", ["/usr/bin/sw_vers", "-productVersion"], runner)
    _append_command(checks, "XCODE", ["/usr/bin/xcodebuild", "-version"], runner)
    _append_command(checks, "DEVICECTL", ["/usr/bin/xcrun", "devicectl", "--version"], runner)
    status = "FAIL" if any(item["status"] == "FAIL" for item in checks) else "PASS"
    return {"status": status, "checks": checks,
            "errorCode": None if status == "PASS" else "ENVIRONMENT_NOT_READY"}


def _append_command(checks: list[dict[str, str]], code: str, command: list[str],
                    runner: Callable[..., Any]) -> None:
    """执行固定白名单版本命令并追加脱敏结果。"""
    try:
        result = runner(command, check=False, capture_output=True, text=True, timeout=15)
        message = (result.stdout or result.stderr).splitlines()[0][:120]
        _append(checks, code, result.returncode == 0, message)
    except (OSError, subprocess.SubprocessError):
        _append(checks, code, False, "NOT_AVAILABLE")


def _append(checks: list[dict[str, str]], code: str, passed: bool, message: str) -> None:
    """追加固定字段诊断项，禁止携带任意命令日志。"""
    checks.append({"code": code, "status": "PASS" if passed else "FAIL", "message": message[:120]})
