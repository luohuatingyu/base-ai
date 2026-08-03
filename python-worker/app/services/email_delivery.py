"""使用标准库执行内部 SMTP 邮件发送。"""

import asyncio
from email.message import EmailMessage
import smtplib
import ssl

from app.models import EmailSendRequest


class MailDeliveryError(Exception):
    """可安全返回给 Java 的邮件投递异常。"""

    def __init__(self, detail: str, status_code: int = 400):
        """保存稳定错误键和对应 HTTP 状态。"""
        super().__init__(detail)
        self.detail = detail
        self.status_code = status_code


async def send_email(request: EmailSendRequest) -> dict[str, bool]:
    """在线程中发送邮件，避免阻塞 FastAPI 事件循环。"""
    try:
        await asyncio.to_thread(_send_sync, request)
        return {"sent": True}
    except (OSError, TimeoutError, smtplib.SMTPException) as exception:
        raise MailDeliveryError("mail.sendFailed", 502) from exception


def _send_sync(request: EmailSendRequest) -> None:
    """按 NONE、STARTTLS 或 SSL 模式创建 SMTP 连接并发送一封邮件。"""
    smtp = request.smtp
    message = EmailMessage()
    message["From"] = smtp.fromAddress
    message["To"] = ", ".join(request.toAddresses)
    if request.ccAddresses:
        message["Cc"] = ", ".join(request.ccAddresses)
    message["Subject"] = request.subject
    message.set_content(request.body)
    recipients = [*request.toAddresses, *request.ccAddresses]
    context = ssl.create_default_context()

    if smtp.tlsMode == "SSL":
        with smtplib.SMTP_SSL(smtp.host, smtp.port, timeout=30, context=context) as client:
            _authenticate_and_send(client, smtp.username, smtp.password, smtp.fromAddress, recipients, message)
        return

    with smtplib.SMTP(smtp.host, smtp.port, timeout=30) as client:
        client.ehlo()
        if smtp.tlsMode == "STARTTLS":
            client.starttls(context=context)
            client.ehlo()
        _authenticate_and_send(client, smtp.username, smtp.password, smtp.fromAddress, recipients, message)


def _authenticate_and_send(client: smtplib.SMTP, username: str, password: str, from_address: str,
                           recipients: list[str], message: EmailMessage) -> None:
    """完成 SMTP 认证并发送消息，不记录凭证或正文。"""
    client.login(username, password)
    client.send_message(message, from_addr=from_address, to_addrs=recipients)
