"""devicectl 只读设备发现测试。"""

from __future__ import annotations

import json
from types import SimpleNamespace

from device_agent.device_detect import detect_devices


def test_detection_deduplicates_and_reports_no_raw_udid() -> None:
    """同一设备优先保留 USB 在线记录，后端报告只含摘要。"""
    raw_udid = "00008110-001234567890001E"

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
