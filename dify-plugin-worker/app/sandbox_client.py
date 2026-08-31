"""通过来源专用 Unix Socket 调用插件 Docker 沙箱 Broker。"""

from __future__ import annotations

import http.client
import json
import os
import socket
from typing import Any


class SandboxError(RuntimeError):
    """表示 Broker 拒绝、超时或无法完成一次沙箱操作。"""

    def __init__(self, status: int, code: str) -> None:
        """保存有限 HTTP 状态和稳定错误码。"""
        super().__init__(code)
        self.status = status if 400 <= status <= 599 else 503
        self.code = code if code.replace("_", "").isalnum() and code.upper() == code else "PLUGIN_SANDBOX_UNAVAILABLE"


class UnixHTTPConnection(http.client.HTTPConnection):
    """把标准库 HTTP 客户端固定到单个 Unix Socket。"""

    def __init__(self, socket_path: str, timeout: int) -> None:
        """记录不可由请求修改的 Socket 路径。"""
        super().__init__("plugin-sandbox", timeout=timeout)
        self.socket_path = socket_path

    def connect(self) -> None:
        """只建立本机 Unix 域连接，不允许网络回退。"""
        connection = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
        connection.settimeout(self.timeout)
        connection.connect(self.socket_path)
        self.sock = connection


class SandboxClient:
    """向 Broker 发送有限 JSON，并限制响应体和错误细节。"""

    def __init__(self, socket_path: str) -> None:
        """读取调用超时与响应上限。"""
        self.socket_path = socket_path
        self.timeout = max(1, min(int(os.getenv("PLUGIN_WORKER_TIMEOUT_SECONDS", "660")), 660))
        self.maximum = max(1024, min(int(os.getenv("PLUGIN_WORKER_MAX_RESPONSE_BYTES", str(16 * 1024 * 1024))),
                                     16 * 1024 * 1024))

    def request(self, operation: str, value: dict[str, Any]) -> dict[str, Any]:
        """调用固定操作并解析 Broker 返回的单个 JSON 对象。"""
        if operation not in {"inspect", "invoke", "remove"}:
            raise SandboxError(400, "PLUGIN_SANDBOX_OPERATION_INVALID")
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        connection = UnixHTTPConnection(self.socket_path, self.timeout)
        try:
            connection.request("POST", f"/sandbox/{operation}", body,
                               {"Content-Type": "application/json", "Content-Length": str(len(body))})
            response = connection.getresponse()
            payload = response.read(self.maximum + 1)
            if len(payload) > self.maximum:
                raise SandboxError(502, "PLUGIN_SANDBOX_OUTPUT_INVALID")
            result = json.loads(payload)
            if not isinstance(result, dict):
                raise SandboxError(502, "PLUGIN_SANDBOX_OUTPUT_INVALID")
            if response.status // 100 != 2:
                raise SandboxError(response.status, str(result.get("error", "PLUGIN_SANDBOX_UNAVAILABLE"))[:80])
            return result
        except SandboxError:
            raise
        except (OSError, ValueError, json.JSONDecodeError) as exception:
            raise SandboxError(503, "PLUGIN_SANDBOX_UNAVAILABLE") from exception
        finally:
            connection.close()

    def healthy(self) -> bool:
        """验证来源专用 Broker Socket 及其 Docker Engine 可用。"""
        connection = UnixHTTPConnection(self.socket_path, min(self.timeout, 3))
        try:
            connection.request("GET", "/health")
            response = connection.getresponse()
            payload = response.read(1024)
            return response.status == 200 and json.loads(payload).get("status") == "UP"
        except (OSError, ValueError, json.JSONDecodeError):
            return False
        finally:
            connection.close()
