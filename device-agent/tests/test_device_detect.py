"""devicectl 只读设备发现测试。"""

from __future__ import annotations

import json
import plistlib
import socket
import struct
import subprocess
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock, Mock

import pytest

from device_agent import device_detect as module
from device_agent.device_detect import detect_devices


def _packet(body: bytes) -> bytes:
    """构造符合 usbmuxd plist 协议的单帧响应。"""
    return struct.pack("<IIII", len(body) + 16, 1, 8, 1) + body


def test_detection_deduplicates_and_reports_no_raw_udid(monkeypatch) -> None:
    """同一设备优先保留 USB 在线记录，后端报告只含摘要。"""
    raw_udid = "00008110-001234567890001E"
    monkeypatch.setattr(module, "_connected_usb_udids", lambda: set(), raising=False)

    def runner(command, **_kwargs):
        output = command[-1]
        payload = {
            "result": {
                "devices": [
                    _device(raw_udid, "Phone", "localNetwork", "disconnected"),
                    _device(raw_udid, "Phone", "wired", "connected"),
                    _device("ignored", "Mac", "wired", "connected", "macos"),
                ]
            }
        }
        with open(output, "w", encoding="utf-8") as stream:
            json.dump(payload, stream)
        return SimpleNamespace(returncode=0, stdout="", stderr="")

    devices = detect_devices(runner)
    report = devices[0].report("ios-agent-test")

    assert len(devices) == 1
    assert devices[0].connection_type == "USB"
    assert devices[0].connected is True
    assert report["deviceId"] != raw_udid
    assert raw_udid not in json.dumps(report)


@pytest.mark.parametrize("tunnel", ["disconnected", "unavailable", "", None])
@pytest.mark.parametrize("transport", ["wired", "localNetwork", ""])
def test_physical_usb_connection_is_online_without_developer_tunnel(
    monkeypatch, tunnel, transport,
) -> None:
    """真实 USB 实连覆盖开发隧道状态及历史接入方式，不伪造 IDA 就绪。"""
    udid = "00008110-001234567890001E"
    monkeypatch.setattr(module, "_connected_usb_udids", lambda: {udid}, raising=False)

    devices = detect_devices(_runner([_device(udid, "Phone", transport, tunnel)]))

    assert len(devices) == 1
    report = devices[0].report("ios-agent-test")
    assert report["connected"] is True
    assert report["connectionType"] == "USB"
    assert report["status"] == "AVAILABLE"
    assert report["idaStatus"] == "UNKNOWN"
    assert report["idaRunning"] is False
    assert udid not in json.dumps(report)


@pytest.mark.parametrize("usb_udids", [set(), {"other-device"}])
def test_historical_wired_device_without_usb_or_tunnel_stays_offline(
    monkeypatch, usb_udids,
) -> None:
    """历史 wired 标记或另一台设备在线均不能把已断开的目标判在线。"""
    monkeypatch.setattr(module, "_connected_usb_udids", lambda: usb_udids, raising=False)

    devices = detect_devices(_runner([_device("target", "Phone", "wired", "disconnected")]))

    assert devices[0].report("ios-agent-test")["status"] == "OFFLINE"
    assert devices[0].connected is False


@pytest.mark.parametrize(("tunnel", "connected"), [
    ("connected", True), ("connecting", True), ("disconnected", False),
    ("unavailable", False), ("", False),
])
def test_wireless_detection_keeps_existing_behavior(monkeypatch, tunnel, connected) -> None:
    """未发现真实 USB 时保持无线设备的既有隧道判定行为。"""
    monkeypatch.setattr(module, "_connected_usb_udids", lambda: set(), raising=False)

    devices = detect_devices(_runner([_device("wireless", "Tablet", "localNetwork", tunnel)]))

    assert devices[0].connected is connected
    assert devices[0].connection_type == "WIRELESS"


def test_usb_inventory_matches_only_its_device_and_deduplicates(monkeypatch) -> None:
    """多设备同时出现时按原始标识在本机匹配，USB 优先且不向报告泄漏标识。"""
    monkeypatch.setattr(module, "_connected_usb_udids", lambda: {"usb-phone"}, raising=False)

    devices = detect_devices(_runner([
        _device("usb-phone", "Phone", "localNetwork", "connected"),
        _device("usb-phone", "Phone", "wired", "disconnected"),
        _device("offline-phone", "Other", "wired", "disconnected"),
    ]))

    assert [(item.udid, item.connected, item.connection_type) for item in devices] == [
        ("usb-phone", True, "USB"), ("offline-phone", False, "USB"),
    ]
    reports = json.dumps([item.report("ios-agent-test") for item in devices])
    assert "usb-phone" not in reports
    assert "offline-phone" not in reports


@pytest.mark.parametrize("fragment_size", [1, 65536])
def test_usbmux_inventory_is_read_only_bounded_and_usb_only(monkeypatch, fragment_size) -> None:
    """解析分片响应并过滤网络记录、非法条目，只发送一次 ListDevices 查询。"""
    payload = {"DeviceList": [
        {"Properties": {"ConnectionType": "USB", "SerialNumber": " raw-usb "}},
        {"Properties": {"ConnectionType": "USB", "SerialNumber": "raw-usb"}},
        {"Properties": {"ConnectionType": "Network", "SerialNumber": "network-only"}},
        {"Properties": {"ConnectionType": "USB", "SerialNumber": "x" * 128}},
        {"Properties": {"ConnectionType": "USB", "SerialNumber": "x" * 129}},
        {"Properties": {"ConnectionType": "USB", "SerialNumber": ""}},
        {"Properties": {"ConnectionType": "USB", "SerialNumber": " "}},
        {"Properties": {"ConnectionType": "USB", "SerialNumber": 123}},
        {"Properties": {"ConnectionType": "USB"}},
        {"Properties": []}, {}, "invalid",
    ]}
    connection, factory = _socket(monkeypatch, _packet(plistlib.dumps(payload)), fragment_size)

    assert module._connected_usb_udids() == {"raw-usb", "x" * 128}

    factory.assert_called_once_with(socket.AF_UNIX, socket.SOCK_STREAM)
    connection.connect.assert_called_once_with("/var/run/usbmuxd")
    connection.sendall.assert_called_once()
    request = connection.sendall.call_args.args[0]
    assert struct.unpack("<IIII", request[:16]) == (len(request), 1, 8, 1)
    assert plistlib.loads(request[16:]) == {
        "MessageType": "ListDevices", "ClientVersionString": "base-ai-device-agent",
        "ProgName": "base-ai-device-agent", "kLibUSBMuxVersion": 3,
    }
    assert all(0 < call.args[0] <= 5 for call in connection.settimeout.call_args_list)
    connection.__exit__.assert_called_once()


@pytest.mark.parametrize("payload", [
    [], {}, {"Number": 0}, {"DeviceList": "invalid"}, {"DeviceList": {}},
    {"DeviceList": []},
])
def test_missing_or_empty_usbmux_inventory_has_no_usb_evidence(monkeypatch, payload) -> None:
    """空清单或不符合协议的结构不得构造 USB 在线证据。"""
    _socket(monkeypatch, _packet(plistlib.dumps(payload)))

    assert module._connected_usb_udids() == set()


@pytest.mark.parametrize("response", [
    b"",
    b"\x00" * 8,
    struct.pack("<IIII", 0, 1, 8, 1),
    struct.pack("<IIII", 16, 1, 8, 1),
    struct.pack("<IIII", 1024 * 1024 + 1, 1, 8, 1),
    struct.pack("<IIII", 17, 0, 8, 1) + b"x",
    struct.pack("<IIII", 17, 1, 0, 1) + b"x",
    struct.pack("<IIII", 17, 1, 8, 2) + b"x",
    struct.pack("<IIII", 50, 1, 8, 1) + b"truncated",
    _packet(b"not a plist"),
    _packet(b'<?xml version="1.0"?><plist><dict>'),
])
def test_usbmux_rejects_truncated_oversized_or_malformed_responses(monkeypatch, response) -> None:
    """异常头部、响应截断、超限与非法 plist 均安全降级。"""
    connection, _ = _socket(monkeypatch, response)

    assert module._connected_usb_udids() == set()
    assert all(call.args[0] <= 1024 * 1024 for call in connection.recv.call_args_list)
    connection.__exit__.assert_called_once()


def test_usbmux_accepts_maximum_valid_response(monkeypatch) -> None:
    """响应大小恰好达到上限时仍可读取有效 USB 枚举。"""
    body = plistlib.dumps({"DeviceList": [
        {"Properties": {"ConnectionType": "USB", "SerialNumber": "usb-phone"}},
    ]}).ljust(1024 * 1024 - 16, b" ")
    _socket(monkeypatch, _packet(body))

    assert module._connected_usb_udids() == {"usb-phone"}


@pytest.mark.parametrize("operation", ["socket", "connect", "sendall", "recv"])
@pytest.mark.parametrize("error_type", [FileNotFoundError, PermissionError, TimeoutError])
def test_usb_query_errors_preserve_existing_device_detection(
    monkeypatch, operation, error_type,
) -> None:
    """本机 Socket 不可用、无权限或超时不阻断原有发现，也不能伪造目标在线。"""
    connection, factory = _socket(monkeypatch, b"")
    target = factory if operation == "socket" else getattr(connection, operation)
    target.side_effect = error_type("local socket unavailable")

    devices = detect_devices(_runner([
        _device("offline", "Phone", "wired", "disconnected"),
        _device("wireless", "Tablet", "localNetwork", "connected"),
    ]))

    assert [(item.udid, item.connected, item.connection_type) for item in devices] == [
        ("wireless", True, "WIRELESS"), ("offline", False, "USB"),
    ]


def test_usbmux_slow_fragments_share_one_response_deadline(monkeypatch) -> None:
    """分片持续到达也不能重新开始超时窗口并拖住 Agent 心跳。"""
    connection, _ = _socket(monkeypatch, _packet(plistlib.dumps({"DeviceList": []})), 1)
    monkeypatch.setattr(module.time, "monotonic", Mock(side_effect=[10, 10, 16]))

    assert module._connected_usb_udids() == set()
    assert connection.recv.call_count == 1


@pytest.mark.parametrize("mode", ["exit", "missing", "json", "os-error", "timeout"])
def test_devicectl_failures_keep_existing_error_code(monkeypatch, mode) -> None:
    """新增 USB 查询不能掩盖 devicectl 失败或上报不完整的空设备快照。"""
    usb_detector = Mock(return_value={"target"})
    monkeypatch.setattr(module, "_connected_usb_udids", usb_detector)

    def run(command, **_kwargs):
        """模拟设备列表命令的各类外部错误。"""
        if mode == "os-error":
            raise OSError("unavailable")
        if mode == "timeout":
            raise subprocess.TimeoutExpired(command, 30)
        if mode == "json":
            Path(command[-1]).write_text("{", encoding="utf-8")
        return SimpleNamespace(returncode=1 if mode == "exit" else 0)

    with pytest.raises(RuntimeError, match="^DEVICE_DETECTION_FAILED$"):
        detect_devices(run)
    usb_detector.assert_not_called()


def test_invalid_candidates_are_ignored_and_inventory_remains_capped(monkeypatch) -> None:
    """USB 证据不绕过有效硬件标识、平台过滤和最多一百台的设备池边界。"""
    monkeypatch.setattr(module, "_connected_usb_udids", lambda: {"mac", ""})

    assert detect_devices(_runner([
        None, _device("", "Missing", "wired", "disconnected"),
        _device("mac", "Mac", "wired", "connected", "macos"),
    ])) == []
    devices = detect_devices(_runner([
        _device(f"phone-{index:03}", f"Phone {index:03}", "wired", "connected")
        for index in range(101)
    ]))
    assert len(devices) == 100
    assert devices[-1].udid == "phone-099"


def _socket(monkeypatch, response: bytes, fragment_size: int = 65536):
    """仅替代外部 Unix Socket，保留真实帧读取、校验与 plist 解析。"""
    pending = bytearray(response)
    connection = MagicMock()
    connection.__enter__.return_value = connection

    def receive(length: int) -> bytes:
        """按请求长度和指定分片大小返回响应，耗尽后模拟关闭连接。"""
        size = min(length, fragment_size)
        result = bytes(pending[:size])
        del pending[:size]
        return result

    connection.recv.side_effect = receive
    factory = Mock(return_value=connection)
    monkeypatch.setattr(module.socket, "socket", factory)
    return connection, factory


def _runner(devices):
    """构造只替代外部 devicectl 的执行器，保留真实解析与判定逻辑。"""
    def run(command, **_kwargs):
        """将指定设备快照写入查询命令要求的临时输出位置。"""
        Path(command[-1]).write_text(json.dumps({"result": {"devices": devices}}), encoding="utf-8")
        return SimpleNamespace(returncode=0)
    return run


def _device(udid: str, name: str, transport: str, tunnel: str,
            platform: str = "ios") -> dict[str, object]:
    """构造 devicectl 返回的单台测试设备。"""
    return {
        "hardwareProperties": {
            "udid": udid,
            "platform": platform,
            "marketingName": "iPhone 16",
        },
        "deviceProperties": {"name": name, "osVersionNumber": "18.0"},
        "connectionProperties": {"transportType": transport, "tunnelState": tunnel},
    }
