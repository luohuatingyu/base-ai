"""使用 Xcode devicectl 只读探测本机可见 iOS 设备。"""

from __future__ import annotations

import hashlib
import json
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


@dataclass(frozen=True, slots=True)
class DeviceCandidate:
    """仅在本机内存中携带原始 UDID 的设备候选。"""

    udid: str
    name: str
    model: str
    os_version: str
    connected: bool
    connection_type: str

    def device_id(self, agent_id: str) -> str:
        """生成仅对当前 Agent 稳定的不可逆设备摘要。"""
        return hashlib.sha256(f"{agent_id}:{self.udid}".encode()).hexdigest()

    def report(self, agent_id: str) -> dict[str, object]:
        """生成不含原始 UDID 的后端只读设备快照。"""
        return {
            "deviceId": self.device_id(agent_id), "deviceName": self.name,
            "model": self.model, "platform": "iOS", "osVersion": self.os_version,
            "connected": self.connected, "connectionType": self.connection_type,
            "status": "AVAILABLE" if self.connected else "OFFLINE", "lastErrorCode": None,
        }


def detect_devices(runner: Callable[..., Any] = subprocess.run) -> list[DeviceCandidate]:
    """执行 devicectl 列表查询；该命令不安装应用、不建会话、不接管设备。"""
    with tempfile.TemporaryDirectory(prefix="base-ai-device-agent-") as directory:
        output = Path(directory) / "devices.json"
        try:
            result = runner(
                ["/usr/bin/xcrun", "devicectl", "list", "devices", "--json-output", str(output)],
                check=False, capture_output=True, text=True, timeout=30,
            )
            if result.returncode != 0 or not output.exists():
                raise RuntimeError("DEVICE_DETECTION_FAILED")
            payload = json.loads(output.read_text(encoding="utf-8"))
        except (OSError, ValueError, subprocess.SubprocessError) as exception:
            raise RuntimeError("DEVICE_DETECTION_FAILED") from exception
    devices: dict[str, DeviceCandidate] = {}
    for item in payload.get("result", {}).get("devices", []):
        candidate = _parse(item)
        if candidate is None:
            continue
        previous = devices.get(candidate.udid)
        if previous is None or (candidate.connected, candidate.connection_type == "USB") > (
            previous.connected, previous.connection_type == "USB"):
            devices[candidate.udid] = candidate
    return sorted(devices.values(), key=lambda value: (not value.connected, value.name, value.udid))[:100]


def _parse(item: Any) -> DeviceCandidate | None:
    """解析单台 iPhone/iPad，缺少硬件 UDID 时跳过。"""
    if not isinstance(item, dict):
        return None
    hardware = item.get("hardwareProperties") or {}
    device = item.get("deviceProperties") or {}
    connection = item.get("connectionProperties") or {}
    udid = str(hardware.get("udid") or "").strip()
    platform = str(hardware.get("platform") or "ios").strip().lower()
    if not udid or platform not in {"ios", "ipados"}:
        return None
    tunnel = str(connection.get("tunnelState") or "").strip().lower()
    transport = str(connection.get("transportType") or "").strip().lower()
    return DeviceCandidate(
        udid=udid, name=str(device.get("name") or "iOS Device").strip(),
        model=str(hardware.get("marketingName") or "").strip(),
        os_version=str(device.get("osVersionNumber") or "").strip(),
        connected=tunnel not in {"", "unavailable", "disconnected"},
        connection_type={"wired": "USB", "localnetwork": "WIRELESS"}.get(transport, "UNKNOWN"),
    )
