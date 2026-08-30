"""Dify 插件 Worker 的内部 HMAC 请求验证。"""

import hashlib
import hmac
import re
import threading
import time
from collections.abc import Mapping


HEX_32 = re.compile(r"^[a-f0-9]{32}$")
HEX_64 = re.compile(r"^[a-f0-9]{64}$")


class InternalRequestVerifier:
    """校验正文绑定签名、短时钟窗和 nonce 防重放。"""

    def __init__(self, secret: str) -> None:
        """创建每个 Worker 进程独立的 nonce 缓存。"""
        self.secret = secret
        self.used: dict[str, int] = {}
        self.lock = threading.Lock()

    def verify(self, method: str, actual_target: str, body: bytes,
               headers: Mapping[str, str], now: int | None = None) -> bool:
        """验证 HMAC 并在签名有效后原子登记 nonce。"""
        try:
            timestamp_text = headers.get("X-Internal-Timestamp", "")
            nonce = headers.get("X-Internal-Nonce", "")
            target = headers.get("X-Internal-Target", "")
            digest = headers.get("X-Internal-Content-SHA256", "")
            signature = headers.get("X-Internal-Signature", "")
            if (len(self.secret) < 24 or not timestamp_text.isdigit() or len(timestamp_text) > 12
                    or not HEX_32.fullmatch(nonce) or not HEX_64.fullmatch(digest)
                    or not HEX_64.fullmatch(signature) or target != actual_target):
                return False
            seconds = int(time.time()) if now is None else now
            signed_at = int(timestamp_text)
            if abs(seconds - signed_at) > 60 or not hmac.compare_digest(hashlib.sha256(body).hexdigest(), digest):
                return False
            canonical = f"{method.upper()}\n{target}\n{signed_at}\n{nonce}\n{digest}".encode()
            expected = hmac.new(self.secret.encode(), canonical, hashlib.sha256).hexdigest()
            if not hmac.compare_digest(expected, signature):
                return False
            with self.lock:
                self.used = {key: value for key, value in self.used.items() if value >= seconds - 60}
                if nonce in self.used:
                    return False
                self.used[nonce] = seconds
            return True
        except (TypeError, ValueError):
            return False
