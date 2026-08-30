"""Dify Worker 内部 HMAC 验证测试。"""

import hashlib
import hmac
import unittest

from app.internal_auth import InternalRequestVerifier


class InternalAuthTest(unittest.TestCase):
    """覆盖正文篡改、过期和 nonce 重放。"""

    def headers(self, body: bytes, timestamp: int, nonce: str) -> dict[str, str]:
        """按跨语言协议生成固定签名。"""
        digest = hashlib.sha256(body).hexdigest()
        target = "/invocations"
        canonical = f"POST\n{target}\n{timestamp}\n{nonce}\n{digest}".encode()
        signature = hmac.new(("d" * 32).encode(), canonical, hashlib.sha256).hexdigest()
        return {"X-Internal-Timestamp": str(timestamp), "X-Internal-Nonce": nonce,
                "X-Internal-Target": target, "X-Internal-Content-SHA256": digest,
                "X-Internal-Signature": signature}

    def test_verifies_once_and_rejects_tampering(self) -> None:
        """同一有效请求只接受一次，篡改正文不得通过。"""
        body = b'{"value":1}'
        now = 1_788_000_000
        headers = self.headers(body, now, "0123456789abcdef0123456789abcdef")
        verifier = InternalRequestVerifier("d" * 32)

        self.assertTrue(verifier.verify("POST", "/invocations", body, headers, now))
        self.assertFalse(verifier.verify("POST", "/invocations", body, headers, now))
        changed = self.headers(body, now, "abcdef0123456789abcdef0123456789")
        self.assertFalse(InternalRequestVerifier("d" * 32).verify(
            "POST", "/invocations", b'{"value":2}', changed, now))

    def test_rejects_expired_signature(self) -> None:
        """超过 60 秒的请求不得执行。"""
        headers = self.headers(b"{}", 1_788_000_000, "fedcba9876543210fedcba9876543210")
        self.assertFalse(InternalRequestVerifier("d" * 32).verify(
            "POST", "/invocations", b"{}", headers, 1_788_000_061))


if __name__ == "__main__":
    unittest.main()
