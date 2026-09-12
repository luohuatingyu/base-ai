"""通过固定公网地址和只读 IMAP 会话读取邮件，不保存正文。"""

import asyncio
import imaplib
import re
import socket
import ssl
import time
from email import policy
from email.parser import BytesParser
from html.parser import HTMLParser

from app.models import EmailInboxRequest
from app.network_policy import NetworkPolicyError, public_addresses

MAX_MESSAGE_BYTES = 2 * 1024 * 1024
MAX_TEXT_CHARACTERS = 200000
HEADER_BYTES = 16384
TIMEOUT = 25


class InboxError(Exception):
    """对外只返回固定错误，避免泄露服务器认证信息。"""

    def __init__(self, status_code=502, detail="mail.imap.readFailed"):
        """保存稳定状态码与消息键。"""
        super().__init__(detail)
        self.status_code = status_code
        self.detail = detail


class PublicImap(imaplib.IMAP4):
    """连接已验证的固定 IP，TLS 始终使用原始域名校验证书。"""

    def __init__(self, config):
        """解析一次 DNS 并给完整会话设置总时限。"""
        self.address = public_addresses(config.host, config.port)[0]
        self.implicit_tls = config.tlsMode == "SSL"
        self.deadline = time.monotonic() + TIMEOUT
        self.bytes_read = 0
        super().__init__(config.host, config.port, timeout=TIMEOUT)

    def _create_socket(self, timeout):
        """避免连接时再次解析域名，并在 TLS 失败时关闭原始套接字。"""
        connection = socket.create_connection((self.address, self.port), timeout)
        try:
            if self.implicit_tls:
                return ssl.create_default_context().wrap_socket(connection, server_hostname=self.host)
            return connection
        except BaseException:
            connection.close()
            raise

    def _budget(self, size):
        """限制响应累计大小和读取时间，拒绝恶意服务器声明的超大 literal。"""
        self.bytes_read += size
        if self.bytes_read > MAX_MESSAGE_BYTES + HEADER_BYTES * 50 + 65536:
            raise InboxError(413, "mail.imap.tooLarge")
        remaining = self.deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError()
        self.sock.settimeout(remaining)

    def read(self, size):
        """在分配 literal 缓冲区前执行大小限制。"""
        self._budget(size)
        return super().read(size)

    def readline(self):
        """每行读取前刷新剩余超时并统计实际字节数。"""
        self._budget(0)
        line = super().readline()
        self._budget(len(line))
        return line


class HtmlText(HTMLParser):
    """提取可阅读文本，忽略脚本、样式和不可见头信息。"""

    def __init__(self):
        """初始化隐藏标签栈及文本片段。"""
        super().__init__(convert_charrefs=True)
        self.hidden = []
        self.parts = []

    def handle_starttag(self, tag, attrs):
        """忽略危险内容并保留基本段落边界。"""
        if tag in {"script", "style", "head", "template"}:
            self.hidden.append(tag)
        if not self.hidden and tag in {"p", "div", "br", "li", "tr"}:
            self.parts.append("\n")

    def handle_endtag(self, tag):
        """关闭隐藏标签。"""
        if self.hidden and self.hidden[-1] == tag:
            self.hidden.pop()

    def handle_data(self, data):
        """仅收集可见文本，不输出任何 HTML 标签。"""
        if not self.hidden:
            self.parts.append(data)


def parse_message(raw, uid, include_body=False):
    """解析 MIME 编码头及安全正文；附件不下载或展示。"""
    message = BytesParser(policy=policy.default).parsebytes(raw)
    result = {"uid": str(uid), "subject": str(message.get("Subject", ""))[:4096],
              "from": str(message.get("From", ""))[:4096], "to": str(message.get("To", ""))[:4096],
              "date": str(message.get("Date", ""))[:256]}
    if include_body:
        body = message.get_body(preferencelist=("plain", "html"))
        content = ""
        if body is not None:
            payload = body.get_payload(decode=True) or b""
            try:
                content = payload.decode(body.get_content_charset() or "utf-8", errors="replace")
            except LookupError:
                content = payload.decode("utf-8", errors="replace")
            if body.get_content_type() == "text/html":
                parser = HtmlText()
                parser.feed(content)
                content = "".join(parser.parts)
        result["body"] = content[:MAX_TEXT_CHARACTERS]
        result["truncated"] = len(content) > MAX_TEXT_CHARACTERS
    return result


def checked(response):
    """将服务器失败状态转换为不含远端原文的固定错误。"""
    status, data = response
    if status != "OK":
        raise InboxError()
    return data


def read_sync(request):
    """只读打开 INBOX，以 PEEK 获取数据并在任何异常后关闭连接。"""
    client = PublicImap(request.imap)
    try:
        if request.imap.tlsMode == "STARTTLS":
            checked(client.starttls(ssl_context=ssl.create_default_context()))
        checked(client.login(request.imap.username, request.imap.password))
        counts = checked(client.select("INBOX", readonly=True))
        total = int(counts[0])
        validity_data = client.response("UIDVALIDITY")[1]
        validity = validity_data[0].decode("ascii") if validity_data and validity_data[0] else ""
        if not re.fullmatch(r"[1-9][0-9]{0,9}", validity) or int(validity) > 4294967295:
            raise InboxError()
        if request.uid is not None:
            if validity != request.uidValidity:
                raise InboxError(409, "mail.imap.mailboxChanged")
            metadata = checked(client.uid("fetch", request.uid, "(UID RFC822.SIZE)"))
            sizes = [re.search(rb"RFC822.SIZE (\d+)", item) for item in metadata if isinstance(item, bytes)]
            match = next((item for item in sizes if item), None)
            if match is None:
                raise InboxError(404, "mail.imap.messageMissing")
            if int(match[1]) > MAX_MESSAGE_BYTES:
                raise InboxError(413, "mail.imap.tooLarge")
            data = checked(client.uid("fetch", request.uid, f"(UID BODY.PEEK[]<0.{MAX_MESSAGE_BYTES + 1}>)"))
            for item in data:
                if isinstance(item, tuple) and re.search(rb"UID " + request.uid.encode() + rb"\b", item[0]):
                    if len(item[1]) > MAX_MESSAGE_BYTES:
                        raise InboxError(413, "mail.imap.tooLarge")
                    return {**parse_message(item[1], request.uid, True), "uidValidity": validity}
            raise InboxError(404, "mail.imap.messageMissing")
        end = total - (request.page - 1) * request.size
        rows = []
        if end > 0:
            start = max(1, end - request.size + 1)
            data = checked(client.fetch(f"{start}:{end}", f"(UID BODY.PEEK[HEADER.FIELDS (SUBJECT FROM TO DATE)]<0.{HEADER_BYTES}>)"))
            for item in data:
                if isinstance(item, tuple):
                    match = re.search(rb"UID (\d+)", item[0])
                    if match:
                        rows.append(parse_message(item[1][:HEADER_BYTES], match[1].decode("ascii")))
        rows.sort(key=lambda row: int(row["uid"]), reverse=True)
        return {"items": rows, "total": total, "page": request.page, "size": request.size, "uidValidity": validity}
    finally:
        client.shutdown()


async def read_inbox(request: EmailInboxRequest):
    """在工作线程中执行阻塞 IMAP，将认证和网络异常统一脱敏。"""
    try:
        return await asyncio.to_thread(read_sync, request)
    except InboxError:
        raise
    except NetworkPolicyError as exception:
        raise InboxError(400, "mail.imap.hostUnsafe") from exception
    except (OSError, imaplib.IMAP4.error, ValueError, TypeError, UnicodeError, RecursionError) as exception:
        raise InboxError() from exception
