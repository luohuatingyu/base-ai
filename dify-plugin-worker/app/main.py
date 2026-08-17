"""Dify 插件兼容 Worker 的受鉴权 HTTP 入口。"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import Any

from app.package_store import PackageError, PackageStore


STORE = PackageStore()
TOKEN = os.getenv("PLUGIN_WORKER_INTERNAL_TOKEN", "")
MAX_REQUEST_BYTES = int(os.getenv("PLUGIN_WORKER_MAX_REQUEST_BYTES", str(8 * 1024 * 1024)))
TIMEOUT_SECONDS = int(os.getenv("PLUGIN_INVOCATION_TIMEOUT_SECONDS", "60"))


class Handler(BaseHTTPRequestHandler):
    """处理健康检查、包探测和插件调用。"""

    server_version = "BaseAiDifyPluginWorker/1.0"

    def do_GET(self) -> None:
        """返回不包含插件状态的健康信息。"""
        if self.path == "/health":
            self._write(200, {"status": "UP", "runtime": "base-ai-python-abi", "python": "3.12"})
            return
        self._write(404, {"error": "NOT_FOUND"})

    def do_POST(self) -> None:
        """鉴权后分派包探测或短生命周期调用。"""
        if not TOKEN or self.headers.get("X-Internal-Token") != TOKEN:
            self._write(401, {"error": "UNAUTHORIZED"})
            return
        try:
            request = self._request()
            if self.path == "/packages/inspect":
                self._write(200, STORE.install(request))
            elif self.path == "/packages/remove":
                self._write(200, STORE.remove(str(request.get("fingerprint", ""))))
            elif self.path == "/invocations":
                self._write(200, self._invoke(request))
            else:
                self._write(404, {"error": "NOT_FOUND"})
        except PackageError as exception:
            self._write(400, {"error": str(exception)})
        except TimeoutError as exception:
            self._write(504, {"error": str(exception)})
        except ValueError as exception:
            self._write(400, {"error": str(exception)[:500]})
        except Exception:
            self._write(500, {"error": "PLUGIN_WORKER_FAILURE"})

    def log_message(self, format: str, *args: Any) -> None:
        """仅记录请求摘要，避免输出插件参数和凭据。"""
        sys.stderr.write("plugin_worker request=%s status=%s\n" % (self.path, args[1] if len(args) > 1 else ""))

    def _request(self) -> dict[str, Any]:
        """读取并限制 JSON 请求体。"""
        length = int(self.headers.get("Content-Length", "0"))
        if length <= 0 or length > MAX_REQUEST_BYTES:
            raise ValueError("REQUEST_SIZE_INVALID")
        value = json.loads(self.rfile.read(length))
        if not isinstance(value, dict):
            raise ValueError("REQUEST_JSON_INVALID")
        return value

    def _invoke(self, request: dict[str, Any]) -> dict[str, Any]:
        """在不继承内部密钥的子进程中执行一次插件调用。"""
        root, metadata = STORE.metadata(str(request.get("fingerprint", "")))
        external_id = str(request.get("componentId", ""))
        component = next((item for item in metadata["components"] if item["externalId"] == external_id), None)
        if component is None or component["compatibilityStatus"] == "UNSUPPORTED":
            raise ValueError("PLUGIN_COMPONENT_UNSUPPORTED")
        child_request = dict(request)
        child_request["root"] = str(root)
        child_request["sourcePath"] = component["sourcePath"]
        child_request["componentType"] = component.get("componentType", "")
        child_request["modelType"] = component.get("modelType", "")
        environment = {
            "PATH": os.getenv("PATH", ""), "PYTHONPATH": "/app", "PYTHONDONTWRITEBYTECODE": "1",
            "PYTHONUNBUFFERED": "1", "LANG": "C.UTF-8",
            "HTTP_PROXY": os.getenv("HTTP_PROXY", ""), "HTTPS_PROXY": os.getenv("HTTPS_PROXY", ""),
            "NO_PROXY": os.getenv("NO_PROXY", ""),
        }
        try:
            result = subprocess.run(
                [sys.executable, "-m", "app.invoke_child"], input=json.dumps(child_request), text=True,
                capture_output=True, timeout=max(1, min(TIMEOUT_SECONDS, 300)), env=environment, check=False,
            )
        except subprocess.TimeoutExpired as exception:
            raise TimeoutError("PLUGIN_INVOCATION_TIMEOUT") from exception
        try:
            response = json.loads(result.stdout)
        except Exception as exception:
            raise ValueError("PLUGIN_OUTPUT_INVALID") from exception
        if result.returncode != 0 or not response.get("success"):
            raise ValueError(str(response.get("error") or "PLUGIN_INVOCATION_FAILED"))
        return response

    def _write(self, status: int, value: dict[str, Any]) -> None:
        """写入有限 JSON 响应。"""
        body = json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", "application/json; charset=utf-8")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)


def main() -> None:
    """校验内部令牌并启动线程化 HTTP 服务。"""
    if len(TOKEN) < 24:
        raise RuntimeError("PLUGIN_WORKER_INTERNAL_TOKEN 至少需要 24 个字符")
    port = int(os.getenv("PORT", "8101"))
    ThreadingHTTPServer(("0.0.0.0", port), Handler).serve_forever()


if __name__ == "__main__":
    main()
