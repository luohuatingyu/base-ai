"""Dify 插件兼容 Worker 的受鉴权 HTTP 入口。"""

from __future__ import annotations

import json
import os
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from app.internal_auth import InternalRequestVerifier
from app.sandbox_client import SandboxClient, SandboxError


TOKEN = os.getenv("PLUGIN_WORKER_INTERNAL_TOKEN", "")
AUTH = InternalRequestVerifier(TOKEN)
SANDBOX = SandboxClient(os.getenv("PLUGIN_SANDBOX_BROKER_SOCKET", "/run/plugin-sandbox/broker.sock"))
MAX_REQUEST_BYTES = int(os.getenv("PLUGIN_WORKER_MAX_REQUEST_BYTES", str(8 * 1024 * 1024)))


class Handler(BaseHTTPRequestHandler):
    """处理健康检查、包探测和插件调用。"""

    server_version = "BaseAiDifyPluginWorker/2.0"

    def do_GET(self) -> None:
        """返回不包含插件状态的健康信息。"""
        if self.path == "/health":
            healthy = SANDBOX.healthy()
            self._write(200 if healthy else 503,
                        {"status": "UP" if healthy else "DOWN", "runtime": "base-ai-python-abi", "python": "3.12"})
            return
        self._write(404, {"error": "NOT_FOUND"})

    def do_POST(self) -> None:
        """鉴权后分派包探测或短生命周期调用。"""
        try:
            body = self._request_body()
            if not AUTH.verify(self.command, self.path, body, self.headers):
                self._write(401, {"error": "UNAUTHORIZED"})
                return
            request = json.loads(body)
            if not isinstance(request, dict):
                raise ValueError("REQUEST_JSON_INVALID")
            if self.path == "/packages/inspect":
                self._write(200, SANDBOX.request("inspect", request))
            elif self.path == "/packages/remove":
                self._write(200, SANDBOX.request("remove", request))
            elif self.path == "/invocations":
                self._write(200, SANDBOX.request("invoke", request))
            else:
                self._write(404, {"error": "NOT_FOUND"})
        except SandboxError as exception:
            self._write(exception.status, {"error": exception.code})
        except ValueError as exception:
            self._write(400, {"error": str(exception)[:500]})
        except Exception:
            self._write(500, {"error": "PLUGIN_WORKER_FAILURE"})

    def log_message(self, format: str, *args: Any) -> None:
        """仅记录请求摘要，避免输出插件参数和凭据。"""
        sys.stderr.write("plugin_worker request=%s status=%s\n" % (self.path, args[1] if len(args) > 1 else ""))

    def _request_body(self) -> bytes:
        """在 JSON 解析前读取并限制签名绑定的原始请求体。"""
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_REQUEST_BYTES:
            raise ValueError("REQUEST_SIZE_INVALID")
        return self.rfile.read(length)

    def _write(self, status: int, value: dict[str, Any]) -> None:
        """写入有限 JSON 响应。"""
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    """校验内部 HMAC 密钥并启动线程化 HTTP 服务。"""
    if len(TOKEN) < 24:
        raise RuntimeError("PLUGIN_WORKER_INTERNAL_TOKEN 至少需要 24 个字符")
    port = int(os.getenv("PORT", "8101"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
