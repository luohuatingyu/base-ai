"""多设备 WDA 运行时测试。"""

from __future__ import annotations

import json
import pytest

from device_agent.device_detect import DeviceCandidate
from device_agent.wda import WdaConfig, WdaError, WdaRuntime


class Response:
    """模拟 urllib JSON 响应上下文。"""

    def __init__(self, payload: dict[str, object]) -> None:
        self.payload = payload

    def __enter__(self):
        """进入模拟 HTTP 上下文。"""
        return self

    def __exit__(self, *_values):
        """退出模拟 HTTP 上下文。"""
        return None

    def read(self) -> bytes:
        """返回 JSON 响应字节。"""
        return json.dumps(self.payload).encode()


def test_setup_uses_raw_udid_only_in_local_appium_request() -> None:
    """后端目标使用匿名 ID，只有发往本机 Appium 的能力包含原始 UDID。"""
    requests = []

    def opener(request, **_kwargs):
        requests.append(request)
        return Response({"value": {"sessionId": "session-1"}})

    runtime = WdaRuntime(opener)
    device = DeviceCandidate("raw-udid-test", "Phone", "iPhone", "18.0", True, "USB")
    device_id = device.device_id("ios-agent-test")
    runtime.refresh("ios-agent-test", [device])
    runtime.apply_assignments({"devices": [{"deviceId": device_id, "wdaLocalPort": 8100}]})

    summary = runtime.setup(device_id, WdaConfig.from_payload({
        "launchMode": "XCODEBUILD", "appiumServerUrl": "http://127.0.0.1:4723",
        "baseWdaLocalPort": 8100,
        "signingConfig": {"xcodeOrgId": "ABCDEFGHIJ", "xcodeSigningId": "Apple Development",
                          "updatedWdaBundleId": "com.example.WebDriverAgentRunner"},
    }))

    assert "安装" in summary
    assert requests[0].full_url == "http://127.0.0.1:4723/session"
    assert b"raw-udid-test" in requests[0].data
    assert runtime.report(device_id)["wdaStatus"] == "READY"
    assert runtime.report(device_id)["wdaRunning"] is False


def test_fast_operation_speed_applies_fixed_typing_frequency() -> None:
    """速度档位必须使用后端派生参数，并映射到固定 Appium 输入频率。"""
    requests = []

    def opener(request, **_kwargs):
        requests.append(request)
        return Response({"value": {"sessionId": "session-fast"}})

    runtime = WdaRuntime(opener)
    device = DeviceCandidate("fast-device", "Phone", "iPhone", "18.0", True, "USB")
    device_id = device.device_id("ios-agent-test")
    runtime.refresh("ios-agent-test", [device])
    runtime.apply_assignments({"devices": [{"deviceId": device_id, "wdaLocalPort": 8100}]})

    runtime.start(device_id, WdaConfig.from_payload({
        "operationSpeed": "FAST",
        "wirelessSourcePollIntervalSeconds": 5,
        "wirelessSourceMaxAttempts": 24,
    }))

    payload = json.loads(requests[0].data)
    assert payload["capabilities"]["alwaysMatch"]["appium:maxTypingFrequency"] == 240


def test_operation_speed_rejects_tampered_derived_values() -> None:
    """Agent 不接受与固定档位不一致的派生轮询参数。"""
    with pytest.raises(WdaError, match="WDA_CONFIG_INVALID"):
        WdaConfig.from_payload({
            "operationSpeed": "FAST",
            "wirelessSourcePollIntervalSeconds": 10,
            "wirelessSourceMaxAttempts": 12,
        })


def test_assignments_reject_duplicate_port_and_external_urls() -> None:
    """端口冲突应局部失败，外部 Appium/WDA 地址应整体拒绝。"""
    runtime = WdaRuntime()
    first = DeviceCandidate("first", "A", "iPhone", "18.0", True, "USB")
    second = DeviceCandidate("second", "B", "iPhone", "18.0", True, "USB")
    runtime.refresh("ios-agent-test", [first, second])
    first_id, second_id = first.device_id("ios-agent-test"), second.device_id("ios-agent-test")
    runtime.apply_assignments({"devices": [
        {"deviceId": first_id, "wdaLocalPort": 8100},
        {"deviceId": second_id, "wdaLocalPort": 8100},
    ]})

    assert runtime.report(second_id)["wdaPortErrorCode"] == "WDA_PORT_COLLISION"
    with pytest.raises(WdaError, match="WDA_CONFIG_INVALID"):
        WdaConfig.from_payload({"appiumServerUrl": "https://example.com:4723"})


def test_target_must_be_online_and_assigned() -> None:
    """离线、未知或尚未分配端口的目标不能触发真机控制。"""
    runtime = WdaRuntime()
    offline = DeviceCandidate("offline", "Phone", "iPhone", "18.0", False, "USB")
    runtime.refresh("ios-agent-test", [offline])

    with pytest.raises(WdaError, match="DEVICE_OFFLINE"):
        runtime.start(offline.device_id("ios-agent-test"), WdaConfig.from_payload({}))
    with pytest.raises(WdaError, match="DEVICE_NOT_FOUND"):
        runtime.start("a" * 64, WdaConfig.from_payload({}))
