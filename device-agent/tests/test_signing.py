"""设备 Agent HMAC 签名测试。"""

from __future__ import annotations

import hashlib
import hmac

from device_agent.signing import signed_headers


def test_signing_uses_path_body_hash_timestamp_and_nonce() -> None:
    """签名必须覆盖固定路径、正文摘要、时间戳和防重放 Nonce。"""
    body = b'{"status":"ONLINE"}'
    secret = "0123456789abcdef0123456789abcdef"

    headers = signed_headers(
        "post", "https://base.example.com/api/agent/ios-device/v1/health?ignored=true",
        body, "ios-agent-test", secret, timestamp=1_700_000_000, nonce="nonce-test",
    )

    body_hash = hashlib.sha256(body).hexdigest()
    canonical = "POST\n/api/agent/ios-device/v1/health\n" + body_hash \
        + "\n1700000000\nnonce-test"
    expected = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    assert headers["X-Device-Agent-Content-SHA256"] == body_hash
    assert headers["X-Device-Agent-Signature"] == expected
    assert headers["X-Device-Agent-Nonce"] == "nonce-test"
