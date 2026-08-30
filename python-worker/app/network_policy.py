"""出站网络目标校验与固定解析，阻断私网访问和 DNS 重绑定。"""

from __future__ import annotations

import asyncio
import ipaddress
import socket
from collections.abc import Callable

import httpx


class NetworkPolicyError(ValueError):
    """目标地址不满足公网出站策略。"""


Resolver = Callable[..., list[tuple]]


def public_addresses(host: str, port: int, resolver: Resolver = socket.getaddrinfo) -> list[str]:
    """解析目标并要求全部结果均为可路由公网地址，返回去重后的固定 IP。"""
    normalized = host.strip().rstrip(".")
    if not normalized or normalized.lower() == "localhost":
        raise NetworkPolicyError("OUTBOUND_HOST_FORBIDDEN")
    try:
        records = resolver(normalized, port, type=socket.SOCK_STREAM)
    except (OSError, UnicodeError) as exception:
        raise NetworkPolicyError("OUTBOUND_DNS_FAILED") from exception
    addresses: list[str] = []
    for record in records:
        try:
            address = str(ipaddress.ip_address(record[4][0]))
        except (IndexError, TypeError, ValueError) as exception:
            raise NetworkPolicyError("OUTBOUND_DNS_INVALID") from exception
        if not ipaddress.ip_address(address).is_global:
            raise NetworkPolicyError("OUTBOUND_ADDRESS_FORBIDDEN")
        if address not in addresses:
            addresses.append(address)
    if not addresses:
        raise NetworkPolicyError("OUTBOUND_DNS_FAILED")
    return addresses


def reject_unsafe_literal(host: str) -> None:
    """在请求模型解析阶段立即拒绝本机名称和非公网 IP 字面量。"""
    normalized = host.strip().rstrip(".").lower()
    if normalized == "localhost" or normalized.endswith((".localhost", ".local", ".internal")):
        raise NetworkPolicyError("OUTBOUND_HOST_FORBIDDEN")
    try:
        address = ipaddress.ip_address(normalized)
    except ValueError:
        return
    if not address.is_global:
        raise NetworkPolicyError("OUTBOUND_ADDRESS_FORBIDDEN")


class PublicNetworkTransport(httpx.AsyncBaseTransport):
    """把 HTTPX 请求固定到已验证公网 IP，同时保留原 Host 与 TLS SNI。"""

    def __init__(self, delegate: httpx.AsyncBaseTransport | None = None,
                 resolver: Resolver = socket.getaddrinfo) -> None:
        """创建不继承环境代理、不重试且不跨域复用连接的生产传输。"""
        self._delegate = delegate or httpx.AsyncHTTPTransport(
            retries=0,
            trust_env=False,
            limits=httpx.Limits(max_connections=200, max_keepalive_connections=0),
        )
        self._resolver = resolver

    async def handle_async_request(self, request: httpx.Request) -> httpx.Response:
        """逐请求重新解析并固定目标，避免连接阶段再次查询攻击者控制的 DNS。"""
        host = request.url.host
        if not host or request.url.scheme not in {"http", "https"}:
            raise NetworkPolicyError("OUTBOUND_URL_FORBIDDEN")
        port = request.url.port or (443 if request.url.scheme == "https" else 80)
        addresses = await asyncio.to_thread(public_addresses, host, port, self._resolver)
        headers = request.headers.copy()
        default_port = 443 if request.url.scheme == "https" else 80
        authority = f"[{host}]" if ":" in host else host
        headers["Host"] = authority if port == default_port else f"{authority}:{port}"
        extensions = dict(request.extensions)
        extensions["sni_hostname"] = host
        pinned = httpx.Request(request.method, request.url.copy_with(host=addresses[0]), headers=headers,
                               stream=request.stream, extensions=extensions)
        response = await self._delegate.handle_async_request(pinned)
        response.request = request
        return response

    async def aclose(self) -> None:
        """关闭底层连接池。"""
        await self._delegate.aclose()
