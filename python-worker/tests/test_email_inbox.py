"""收件 MIME、请求约束、只读行为及错误边界测试。"""

import asyncio
import imaplib
import socketserver
import threading
import ssl
import time
from unittest.mock import Mock
from email.message import EmailMessage

import pytest
from pydantic import ValidationError
from app.models import EmailInboxRequest
from app.services import email_inbox as inbox


def request(**values):
    """创建无真实认证材料的收件请求。"""
    return EmailInboxRequest.model_validate({"imap": {"host": "imap.example.com", "port": 993,
        "username": "reader", "password": "secret", "tlsMode": "SSL"}, **values})


@pytest.mark.parametrize("values", [{"page": 0}, {"size": 0}, {"size": 51}, {"uid": "1:*"},
    {"uid": "4294967296", "uidValidity": "1"}, {"uid": "1"}, {"uid": "0"}])
def test_rejects_invalid_request(values):
    """拒绝分页边界及非法或不完整邮件标识。"""
    with pytest.raises(ValidationError):
        request(**values)


@pytest.mark.parametrize("field,value", [("host", "127.0.0.1"), ("host", "x\nLOGIN"),
    ("host", "x" * 256), ("username", ""), ("tlsMode", "NONE"), ("port", 65536), ("password", "")])
def test_rejects_invalid_config(field, value):
    """配置验证拒绝控制字符、私网和未加密认证。"""
    values = request().model_dump()
    values["imap"][field] = value
    with pytest.raises((ValidationError, ValueError)):
        EmailInboxRequest.model_validate(values)


def test_parses_mime_without_active_content():
    """中文编码、HTML 正文和附件均不能注入页面。"""
    message = EmailMessage()
    message["Subject"] = "中文主题"
    message["From"] = "sender@example.com"
    message.set_content('<html><head><style>bad</style></head><body><p>你好</p><script>evil()</script><img src="https://tracker">正文</body></html>', subtype="html")
    message.add_attachment(b"attachment", maintype="application", subtype="octet-stream", filename="secret.txt")
    result = inbox.parse_message(message.as_bytes(), "4", True)
    assert result["subject"] == "中文主题"
    assert "你好" in result["body"] and "正文" in result["body"]
    assert all(value not in result["body"] for value in ("evil", "bad", "tracker", "attachment"))
    assert not result["truncated"]


def test_handles_unknown_charset_and_long_body():
    """未知字符集可回退，超长正文明确截断。"""
    raw = b'Content-Type: text/plain; charset="unknown-charset"\r\n\r\nhello'
    assert inbox.parse_message(raw, "1", True)["body"] == "hello"
    result = inbox.parse_message(b"\r\n" + b"x" * (inbox.MAX_TEXT_CHARACTERS + 1), "1", True)
    assert result["truncated"]
    assert len(result["body"]) == inbox.MAX_TEXT_CHARACTERS


class FakeImap:
    """仅隔离网络，记录协议命令和清理行为。"""

    def __init__(self, config):
        """保存当前会话用于行为断言。"""
        self.calls = []
        self.closed = False

    def starttls(self, **kwargs):
        """记录 TLS 升级。"""
        self.calls.append("starttls")
        assert kwargs["ssl_context"].check_hostname
        return "OK", []

    def login(self, *args):
        """记录认证。"""
        self.calls.append("login")
        return "OK", []

    def select(self, mailbox, readonly):
        """确保只读打开 INBOX。"""
        assert mailbox == "INBOX" and readonly
        return "OK", [b"2"]

    def response(self, name):
        """返回邮箱版本。"""
        assert name == "UIDVALIDITY"
        return name, [b"9"]

    def fetch(self, sequence, fields):
        """返回编码头且禁止隐式已读。"""
        assert "BODY.PEEK" in fields
        self.calls.append(sequence)
        return "OK", [(b"1 (UID 4 BODY[HEADER] {19}", b"Subject: first\r\n\r\n"),
                       (b"2 (UID 7 BODY[HEADER] {20}", b"Subject: second\r\n\r\n")]

    def uid(self, command, uid, fields):
        """返回大小或正文，禁止写入命令。"""
        assert command == "fetch"
        if "RFC822.SIZE" in fields:
            return "OK", [b"1 (UID 4 RFC822.SIZE 40)"]
        assert "BODY.PEEK" in fields
        return "OK", [(b"1 (UID 4 BODY[] {40}", b"Subject: hello\r\n\r\nmessage body")]

    def shutdown(self):
        """记录连接释放。"""
        self.closed = True


def test_lists_and_reads_without_marking_seen(monkeypatch):
    """列表按 UID 倒序，翻页为空，正文读取保持服务器标志。"""
    client = FakeImap(None)
    monkeypatch.setattr(inbox, "PublicImap", lambda config: client)
    result = asyncio.run(inbox.read_inbox(request()))
    assert [row["uid"] for row in result["items"]] == ["7", "4"]
    assert result["total"] == 2 and result["uidValidity"] == "9"
    assert client.closed
    assert asyncio.run(inbox.read_inbox(request(page=2)))["items"] == []
    result = asyncio.run(inbox.read_inbox(request(uid="4", uidValidity="9")))
    assert result["body"] == "message body"
    config = request(); config.imap.tlsMode = "STARTTLS"
    asyncio.run(inbox.read_inbox(config))
    assert client.calls[-3:-1] == ["starttls", "login"]


@pytest.mark.parametrize("kind,status", [("version", 409), ("missing", 404), ("large", 413), ("auth", 502), ("timeout", 502)])
def test_errors_release_connection_and_hide_secrets(monkeypatch, kind, status):
    """错误路径均关闭连接，不透传认证服务器原文。"""
    client = FakeImap(None)
    monkeypatch.setattr(inbox, "PublicImap", lambda config: client)
    if kind in {"missing", "large"}:
        monkeypatch.setattr(client, "uid", lambda *args: ("OK", [None] if kind == "missing" else [b"1 (UID 4 RFC822.SIZE 9999999)"]))
    if kind in {"auth", "timeout"}:
        def fail(*args):
            """模拟外部认证失败或超时。"""
            raise (TimeoutError("secret") if kind == "timeout" else imaplib.IMAP4.error("secret"))
        monkeypatch.setattr(client, "login", fail)
    with pytest.raises(inbox.InboxError) as caught:
        asyncio.run(inbox.read_inbox(request(uid="4", uidValidity="8" if kind == "version" else "9")))
    assert caught.value.status_code == status
    assert "secret" not in str(caught.value)
    assert client.closed


def test_real_imap_protocol_readonly(monkeypatch):
    """使用真实 TCP IMAP 协议解析验证 EXAMINE、UID 与 PEEK；仅替换公网连接边界。"""
    commands = []
    raw = b"Subject: protocol\r\nFrom: sender@example.com\r\n\r\nprotocol body"

    class Handler(socketserver.StreamRequestHandler):
        """提供受控 IMAP 服务器协议响应。"""

        def handle(self):
            """模拟能力、认证、只读选择和 FETCH literal。"""
            self.wfile.write(b"* OK ready\r\n")
            while line := self.rfile.readline():
                tag, command = line.rstrip().split(b" ", 1)
                commands.append(command)
                if command == b"CAPABILITY":
                    self.wfile.write(b"* CAPABILITY IMAP4rev1\r\n")
                elif command.startswith(b"EXAMINE"):
                    self.wfile.write(b"* 1 EXISTS\r\n* OK [UIDVALIDITY 9] valid\r\n")
                elif command.startswith(b"UID FETCH") and b"RFC822.SIZE" in command:
                    self.wfile.write(b"* 1 FETCH (UID 4 RFC822.SIZE " + str(len(raw)).encode() + b")\r\n")
                elif command.startswith((b"FETCH", b"UID FETCH")):
                    self.wfile.write(b"* 1 FETCH (UID 4 BODY[] {" + str(len(raw)).encode() + b"}\r\n" + raw + b")\r\n")
                self.wfile.write(tag + b" OK done\r\n")

    with socketserver.TCPServer(("127.0.0.1", 0), Handler) as server:
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        monkeypatch.setattr(inbox, "PublicImap", lambda config: imaplib.IMAP4("127.0.0.1", server.server_address[1], timeout=2))
        try:
            listing = asyncio.run(inbox.read_inbox(request()))
            detail = asyncio.run(inbox.read_inbox(request(uid="4", uidValidity="9")))
            assert listing["items"][0]["subject"] == "protocol"
            assert detail["body"] == "protocol body"
            assert any(command.startswith(b"EXAMINE") for command in commands)
            assert not any(command.startswith((b"SELECT", b"STORE", b"DELETE")) for command in commands)
            assert all(b"BODY.PEEK" in command for command in commands if b"BODY" in command)
        finally:
            server.shutdown()
            thread.join(timeout=2)


@pytest.mark.parametrize("tls", [True, False])
def test_connects_to_pinned_address_with_original_tls_name(monkeypatch, tls):
    """连接使用固定 IP，证书验证使用原始服务器域名。"""
    connection = Mock()
    wrapped = Mock()
    context = Mock()
    context.wrap_socket.return_value = wrapped
    connect = Mock(return_value=connection)
    monkeypatch.setattr(inbox.socket, "create_connection", connect)
    monkeypatch.setattr(inbox.ssl, "create_default_context", lambda: context)
    client = object.__new__(inbox.PublicImap)
    client.address = "8.8.8.8"; client.host = "imap.example.com"; client.port = 993; client.implicit_tls = tls
    assert client._create_socket(25) is (wrapped if tls else connection)
    connect.assert_called_once_with(("8.8.8.8", 993), 25)
    if tls:
        context.wrap_socket.assert_called_once_with(connection, server_hostname="imap.example.com")
        context.wrap_socket.side_effect = ssl.SSLCertVerificationError("invalid certificate")
        with pytest.raises(ssl.SSLCertVerificationError):
            client._create_socket(25)
        connection.close.assert_called_once()


def test_limits_response_allocation_and_session_time():
    """分配恶意 literal 前拒绝超限，剩余时间到期立即终止。"""
    client = object.__new__(inbox.PublicImap)
    client.sock = Mock(); client.bytes_read = 0; client.deadline = time.monotonic() + 10
    client._budget(100)
    assert client.bytes_read == 100
    with pytest.raises(inbox.InboxError) as caught:
        client.read(100000000)
    assert caught.value.status_code == 413
    client.bytes_read = 0; client.deadline = time.monotonic() - 1
    with pytest.raises(TimeoutError):
        client.readline()


def test_rejects_private_resolution(monkeypatch):
    """DNS 解析到受限地址时不建立连接，返回稳定错误。"""
    from app.network_policy import NetworkPolicyError

    def fail(*args):
        """模拟 DNS 策略拒绝。"""
        raise NetworkPolicyError("unsafe")

    monkeypatch.setattr(inbox, "public_addresses", fail)
    with pytest.raises(inbox.InboxError) as caught:
        asyncio.run(inbox.read_inbox(request()))
    assert caught.value.status_code == 400
    assert caught.value.detail == "mail.imap.hostUnsafe"


@pytest.mark.parametrize("kind,status", [("select", 502), ("version", 502), ("bodyMissing", 404), ("bodyLarge", 413)])
def test_rejects_inconsistent_server_responses(monkeypatch, kind, status):
    """服务器选择失败、版本无效或正文变化不能产生错误邮件内容。"""
    client = FakeImap(None)
    monkeypatch.setattr(inbox, "PublicImap", lambda config: client)
    if kind == "select":
        monkeypatch.setattr(client, "select", lambda *args, **kwargs: ("NO", [b"secret"]))
    elif kind == "version":
        monkeypatch.setattr(client, "response", lambda name: (name, [None]))
    else:
        def fetch(command, uid, fields):
            """模拟查询大小后正文消失或服务器超出请求范围返回数据。"""
            if "RFC822.SIZE" in fields:
                return "OK", [b"1 (UID 4 RFC822.SIZE 40)"]
            return "OK", [None] if kind == "bodyMissing" else [(b"1 (UID 4 BODY[])", b"x" * (inbox.MAX_MESSAGE_BYTES + 1))]
        monkeypatch.setattr(client, "uid", fetch)
    with pytest.raises(inbox.InboxError) as caught:
        asyncio.run(inbox.read_inbox(request(uid="4", uidValidity="9")))
    assert caught.value.status_code == status
    assert client.closed
