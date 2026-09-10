"""探测本机可用于 WebDriverAgent 的开发签名身份。"""

from __future__ import annotations

from dataclasses import dataclass
from datetime import datetime
import re
import subprocess
from typing import Any, Callable


_TEAM_ID = re.compile(r"^[A-Za-z0-9]{10}\Z")
_IDENTITY_LINE = re.compile(r'^\s*\d+\)\s+[0-9A-F]{40}\s+"(.+)"\s*$', re.MULTILINE)
_SUBJECT_OU = re.compile(r"\bOU\s*=\s*([A-Za-z0-9]{10})\b")
_END_DATE = re.compile(r"notAfter=(.+)")
_ALLOWED_PREFIXES = ("Apple Development", "iPhone Developer")
COMMAND_TIMEOUT_SECONDS = 15
MAX_CANDIDATES = 10


@dataclass(frozen=True, slots=True)
class SigningCandidate:
    """一个可直接写入 WDA 配置的开发签名候选。"""

    signing_identity: str
    team_id: str
    label: str
    expires_at: str

    def as_payload(self) -> dict[str, str]:
        """转换为管理页面消费的稳定字段。"""
        return {
            "signingIdentity": self.signing_identity,
            "teamId": self.team_id,
            "label": self.label,
            "expiresAt": self.expires_at,
        }


def detect_signing_candidates(
    runner: Callable[..., Any] = subprocess.run,
) -> list[SigningCandidate]:
    """列出开发签名身份，并从证书 OU 中提取 Apple 团队 ID。"""
    listing = _run(runner, ["/usr/bin/security", "find-identity", "-v", "-p", "codesigning"])
    if listing.returncode != 0:
        raise RuntimeError("SIGNING_DETECT_FAILED")
    names = [
        name for name in _IDENTITY_LINE.findall(listing.stdout)
        if name.startswith(_ALLOWED_PREFIXES)
    ]
    if not names:
        return []
    # 在启动任何子进程前截断候选数量：每个身份要导出并逐张解析证书，
    # 证书异常多的钥匙串会拖垮单线程 Agent 的心跳与命令轮询
    names = names[:MAX_CANDIDATES]
    candidates: dict[tuple[str, str], SigningCandidate] = {}
    for name in dict.fromkeys(names):
        for candidate in _parse_certificates(runner, name):
            key = (candidate.signing_identity, candidate.team_id)
            existing = candidates.get(key)
            if existing is None or candidate.expires_at > existing.expires_at:
                candidates[key] = candidate
    if not candidates:
        raise RuntimeError("SIGNING_DETECT_FAILED")
    return sorted(
        candidates.values(), key=lambda item: (item.signing_identity, item.team_id)
    )[:MAX_CANDIDATES]


def _parse_certificates(
    runner: Callable[..., Any], common_name: str,
) -> list[SigningCandidate]:
    """导出指定名称的证书并解析团队 ID 和到期日。"""
    exported = _run(
        runner, ["/usr/bin/security", "find-certificate", "-a", "-c", common_name, "-p"],
    )
    if exported.returncode != 0:
        return []
    identity_type = common_name.split(":", 1)[0].strip()
    candidates: list[SigningCandidate] = []
    for pem in _split_pem(exported.stdout):
        subject = _run(
            runner, ["/usr/bin/openssl", "x509", "-noout", "-subject", "-enddate"],
            input_text=pem,
        )
        match = _SUBJECT_OU.search(subject.stdout) if subject.returncode == 0 else None
        if match is None or not _TEAM_ID.fullmatch(match.group(1)):
            continue
        candidates.append(SigningCandidate(
            identity_type, match.group(1).upper(), common_name, _parse_end_date(subject.stdout),
        ))
    return candidates


def _split_pem(text: str) -> list[str]:
    """把多个 PEM 证书拆成独立文本。"""
    marker = "-----END CERTIFICATE-----"
    return [
        part.lstrip() + marker + "\n" for part in text.split(marker)
        if "-----BEGIN CERTIFICATE-----" in part
    ]


def _parse_end_date(text: str) -> str:
    """把 OpenSSL 到期时间转换为管理页使用的日期。"""
    match = _END_DATE.search(text)
    if match is None:
        return ""
    try:
        normalized = " ".join(match.group(1).split())
        return datetime.strptime(normalized, "%b %d %H:%M:%S %Y %Z").strftime("%Y-%m-%d")
    except ValueError:
        return ""


def _run(
    runner: Callable[..., Any], args: list[str], input_text: str | None = None,
) -> Any:
    """无 Shell 执行固定只读命令，并把本机异常折叠为失败结果。"""
    try:
        options: dict[str, Any] = {
            "check": False, "capture_output": True, "text": True,
            "timeout": COMMAND_TIMEOUT_SECONDS,
        }
        if input_text is not None:
            options["input"] = input_text
        return runner(args, **options)
    except (OSError, subprocess.SubprocessError):
        return subprocess.CompletedProcess(args, 127, "", "")
