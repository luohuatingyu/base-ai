"""在一次性 Docker 容器中安装、探测或调用单个 Dify 插件。"""

from __future__ import annotations

import json
import os
import subprocess
import sys
from typing import Any

from app.package_store import PackageError, PackageStore


def invoke(store: PackageStore, request: dict[str, Any]) -> dict[str, Any]:
    """从当前指纹独占卷读取组件并在无长期凭据的子进程中调用。"""
    root, metadata = store.metadata(str(request.get("fingerprint", "")))
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
        "PYTHONUNBUFFERED": "1", "LANG": "C.UTF-8", "HTTP_PROXY": os.getenv("HTTP_PROXY", ""),
        "HTTPS_PROXY": os.getenv("HTTPS_PROXY", ""), "NO_PROXY": os.getenv("NO_PROXY", ""),
    }
    timeout = max(1, min(int(os.getenv("PLUGIN_INVOCATION_TIMEOUT_SECONDS", "60")), 300))
    try:
        result = subprocess.run([sys.executable, "-m", "app.invoke_child"], input=json.dumps(child_request),
                                text=True, capture_output=True, timeout=timeout, env=environment, check=False)
    except subprocess.TimeoutExpired as exception:
        raise TimeoutError("PLUGIN_INVOCATION_TIMEOUT") from exception
    try:
        response = json.loads(result.stdout)
    except Exception as exception:
        raise ValueError("PLUGIN_OUTPUT_INVALID") from exception
    if result.returncode != 0 or not response.get("success"):
        raise ValueError(str(response.get("error") or "PLUGIN_INVOCATION_FAILED"))
    return response


def main() -> None:
    """读取 Broker 标准输入并执行命令行固定的单一沙箱操作。"""
    if len(sys.argv) != 2 or sys.argv[1] not in {"inspect", "invoke"}:
        raise ValueError("PLUGIN_SANDBOX_OPERATION_INVALID")
    request = json.loads(sys.stdin.read())
    if not isinstance(request, dict):
        raise ValueError("REQUEST_JSON_INVALID")
    store = PackageStore()
    result = store.install(request) if sys.argv[1] == "inspect" else invoke(store, request)
    sys.stdout.write(json.dumps(result, ensure_ascii=False, separators=(",", ":")))


if __name__ == "__main__":
    try:
        main()
    except (PackageError, TimeoutError, ValueError) as exception:
        sys.stdout.write(json.dumps({"error": str(exception)[:500]}, ensure_ascii=False))
        sys.exit(1)
    except Exception:
        sys.stdout.write(json.dumps({"error": "PLUGIN_SANDBOX_FAILURE"}))
        sys.exit(1)
