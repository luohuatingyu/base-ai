"""结合 Xcode devicectl 与 usbmuxd 只读探测本机可见 iOS 设备。"""

from __future__ import annotations

import hashlib
import json
import plistlib
import socket
import struct
import subprocess
import tempfile
import time
from dataclasses import dataclass, replace
from pathlib import Path
from typing import Any, Callable
from xml.parsers.expat import ExpatError


USBMUX_TIMEOUT_SECONDS = 5
USBMUX_MAX_RESPONSE_BYTES = 1024 * 1024


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

    def report(self, agent_id: str, automation: dict[str, object] | None = None) -> dict[str, object]:
        """生成不含原始 UDID、但包含脱敏 IDA 状态的后端设备快照。"""
        state = automation or {}
        return {
            "deviceId": self.device_id(agent_id), "deviceName": self.name,
            "model": self.model, "platform": "iOS", "osVersion": self.os_version,
            "connected": self.connected, "connectionType": self.connection_type,
            "status": "AVAILABLE" if self.connected else "OFFLINE",
            "idaStatus": state.get("idaStatus", "UNKNOWN"),
            "idaRunning": bool(state.get("idaRunning", False)),
            "idaLocalPort": state.get("idaLocalPort"),
            "idaPortErrorCode": state.get("idaPortErrorCode"),
            "lastErrorCode": state.get("lastErrorCode"),
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
    usb_udids = _connected_usb_udids()
    devices: dict[str, DeviceCandidate] = {}
    for item in payload.get("result", {}).get("devices", []):
        candidate = _parse(item)
        if candidate is None:
            continue
        # 开发隧道可在 USB 持续连接时断开，只有本轮物理枚举可覆盖其离线结论。
        if candidate.udid in usb_udids:
            candidate = replace(candidate, connected=True, connection_type="USB")
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
    # devicectl 能枚举到无线设备即表示当前无线连接可用，不受开发隧道瞬时状态影响。
    connected = True if transport == "localnetwork" else tunnel not in {"", "unavailable", "disconnected"}
    return DeviceCandidate(
        udid=udid, name=str(device.get("name") or "iOS Device").strip(),
        model=str(hardware.get("marketingName") or "").strip(),
        os_version=str(device.get("osVersionNumber") or "").strip(),
        connected=connected,
        connection_type={"wired": "USB", "localnetwork": "WIRELESS"}.get(transport, "UNKNOWN"),
    )


def _connected_usb_udids() -> set[str]:
    """只读查询本机 USB 实连标识；失败时不提供额外在线证据，保留原有判定。"""
    request = plistlib.dumps({
        "MessageType": "ListDevices", "ClientVersionString": "base-ai-device-agent",
        "ProgName": "base-ai-device-agent", "kLibUSBMuxVersion": 3,
    })
    try:
        with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
            connection.settimeout(USBMUX_TIMEOUT_SECONDS)
            connection.connect("/var/run/usbmuxd")
            connection.sendall(struct.pack("<IIII", len(request) + 16, 1, 8, 1) + request)
            deadline = time.monotonic() + USBMUX_TIMEOUT_SECONDS
            header = _receive_exact(connection, 16, deadline)
            length, version, message, tag = struct.unpack("<IIII", header)
            if not 16 < length <= USBMUX_MAX_RESPONSE_BYTES or (version, message, tag) != (1, 8, 1):
                return set()
            payload = plistlib.loads(_receive_exact(connection, length - 16, deadline))
    except (OSError, ValueError, ExpatError):
        return set()
    if not isinstance(payload, dict) or not isinstance(payload.get("DeviceList"), list):
        return set()
    devices: set[str] = set()
    for item in payload["DeviceList"]:
        properties = item.get("Properties") if isinstance(item, dict) else None
        if not isinstance(properties, dict) or properties.get("ConnectionType") != "USB":
            continue
        udid = properties.get("SerialNumber")
        if isinstance(udid, str) and 0 < len(udid.strip()) <= 128:
            devices.add(udid.strip())
    return devices


def _receive_exact(connection: socket.socket, length: int, deadline: float) -> bytes:
    """在同一个响应截止时间内收齐分片，拒绝提前断开及持续慢速响应。"""
    result = bytearray()
    while len(result) < length:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError("USBMUX_RESPONSE_TIMEOUT")
        connection.settimeout(remaining)
        block = connection.recv(length - len(result))
        if not block:
            raise ValueError("USBMUX_RESPONSE_TRUNCATED")
        result.extend(block)
    return bytes(result)
