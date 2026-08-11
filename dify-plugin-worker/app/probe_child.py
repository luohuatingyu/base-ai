"""在隔离子进程中探测 Dify 插件组件是否能够真实加载。"""

from __future__ import annotations

import json
import sys
from pathlib import Path

from app.invoke_child import load_component_class


def main() -> None:
    """加载指定组件类并确认 ABI 方法，构造器留待凭据已注入的真实调用阶段。"""
    component = load_component_class(Path(sys.argv[1]).resolve(), sys.argv[2])
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
