"""Python Worker SMTP 邮件发送测试。"""

import asyncio
import smtplib

import pytest
from pydantic import ValidationError

from app.models import EmailSendRequest
from app.services import email_delivery


class FakeSmtp:
    """记录 SMTP 生命周期和发送参数的上下文管理器。"""

    def __init__(self, host="", port=0, **kwargs):
        """记录连接参数。"""
        self.calls = []
        self._host = host
        if host:
            self.connect(host, port)

    def connect(self, host, port):
        """记录固定 IP 连接目标。"""
        self.calls.append(("connect", host, port))
        return 220, b"ready"

    def __enter__(self):
        """返回当前模拟连接。"""
        return self

    def __exit__(self, *_args):
        """记录连接关闭。"""
        self.calls.append(("close",))

    def ehlo(self):
        """记录 SMTP 握手。"""
        self.calls.append(("ehlo",))

    def starttls(self, **kwargs):
        """记录 TLS 升级。"""
        self.calls.append(("starttls", kwargs))

    def login(self, username, password):
        """记录认证参数供断言使用。"""
        self.calls.append(("login", username, password))

    def send_message(self, message, **kwargs):
        """记录邮件消息及收件人。"""
        self.calls.append(("send", message, kwargs))


def request(tls_mode: str = "STARTTLS") -> EmailSendRequest:
    """创建不包含真实凭证的合法发送请求。"""
    return EmailSendRequest.model_validate({
        "smtp": {
            "host": "smtp.example.com", "port": 587, "username": "sender@example.com",
            "fromAddress": "sender@example.com", "tlsMode": tls_mode, "password": "test-password",
        },
        "toAddresses": ["one@example.com"], "ccAddresses": ["two@example.com"],
        "subject": "邮件路由测试", "body": "测试正文",
    })


@pytest.fixture(autouse=True)
def public_dns(monkeypatch):
    """所有正常发送用例固定到无敏感含义的公网测试地址。"""
    monkeypatch.setattr(email_delivery, "public_addresses", lambda *_args, **_kwargs: ["8.8.8.8"])


def test_sends_with_starttls_and_all_recipients(monkeypatch):
    """STARTTLS 模式必须升级连接、认证并发送给主送和抄送人。"""
    instances = []

    def factory(*args, **kwargs):
        """创建并保存模拟连接。"""
        instance = FakeSmtp(*args, **kwargs)
        instances.append(instance)
        return instance

    monkeypatch.setattr(email_delivery.smtplib, "SMTP", factory)

    result = asyncio.run(email_delivery.send_email(request()))

    assert result == {"sent": True}
    calls = instances[0].calls
    assert [call[0] for call in calls] == ["connect", "ehlo", "starttls", "ehlo", "login", "send", "close"]
    assert calls[0][1:] == ("8.8.8.8", 587)
    assert instances[0]._host == "smtp.example.com"
    assert calls[-2][2]["to_addrs"] == ["one@example.com", "two@example.com"]


def test_uses_smtp_ssl_without_starttls(monkeypatch):
    """SSL 模式必须使用 SMTP_SSL 且不重复执行 STARTTLS。"""
    instances = []

    def factory(*args, **kwargs):
        """创建并保存模拟 SSL 连接。"""
        instance = FakeSmtp(*args, **kwargs)
        instances.append(instance)
        return instance

    monkeypatch.setattr(email_delivery.smtplib, "SMTP_SSL", factory)

    assert asyncio.run(email_delivery.send_email(request("SSL"))) == {"sent": True}
    assert "starttls" not in [call[0] for call in instances[0].calls]


def test_uses_plain_smtp_without_starttls(monkeypatch):
    """NONE 模式必须使用普通 SMTP 且不升级 TLS。"""
    instances = []

    def factory(*args, **kwargs):
        """创建并保存模拟普通连接。"""
        instance = FakeSmtp(*args, **kwargs)
        instances.append(instance)
        return instance

    monkeypatch.setattr(email_delivery.smtplib, "SMTP", factory)

    assert asyncio.run(email_delivery.send_email(request("NONE"))) == {"sent": True}
    assert [call[0] for call in instances[0].calls] == ["connect", "ehlo", "login", "send", "close"]


def test_hides_smtp_failure_details(monkeypatch):
    """SMTP 异常必须转换为稳定错误键且不泄露底层凭证信息。"""
    def failed_factory(*_args, **_kwargs):
        """模拟包含敏感细节的连接异常。"""
        raise smtplib.SMTPException("secret provider detail")

    monkeypatch.setattr(email_delivery.smtplib, "SMTP", failed_factory)

    with pytest.raises(email_delivery.MailDeliveryError) as raised:
        asyncio.run(email_delivery.send_email(request("NONE")))
    assert raised.value.detail == "mail.sendFailed"
    assert raised.value.status_code == 502
    assert "secret provider detail" not in raised.value.detail


def test_rejects_private_smtp_resolution_before_connect(monkeypatch):
    """SMTP 主机解析到私网时不得创建任何网络连接。"""
    connected = False

    def unsafe(*_args, **_kwargs):
        """模拟 DNS 返回被策略拒绝的地址。"""
        raise email_delivery.NetworkPolicyError("OUTBOUND_ADDRESS_FORBIDDEN")

    def factory(*_args, **_kwargs):
        """记录不应发生的 SMTP 创建。"""
        nonlocal connected
        connected = True
        return FakeSmtp()

    monkeypatch.setattr(email_delivery, "public_addresses", unsafe)
    monkeypatch.setattr(email_delivery.smtplib, "SMTP", factory)

    with pytest.raises(email_delivery.MailDeliveryError) as raised:
        asyncio.run(email_delivery.send_email(request("NONE")))
    assert raised.value.detail == "mail.smtpHostUnsafe"
    assert connected is False


@pytest.mark.parametrize("field,value", [
    ("subject", "normal\nBcc:evil@example.com"),
    ("toAddresses", ["normal@example.com\nBcc:evil@example.com"]),
    ("ccAddresses", ["invalid-address"]),
])
def test_rejects_header_injection_and_invalid_addresses(field, value):
    """邮件头注入和非法地址必须在建立 SMTP 连接前拒绝。"""
    payload = request().model_dump()
    payload[field] = value
    with pytest.raises(ValidationError):
        EmailSendRequest.model_validate(payload)
