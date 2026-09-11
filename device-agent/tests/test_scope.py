"""通用设备 Agent 功能范围契约测试。"""

from __future__ import annotations

from device_agent.main import CAPABILITIES


def test_capabilities_include_generic_automation_and_exclude_business_actions() -> None:
    """能力白名单包含 IDA 与 Registry，但不包含账号、好友和业务任务。"""
    values = " ".join(CAPABILITIES).upper()
    assert "IDA" in values
    assert "REGISTRY" in values
    for forbidden in ("ACCOUNT", "FRIEND", "TASK_EXECUTION", "WECOM"):
        assert forbidden not in values
    assert set(CAPABILITIES) == {
        "DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING", "DETECT_DEVICE",
        "SETUP_IDA", "START_IDA", "REGISTRY_ONLINE", "REGISTRY_OFFLINE",
        "REGISTRY_RECREATE", "UPGRADE", "UPDATE_BACKEND_URL",
    }
