"""设备 Agent Apple 开发签名探测测试。"""

from __future__ import annotations

import subprocess

import pytest

from device_agent.signing_detect import detect_signing_candidates


def test_detect_signing_candidates_filters_and_extracts_team_id() -> None:
    """只返回开发证书，并从证书 OU 提取通用签名类型和团队 ID。"""
    calls: list[list[str]] = []

    def runner(command, **_kwargs):
        calls.append(command)
        if "find-identity" in command:
            return subprocess.CompletedProcess(command, 0, """
  1) AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "Apple Development: Alice (ABCDEFGHIJ)"
  2) BBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBBB "Apple Distribution: Alice (ABCDEFGHIJ)"
""", "")
        if "find-certificate" in command:
            return subprocess.CompletedProcess(command, 0, """
-----BEGIN CERTIFICATE-----
TEST
-----END CERTIFICATE-----
""", "")
        return subprocess.CompletedProcess(
            command, 0,
            "subject=CN=Alice, OU = ABCDEFGHIJ\nnotAfter=Aug 20 03:26:40 2027 GMT\n", "",
        )

    candidates = detect_signing_candidates(runner)

    assert [item.as_payload() for item in candidates] == [{
        "signingIdentity": "Apple Development",
        "teamId": "ABCDEFGHIJ",
        "label": "Apple Development: Alice (ABCDEFGHIJ)",
        "expiresAt": "2027-08-20",
    }]
    assert all(isinstance(command, list) for command in calls)


def test_detect_signing_candidates_fails_when_certificate_cannot_be_parsed() -> None:
    """发现开发身份但无法读取团队 ID 时必须明确失败，不能误报为空。"""
    def runner(command, **_kwargs):
        if "find-identity" in command:
            return subprocess.CompletedProcess(
                command, 0,
                '  1) AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA "Apple Development: Alice"\n', "",
            )
        if "find-certificate" in command:
            return subprocess.CompletedProcess(
                command, 0,
                "-----BEGIN CERTIFICATE-----\nTEST\n-----END CERTIFICATE-----\n", "",
            )
        return subprocess.CompletedProcess(command, 0, "subject=CN=Alice\n", "")

    with pytest.raises(RuntimeError, match="SIGNING_DETECT_FAILED"):
        detect_signing_candidates(runner)
