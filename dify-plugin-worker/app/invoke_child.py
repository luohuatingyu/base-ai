"""在无内部密钥的短生命周期子进程中调用 Python 插件。"""

from __future__ import annotations

import importlib
import importlib.util
import inspect
import json
import sys
import types
from pathlib import Path
from typing import Any

from app.abi import PluginComponent, Runtime, install_modules, normalize_output


def load_component(root: Path, source_path: str) -> PluginComponent:
    """从已校验包目录加载唯一插件组件类。"""
    source = (root / source_path).resolve()
    if root not in source.parents or not source.is_file():
        raise ValueError("PYTHON_SOURCE_MISSING")
    install_modules()
    sys.path.insert(0, str(root))
    dependencies = root / ".deps"
    if dependencies.is_dir():
        sys.path.insert(1, str(dependencies))
    relative = source.relative_to(root).with_suffix("")
    module_parts = list(relative.parts)
    if source.suffix == ".py" and module_parts and all(part.isidentifier() for part in module_parts):
        # 使用隔离的合成根包保留目录包语义，使 .helper 等相对导入只落在当前插件目录。
        package_name = "_base_ai_plugin"
        package = types.ModuleType(package_name)
        package.__path__ = [str(root)]
        package.__package__ = package_name
        sys.modules[package_name] = package
        module = importlib.import_module(package_name + "." + ".".join(module_parts))
    else:
        specification = importlib.util.spec_from_file_location("base_ai_plugin_component", source)
        if specification is None or specification.loader is None:
            raise ValueError("PYTHON_SOURCE_INVALID")
        module = importlib.util.module_from_spec(specification)
        specification.loader.exec_module(module)
    candidates = [value for value in vars(module).values() if inspect.isclass(value)
                  and issubclass(value, PluginComponent) and value is not PluginComponent
                  and value.__module__ == module.__name__]
    if not candidates:
        raise ValueError("PYTHON_COMPONENT_CLASS_MISSING")
    return candidates[0]()


def invoke_method(component: PluginComponent, operation: str, payload: dict[str, Any]) -> Any:
    """按受限操作名选择插件方法并匹配公开参数。"""
    allowed = {
        "invoke": ["_invoke", "invoke"],
        "validate_credentials": ["_validate_credentials", "validate_credentials"],
        "subscribe": ["_subscribe", "subscribe"],
        "unsubscribe": ["_unsubscribe", "unsubscribe"],
        "refresh": ["_refresh", "refresh"],
        "dispatch_event": ["_dispatch_event", "dispatch_event", "_invoke_event"],
        "oauth_authorize": ["_get_authorization_url", "get_authorization_url"],
        "oauth_exchange": ["_get_credentials", "get_credentials"],
        "schema": ["_get_schema", "get_schema"],
    }
    names = allowed.get(operation)
    if names is None:
        raise ValueError("PLUGIN_OPERATION_INVALID")
    method = next((getattr(component, name) for name in names if callable(getattr(component, name, None))), None)
    if method is None:
        raise ValueError("PLUGIN_ABI_METHOD_MISSING")
    parameters = payload.get("parameters") if isinstance(payload.get("parameters"), dict) else {}
    credentials = payload.get("credentials") if isinstance(payload.get("credentials"), dict) else {}
    context = payload.get("context") if isinstance(payload.get("context"), dict) else {}
    component.runtime = Runtime(credentials, context)
    available = {
        "tool_parameters": parameters, "parameters": parameters, "credentials": credentials,
        "user_id": str(context.get("userId", "")), "context": context,
        "event": payload.get("event", {}), "payload": payload.get("event", {}),
        "redirect_uri": payload.get("redirectUri", ""), "code": payload.get("code", ""),
        "state": payload.get("state", ""), "code_verifier": payload.get("codeVerifier", ""),
    }
    signature = inspect.signature(method)
    kwargs = {name: available[name] for name in signature.parameters if name in available}
    result = method(**kwargs)
    if inspect.isgenerator(result):
        result = list(result)
    return normalize_output(result)


def main() -> None:
    """从标准输入读取一次调用并只向标准输出写 JSON。"""
    request = json.loads(sys.stdin.read())
    component = load_component(Path(request["root"]).resolve(), request["sourcePath"])
    output = invoke_method(component, request.get("operation", "invoke"), request)
    sys.stdout.write(json.dumps({"success": True, "output": output}, ensure_ascii=False))


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        sys.stdout.write(json.dumps({"success": False, "error": str(exception)[:500]}, ensure_ascii=False))
        sys.exit(1)
