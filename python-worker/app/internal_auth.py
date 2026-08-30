"""跨 Java/Python 的内部请求 HMAC-SHA256 签名协议。"""

from __future__ import annotations

import hashlib
import hmac
import json
import re
import secrets
import time
from collections.abc import Mapping
from urllib.parse import urlsplit

TIMESTAMP = "X-Internal-Timestamp"
NONCE = "X-Internal-Nonce"
TARGET = "X-Internal-Target"
CONTENT_SHA256 = "X-Internal-Content-SHA256"
SIGNATURE = "X-Internal-Signature"
MAXIMUM_SKEW_SECONDS = 60
NONCE_PATTERN = re.compile(r"^[a-f0-9]{32}$")
DIGEST_PATTERN = re.compile(r"^[a-f0-9]{64}$")


def json_body(value: object) -> bytes:
    """生成与 HTTP 客户端实际发送内容完全一致的紧凑 UTF-8 JSON。"""
    return json.dumps(value, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def request_target(url: str) -> str:
    """从绝对 URL 生成保留查询字符串的 origin-form request-target。"""
    parsed = urlsplit(url)
    path = parsed.path or "/"
    return f"{path}?{parsed.query}" if parsed.query else path


def signed_headers(secret: str, method: str, target: str, body: bytes = b"", *,
                   timestamp: int | None = None, nonce: str | None = None) -> dict[str, str]:
    """使用时间戳和随机 nonce 生成不可重放请求头。"""
    if not target.startswith("/") or len(target) > 4096 or "\r" in target or "\n" in target:
        raise ValueError("invalid internal request target")
    seconds = int(time.time()) if timestamp is None else timestamp
    unique = secrets.token_hex(16) if nonce is None else nonce
    digest = hashlib.sha256(body).hexdigest()
    canonical = _canonical(method, target, seconds, unique, digest)
    signature = hmac.new(secret.encode("utf-8"), canonical, hashlib.sha256).hexdigest()
    return {TIMESTAMP: str(seconds), NONCE: unique, TARGET: target,
            CONTENT_SHA256: digest, SIGNATURE: signature}


class InternalRequestVerifier:
    """校验内部签名并维护单进程时间窗 nonce 缓存。"""

    def __init__(self, secret: str, maximum_skew_seconds: int = MAXIMUM_SKEW_SECONDS) -> None:
        """保存独立共享密钥与允许的最大小时钟偏差。"""
        self.secret = secret
        self.maximum_skew_seconds = maximum_skew_seconds
        self.used_nonces: dict[str, int] = {}

    def has_headers(self, headers: Mapping[str, str]) -> bool:
        """在读取请求体前快速拒绝缺失签名字段的请求。"""
        return all(headers.get(name) for name in (TIMESTAMP, NONCE, TARGET, CONTENT_SHA256, SIGNATURE))

    def verify(self, method: str, actual_target: str, body: bytes,
               headers: Mapping[str, str], now: int | None = None) -> bool:
        """常量时间校验摘要和签名，并原子拒绝时间窗内重复 nonce。"""
        try:
            timestamp_text = headers.get(TIMESTAMP, "")
            nonce = headers.get(NONCE, "")
            target = headers.get(TARGET, "")
            digest = headers.get(CONTENT_SHA256, "")
            signature = headers.get(SIGNATURE, "")
            if (len(self.secret) < 24 or not timestamp_text.isdigit() or len(timestamp_text) > 12
                    or not NONCE_PATTERN.fullmatch(nonce) or not DIGEST_PATTERN.fullmatch(digest)
                    or not DIGEST_PATTERN.fullmatch(signature) or target != actual_target):
                return False
            seconds = int(time.time()) if now is None else now
            signed_at = int(timestamp_text)
            if abs(seconds - signed_at) > self.maximum_skew_seconds:
                return False
            actual_digest = hashlib.sha256(body).hexdigest()
            if not hmac.compare_digest(digest, actual_digest):
                return False
            expected = hmac.new(self.secret.encode("utf-8"),
                _canonical(method, target, signed_at, nonce, digest), hashlib.sha256).hexdigest()
            if not hmac.compare_digest(signature, expected):
                return False
            self.used_nonces = {key: value for key, value in self.used_nonces.items()
                                if value >= seconds - self.maximum_skew_seconds}
            if nonce in self.used_nonces:
                return False
            self.used_nonces[nonce] = seconds
            return True
        except (TypeError, ValueError):
            return False


def _canonical(method: str, target: str, timestamp: int, nonce: str, digest: str) -> bytes:
    """生成与 Java、Go 和 Node 实现一致的换行分隔规范串。"""
    return f"{method.upper()}\n{target}\n{timestamp}\n{nonce}\n{digest}".encode("utf-8")
