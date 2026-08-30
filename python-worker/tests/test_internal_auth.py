"""跨服务 HMAC 签名协议测试。"""

from app.internal_auth import InternalRequestVerifier, signed_headers


SECRET = "s" * 32
NOW = 1_788_000_000
NONCE = "0123456789abcdef0123456789abcdef"


def test_signatures_bind_method_target_and_body():
    """签名必须同时绑定方法、request-target 和原始正文。"""
    body = b'{"status":"RUNNING"}'
    headers = signed_headers(SECRET, "POST", "/api/internal/events", body, timestamp=NOW, nonce=NONCE)
    verifier = InternalRequestVerifier(SECRET)

    assert verifier.verify("POST", "/api/internal/events", body, headers, NOW)
    assert not InternalRequestVerifier(SECRET).verify("POST", "/api/internal/events", b"{}", headers, NOW)
    assert not InternalRequestVerifier(SECRET).verify("PUT", "/api/internal/events", body, headers, NOW)


def test_signatures_expire_and_nonce_cannot_be_replayed():
    """过期签名和已使用 nonce 必须被拒绝。"""
    headers = signed_headers(SECRET, "POST", "/internal", b"", timestamp=NOW, nonce=NONCE)
    verifier = InternalRequestVerifier(SECRET)

    assert not verifier.verify("POST", "/internal", b"", headers, NOW + 61)
    assert verifier.verify("POST", "/internal", b"", headers, NOW)
    assert not verifier.verify("POST", "/internal", b"", headers, NOW)
