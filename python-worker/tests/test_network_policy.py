"""Python Worker 出站网络策略测试。"""

import asyncio
import socket

import httpx
import pytest

from app.network_policy import NetworkPolicyError, PublicNetworkTransport, public_addresses


def record(address: str) -> tuple:
    """构造 getaddrinfo 风格的 IPv4 结果。"""
    return socket.AF_INET, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", (address, 443)


def ipv6_record(address: str) -> tuple:
    """构造 getaddrinfo 风格的 IPv6 结果。"""
    return socket.AF_INET6, socket.SOCK_STREAM, socket.IPPROTO_TCP, "", (address, 8443, 0, 0)


def test_resolution_rejects_mixed_public_and_private_addresses():
    """同一名称只要包含一个私网结果就必须整体拒绝。"""
    resolver = lambda *_args, **_kwargs: [record("8.8.8.8"), record("127.0.0.1")]
    with pytest.raises(NetworkPolicyError, match="OUTBOUND_ADDRESS_FORBIDDEN"):
        public_addresses("provider.example", 443, resolver)


def test_transport_pins_public_ip_and_preserves_host_and_sni():
    """实际连接必须使用已验证 IP，而 HTTP Host 和 TLS SNI 保留供应商域名。"""
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        """记录传给底层网络传输的固定请求。"""
        captured["host"] = request.url.host
        captured["header"] = request.headers["Host"]
        captured["sni"] = request.extensions["sni_hostname"]
        return httpx.Response(200, json={"ok": True})

    resolver = lambda *_args, **_kwargs: [record("8.8.8.8")]
    transport = PublicNetworkTransport(httpx.MockTransport(handler), resolver)

    async def invoke() -> httpx.Response:
        """通过待测传输执行一次 HTTPS 请求并关闭资源。"""
        async with httpx.AsyncClient(transport=transport) as client:
            return await client.get("https://provider.example/v1/models")

    response = asyncio.run(invoke())

    assert response.status_code == 200
    assert captured == {"host": "8.8.8.8", "header": "provider.example", "sni": "provider.example"}


def test_transport_formats_ipv6_authority_without_changing_sni():
    """公网 IPv6 固定地址必须使用合法 URL，原 IPv6 Host 头需保留方括号。"""
    captured = {}

    def handler(request: httpx.Request) -> httpx.Response:
        """记录 IPv6 固定请求。"""
        captured["host"] = request.url.host
        captured["header"] = request.headers["Host"]
        captured["sni"] = request.extensions["sni_hostname"]
        return httpx.Response(200)

    resolver = lambda *_args, **_kwargs: [ipv6_record("2001:4860:4860::8888")]
    transport = PublicNetworkTransport(httpx.MockTransport(handler), resolver)

    async def invoke() -> httpx.Response:
        """通过自定义端口执行一次 IPv6 固定请求。"""
        async with httpx.AsyncClient(transport=transport) as client:
            return await client.get("https://[2001:4860:4860::8844]:8443/v1/models")

    response = asyncio.run(invoke())

    assert response.status_code == 200
    assert captured == {
        "host": "2001:4860:4860::8888",
        "header": "[2001:4860:4860::8844]:8443",
        "sni": "2001:4860:4860::8844",
    }
