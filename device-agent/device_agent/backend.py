"""设备 Agent 后端协议客户端。"""

from __future__ import annotations

import json
import ssl
import urllib.error
import urllib.request
from dataclasses import dataclass
from typing import Any

from .config import AgentConfig, load_secret
from .signing import signed_headers


class BackendError(RuntimeError):
    """表示后端协议、网络或认证失败。"""

    def __init__(self, code: str, status: int | None = None) -> None:
        super().__init__(code)
        self.code = code
        self.status = status


@dataclass(slots=True)
class BackendClient:
    """封装签名请求和通用设备管理协议端点。"""

    config: AgentConfig
    timeout: float = 20.0

    @classmethod
    def claim(
        cls, backend_url: str, pairing_code: str, ca_file: str | None = None,
    ) -> dict[str, Any]:
        """用一次性配对码领取 Agent 身份与独立 Secret。"""
        url = backend_url.rstrip("/") + "/api/agent/ios-device/v1/pairing/claim"
        body = json.dumps({"pairingCode": pairing_code}, separators=(",", ":")).encode()
        request = urllib.request.Request(url, data=body, method="POST", headers={
            "Content-Type": "application/json", "Accept": "application/json"})
        try:
            with urllib.request.urlopen(
                request, timeout=20, context=_ssl_context(ca_file),
            ) as response:
                return json.loads(response.read())
        except (urllib.error.URLError, ValueError) as exception:
            raise BackendError("PAIRING_FAILED", getattr(exception, "code", None)) from exception

    def request(self, method: str, path: str, payload: Any | None = None) -> Any:
        """发送一次 HMAC 签名 JSON 请求。"""
        url = self.config.backend_url.rstrip("/") + "/api/agent/ios-device/v1" + path
        body = b"" if payload is None else json.dumps(payload, separators=(",", ":")).encode()
        headers = {"Accept": "application/json", "Content-Type": "application/json"}
        headers.update(signed_headers(method, url, body, self.config.agent_id,
                                      load_secret(self.config.agent_id)))
        request = urllib.request.Request(url, data=body if method.upper() != "GET" else None,
                                         method=method.upper(), headers=headers)
        try:
            with urllib.request.urlopen(
                request, timeout=self.timeout, context=_ssl_context(self.config.ca_file),
            ) as response:
                raw = response.read()
                return None if not raw else json.loads(raw)
        except urllib.error.HTTPError as exception:
            if exception.code == 204:
                return None
            raise BackendError("BACKEND_HTTP_ERROR", exception.code) from exception
        except (urllib.error.URLError, ValueError) as exception:
            raise BackendError("BACKEND_UNAVAILABLE") from exception

    def health(self, payload: dict[str, Any]) -> None:
        """上报 Agent 健康快照。"""
        self.request("POST", "/health", payload)

    def diagnostics(self, payload: dict[str, Any]) -> None:
        """上报只读诊断结果。"""
        self.request("POST", "/diagnostics", payload)

    def synchronize_devices(self, devices: list[dict[str, Any]]) -> dict[str, Any]:
        """同步完整匿名设备快照并接收每台设备的期望 IDA 端口。"""
        result = self.request("POST", "/devices/sync", {"devices": devices})
        return result if isinstance(result, dict) else {"devices": []}

    def ida_config(self) -> dict[str, Any]:
        """拉取当前 Agent 的 IDA 签名与本地服务配置。"""
        result = self.request("GET", "/ida-config")
        if not isinstance(result, dict):
            raise BackendError("IDA_CONFIG_INVALID")
        return result

    def registry_config(self) -> dict[str, Any]:
        """拉取 Remote XPC Registry 的期望配置。"""
        result = self.request("GET", "/registry/config")
        if not isinstance(result, dict):
            raise BackendError("REGISTRY_CONFIG_INVALID")
        return result

    def registry_status(self, payload: dict[str, Any]) -> None:
        """上报不含设备标识和日志的 Registry 状态。"""
        self.request("POST", "/registry/status", payload)

    def lease_command(self, capabilities: list[str]) -> dict[str, Any] | None:
        """领取一条能力匹配的管理命令。"""
        return self.request("POST", "/commands/lease", {"capabilities": capabilities})

    def report_command(self, command_id: int, lease_token: str, status: str,
                       result_summary: str = "", error_code: str | None = None) -> None:
        """上报命令终态和稳定错误码。"""
        self.request("POST", f"/commands/{command_id}/result", {
            "leaseToken": lease_token, "status": status,
            "resultSummary": result_summary, "errorCode": error_code,
        })


def _ssl_context(ca_file: str | None) -> ssl.SSLContext:
    """创建保留系统根证书、可附加平台自签根证书的 TLS 上下文。

    cafile 参数会替换整个信任库，导致自签部署改址到公网证书地址后无法验证；
    必须先加载系统默认信任，再把平台根证书追加进去。
    """
    context = ssl.create_default_context()
    if ca_file:
        context.load_verify_locations(cafile=ca_file)
    return context
