"""多设备 WebDriverAgent 安装、启动和 Appium 会话管理。"""

from __future__ import annotations

import json
import subprocess
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any, Callable
from urllib.parse import urlsplit

from .device_detect import DeviceCandidate


class WdaError(RuntimeError):
    """表示 WDA 配置、目标设备或 Appium 协议失败。"""


@dataclass(frozen=True, slots=True)
class WdaConfig:
    """后端下发的通用 WDA 配置。"""

    launch_mode: str
    wda_url: str | None
    appium_url: str
    base_port: int
    xcode_org_id: str | None
    xcode_signing_id: str | None
    bundle_id: str | None
    allow_registration: bool
    operation_speed: str
    wireless_source_poll_interval_seconds: int
    wireless_source_max_attempts: int

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "WdaConfig":
        """严格解析配置，禁止把非回环服务地址传给自动化客户端。"""
        signing = payload.get("signingConfig") or {}
        launch_mode = str(payload.get("launchMode") or "XCODEBUILD").upper()
        appium_url = str(payload.get("appiumServerUrl") or "http://127.0.0.1:4723").rstrip("/")
        wda_url = str(payload.get("wdaUrl") or "").rstrip("/") or None
        try:
            base_port = int(payload.get("baseWdaLocalPort") or 8100)
            wireless_interval = int(payload.get("wirelessSourcePollIntervalSeconds") or 10)
            wireless_attempts = int(payload.get("wirelessSourceMaxAttempts") or 12)
        except (TypeError, ValueError) as exception:
            raise WdaError("WDA_CONFIG_INVALID") from exception
        operation_speed = str(payload.get("operationSpeed") or "STANDARD").upper()
        if launch_mode not in {"XCODEBUILD", "PREINSTALLED", "URL"}:
            raise WdaError("WDA_CONFIG_INVALID")
        if (not _loopback_http(appium_url) or not 1024 <= base_port <= 65535
                or operation_speed not in OPERATION_SPEED_PROFILES):
            raise WdaError("WDA_CONFIG_INVALID")
        expected = OPERATION_SPEED_PROFILES[operation_speed]
        if (wireless_interval, wireless_attempts) != (
                expected.wireless_poll_interval_seconds, expected.wireless_max_attempts):
            raise WdaError("WDA_CONFIG_INVALID")
        if launch_mode == "URL" and (wda_url is None or not _loopback_http(wda_url)):
            raise WdaError("WDA_CONFIG_INVALID")
        return cls(
            launch_mode, wda_url, appium_url, base_port,
            _optional(signing.get("xcodeOrgId")), _optional(signing.get("xcodeSigningId")),
            _optional(signing.get("updatedWdaBundleId")),
            bool(signing.get("allowProvisioningDeviceRegistration", False)),
            operation_speed, wireless_interval, wireless_attempts,
        )


@dataclass(frozen=True, slots=True)
class OperationSpeedProfile:
    """通用 Appium 动作、滑动、输入和页面采样的固定节奏。"""

    action_interval_seconds: float
    swipe_interval_seconds: float
    typing_frequency: int
    wireless_poll_interval_seconds: int
    wireless_max_attempts: int


OPERATION_SPEED_PROFILES = {
    "SLOW": OperationSpeedProfile(2.0, 3.0, 60, 15, 8),
    "STANDARD": OperationSpeedProfile(1.0, 2.0, 120, 10, 12),
    "FAST": OperationSpeedProfile(0.5, 1.0, 240, 5, 24),
}


class WdaRuntime:
    """维护匿名设备到本机候选、期望端口和 Appium 会话的映射。"""

    def __init__(self, opener: Callable[..., Any] = urllib.request.urlopen) -> None:
        self.opener = opener
        self.devices: dict[str, DeviceCandidate] = {}
        self.ports: dict[str, int] = {}
        self.states: dict[str, dict[str, object]] = {}
        self.sessions: dict[str, tuple[str, str]] = {}

    def refresh(self, agent_id: str, devices: list[DeviceCandidate]) -> None:
        """只在内存中刷新匿名 ID 与原始 UDID 的映射。"""
        self.devices = {device.device_id(agent_id): device for device in devices}
        for device_id in self.devices:
            self.states.setdefault(device_id, {
                "wdaStatus": "UNKNOWN", "wdaRunning": False,
                "wdaLocalPort": self.ports.get(device_id), "wdaPortErrorCode": None,
                "lastErrorCode": None,
            })
        for device_id in list(self.states):
            if device_id not in self.devices:
                self.close(device_id)
                self.states.pop(device_id, None)

    def apply_assignments(self, payload: dict[str, Any]) -> None:
        """应用后端端口分配，冲突或非法响应只标记目标设备而不占用端口。"""
        items = payload.get("devices") if isinstance(payload, dict) else []
        desired: dict[str, int] = {}
        for item in items if isinstance(items, list) else []:
            if not isinstance(item, dict):
                continue
            device_id = str(item.get("deviceId") or "").lower()
            try:
                port = int(item.get("wdaLocalPort"))
            except (TypeError, ValueError):
                continue
            if device_id in self.devices and 1024 <= port <= 65535:
                desired[device_id] = port
        used: set[int] = set()
        for device_id, port in desired.items():
            state = self.states[device_id]
            if port in used:
                state.update({"wdaStatus": "ERROR", "wdaRunning": False,
                              "wdaPortErrorCode": "WDA_PORT_COLLISION"})
                continue
            used.add(port)
            if self.ports.get(device_id) != port:
                self.close(device_id)
            self.ports[device_id] = port
            state["wdaLocalPort"] = port
            state["wdaPortErrorCode"] = None

    def report(self, device_id: str) -> dict[str, object]:
        """返回单设备可安全上报的 WDA 状态。"""
        return dict(self.states.get(device_id, {}))

    def setup(self, device_id: str, config: WdaConfig) -> str:
        """建立一次受控 Appium 会话，以完成 WDA 构建、签名和安装验证。"""
        candidate, port = self._target(device_id)
        self.close(device_id)
        session_id = self._create_session(candidate, port, config, setup=True)
        self._delete_session(config.appium_url, session_id)
        self.states[device_id].update({
            "wdaStatus": "READY", "wdaRunning": False,
            "wdaPortErrorCode": None, "lastErrorCode": None,
        })
        return "WebDriverAgent 已完成构建、签名、安装和连接验证"

    def start(self, device_id: str, config: WdaConfig) -> str:
        """启动或复用预装 WDA，并保留 Appium 会话维持运行。"""
        candidate, port = self._target(device_id)
        self.close(device_id)
        session_id = self._create_session(candidate, port, config, setup=False)
        self.sessions[device_id] = (config.appium_url, session_id)
        self.states[device_id].update({
            "wdaStatus": "READY", "wdaRunning": True,
            "wdaPortErrorCode": None, "lastErrorCode": None,
        })
        return "WebDriverAgent 已启动并通过 Appium 会话验证"

    def fail(self, device_id: str, code: str) -> None:
        """记录单设备稳定错误码，避免泄漏 Appium 或 Xcode 原始输出。"""
        if device_id in self.states:
            self.states[device_id].update({
                "wdaStatus": "ERROR", "wdaRunning": False,
                "lastErrorCode": code[:64],
            })

    def close(self, device_id: str) -> None:
        """幂等关闭一台设备的 Appium 会话。"""
        session = self.sessions.pop(device_id, None)
        if session is not None:
            try:
                self._delete_session(*session)
            except WdaError:
                pass
        if device_id in self.states:
            self.states[device_id]["wdaRunning"] = False

    def close_all(self) -> None:
        """进程退出前关闭全部受管会话。"""
        for device_id in list(self.sessions):
            self.close(device_id)

    def _target(self, device_id: str) -> tuple[DeviceCandidate, int]:
        """查找在线目标和有效期望端口。"""
        candidate = self.devices.get(str(device_id or "").lower())
        port = self.ports.get(str(device_id or "").lower())
        if candidate is None:
            raise WdaError("DEVICE_NOT_FOUND")
        if not candidate.connected:
            raise WdaError("DEVICE_OFFLINE")
        if port is None:
            raise WdaError("WDA_PORT_NOT_ASSIGNED")
        return candidate, port

    def _create_session(self, candidate: DeviceCandidate, port: int,
                        config: WdaConfig, setup: bool) -> str:
        """使用 W3C 能力创建只控制 WDA 本身的 XCUITest 会话。"""
        capabilities: dict[str, object] = {
            "platformName": "iOS", "appium:automationName": "XCUITest",
            "appium:udid": candidate.udid, "appium:deviceName": candidate.name,
            "appium:platformVersion": candidate.os_version, "appium:wdaLocalPort": port,
            "appium:autoLaunch": False, "appium:newCommandTimeout": 3600,
            "appium:showXcodeLog": False,
            "appium:maxTypingFrequency":
                OPERATION_SPEED_PROFILES[config.operation_speed].typing_frequency,
        }
        if config.launch_mode == "URL":
            capabilities["appium:webDriverAgentUrl"] = config.wda_url
        elif setup and config.launch_mode == "XCODEBUILD":
            capabilities.update(_signing_capabilities(config))
            capabilities["appium:useNewWDA"] = True
        else:
            capabilities["appium:usePreinstalledWDA"] = True
            if config.bundle_id:
                capabilities["appium:updatedWDABundleId"] = config.bundle_id
        response = self._json_request(config.appium_url + "/session", "POST", {
            "capabilities": {"alwaysMatch": capabilities, "firstMatch": [{}]},
        }, timeout=720 if setup else 180)
        session_id = response.get("sessionId") or (response.get("value") or {}).get("sessionId")
        if not isinstance(session_id, str) or not session_id:
            raise WdaError("APPIUM_SESSION_INVALID")
        return session_id

    def _delete_session(self, appium_url: str, session_id: str) -> None:
        """释放指定 Appium 会话。"""
        self._json_request(f"{appium_url}/session/{session_id}", "DELETE", None, timeout=30)

    def _json_request(self, url: str, method: str, payload: dict[str, Any] | None,
                      timeout: float) -> dict[str, Any]:
        """调用本机 Appium JSON 接口并折叠外部错误细节。"""
        body = None if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        request = urllib.request.Request(url, data=body, method=method,
                                         headers={"Content-Type": "application/json"})
        try:
            with self.opener(request, timeout=timeout) as response:
                raw = response.read()
                result = {} if not raw else json.loads(raw)
        except (OSError, TimeoutError, ValueError, urllib.error.URLError) as exception:
            raise WdaError("APPIUM_UNAVAILABLE") from exception
        if not isinstance(result, dict):
            raise WdaError("APPIUM_RESPONSE_INVALID")
        value = result.get("value")
        if isinstance(value, dict) and value.get("error"):
            raise WdaError("WDA_OPERATION_FAILED")
        return result


def xcuitest_driver_version(runner: Callable[..., Any] = subprocess.run) -> str | None:
    """读取固定 Appium Home 中已安装的 XCUITest driver 版本。"""
    try:
        result = runner(["appium", "driver", "list", "--installed", "--json"],
                        check=False, capture_output=True, text=True, timeout=30)
        payload = json.loads(result.stdout or "{}")
        driver = payload.get("xcuitest") if isinstance(payload, dict) else None
        return str((driver or {}).get("version") or "") or None
    except (OSError, ValueError, subprocess.SubprocessError):
        return None


def _signing_capabilities(config: WdaConfig) -> dict[str, object]:
    """生成 Appium 官方 XCUITest 签名能力。"""
    values: dict[str, object] = {}
    if config.xcode_org_id and config.xcode_signing_id:
        values["appium:xcodeOrgId"] = config.xcode_org_id
        values["appium:xcodeSigningId"] = config.xcode_signing_id
    if config.bundle_id:
        values["appium:updatedWDABundleId"] = config.bundle_id
    if config.allow_registration:
        values["appium:allowProvisioningDeviceRegistration"] = True
    return values


def _loopback_http(value: str) -> bool:
    """判断地址是否为无凭据的 HTTP 回环地址。"""
    try:
        parsed = urlsplit(value)
        return (parsed.scheme in {"http", "https"} and parsed.hostname in {"127.0.0.1", "localhost", "::1"}
                and parsed.username is None and parsed.password is None
                and parsed.query == "" and parsed.fragment == "")
    except ValueError:
        return False


def _optional(value: object) -> str | None:
    """把可选字段规范为非空字符串。"""
    normalized = str(value or "").strip()
    return normalized or None
