"""Agent HTTP HMAC 签名协议。"""

from __future__ import annotations

import hashlib
import hmac
import secrets
import time
from urllib.parse import urlsplit


def signed_headers(method: str, url: str, body: bytes, agent_id: str, secret: str,
                   timestamp: int | None = None, nonce: str | None = None) -> dict[str, str]:
    """按后端固定协议生成包含正文摘要和 Nonce 的签名头。"""
    request_timestamp = int(time.time()) if timestamp is None else timestamp
    request_nonce = nonce or secrets.token_urlsafe(24)
    content_hash = hashlib.sha256(body).hexdigest()
    path = urlsplit(url).path
    canonical = "\n".join((method.upper(), path, content_hash, str(request_timestamp), request_nonce))
    signature = hmac.new(secret.encode(), canonical.encode(), hashlib.sha256).hexdigest()
    return {
        "X-Device-Agent-Id": agent_id,
        "X-Device-Agent-Timestamp": str(request_timestamp),
        "X-Device-Agent-Nonce": request_nonce,
        "X-Device-Agent-Content-SHA256": content_hash,
        "X-Device-Agent-Signature": signature,
    }
