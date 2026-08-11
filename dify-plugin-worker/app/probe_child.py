"""在隔离子进程中探测 Dify 插件组件是否能够真实加载。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from app.invoke_child import load_component


def main() -> None:
    """加载并实例化指定组件，同时确认目标类型存在可调用 ABI 方法。"""
    component = load_component(Path(sys.argv[1]).resolve(), sys.argv[2])
    component_type = sys.argv[3]
    methods = {
        "TOOL": ("_invoke", "invoke"), "MODEL": ("_invoke", "invoke"),
        "AGENT_STRATEGY": ("_invoke", "invoke"), "DATASOURCE": ("_invoke", "invoke"),
        "TRIGGER": ("_subscribe", "subscribe", "_refresh", "refresh"),
        "EXTENSION": ("_invoke", "invoke", "_get_schema", "get_schema", "_get_authorization_url",
                      "get_authorization_url"),
    }.get(component_type, ())
    if not any(callable(getattr(component, name, None)) for name in methods):
        raise ValueError("PLUGIN_ABI_METHOD_MISSING")
    sys.stdout.write(json.dumps({"success": True}))


if __name__ == "__main__":
    try:
        main()
    except Exception as exception:
        sys.stdout.write(json.dumps({"success": False, "error": str(exception)[:300]}))
        sys.exit(1)
