"""Agent 本地非敏感配置与 macOS Keychain Secret 管理。"""

from __future__ import annotations

import json
import os
import subprocess
import uuid
from dataclasses import asdict, dataclass, field
from pathlib import Path
from typing import Any


APP_DIR = Path(os.environ.get("BASE_AI_DEVICE_AGENT_HOME", "~/.base-ai/device-agent")).expanduser()
CONFIG_PATH = APP_DIR / "config.json"
KEYCHAIN_SERVICE = "com.baseai.device-agent"


class ConfigError(RuntimeError):
    """表示 Agent 配置或 Keychain 凭据不可用。"""


@dataclass(slots=True)
class AgentConfig:
    """保存可安全落盘的 Agent 运行配置。"""

    backend_url: str
    agent_id: str
    installation_id: str = field(default_factory=lambda: str(uuid.uuid4()))
    ca_file: str | None = None

    def save(self, path: Path = CONFIG_PATH) -> None:
        """以仅当前用户可读权限原子保存非敏感配置。"""
        path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
        temporary = path.with_suffix(".tmp")
        temporary.write_text(json.dumps(asdict(self), ensure_ascii=False, indent=2), encoding="utf-8")
        temporary.chmod(0o600)
        temporary.replace(path)

    @classmethod
    def load(cls, path: Path = CONFIG_PATH) -> "AgentConfig":
        """读取并校验已配对配置。"""
        try:
            payload: dict[str, Any] = json.loads(path.read_text(encoding="utf-8"))
            config = cls(**payload)
        except (OSError, ValueError, TypeError) as exception:
            raise ConfigError("AGENT_CONFIG_INVALID") from exception
        if not config.backend_url.startswith(("http://", "https://")) or not config.agent_id:
            raise ConfigError("AGENT_CONFIG_INVALID")
        if config.ca_file is not None and not Path(config.ca_file).is_file():
            raise ConfigError("AGENT_CONFIG_INVALID")
        return config


def store_secret(agent_id: str, secret: str) -> None:
    """把每 Agent Secret 写入当前用户 macOS Keychain。"""
    if len(secret) < 32:
        raise ConfigError("AGENT_SECRET_INVALID")
    subprocess.run(
        ["security", "add-generic-password", "-U", "-s", KEYCHAIN_SERVICE, "-a", agent_id, "-w", secret],
        check=True, capture_output=True, text=True, timeout=15,
    )


def load_secret(agent_id: str) -> str:
    """从当前用户 macOS Keychain 读取 Agent Secret。"""
    try:
        result = subprocess.run(
            ["security", "find-generic-password", "-s", KEYCHAIN_SERVICE, "-a", agent_id, "-w"],
            check=True, capture_output=True, text=True, timeout=15,
        )
    except (OSError, subprocess.SubprocessError) as exception:
        raise ConfigError("AGENT_SECRET_UNAVAILABLE") from exception
    secret = result.stdout.strip()
    if len(secret) < 32:
        raise ConfigError("AGENT_SECRET_INVALID")
    return secret
