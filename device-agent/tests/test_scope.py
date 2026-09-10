"""通用设备 Agent 功能范围契约测试。"""

from __future__ import annotations

from device_agent.main import CAPABILITIES


def test_capabilities_exclude_device_and_business_automation() -> None:
    """能力白名单只允许诊断、发现和 Agent 自维护。"""
    values = " ".join(CAPABILITIES).upper()
    for forbidden in ("WDA", "APPIUM", "ACCOUNT", "FRIEND", "TASK_EXECUTION"):
        assert forbidden not in values
    assert set(CAPABILITIES) == {
        "DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING", "DETECT_DEVICE",
        "UPGRADE", "UPDATE_BACKEND_URL",
    }
