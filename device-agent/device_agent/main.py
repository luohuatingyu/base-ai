"""Base AI 通用只读 iOS 设备 Agent 命令行和轮询主循环。"""

from __future__ import annotations

import argparse
import signal
import time
from typing import Any

from . import __version__
from .backend import BackendClient, BackendError
from .config import AgentConfig, ConfigError, store_secret
from .device_detect import detect_devices
from .diagnostics import collect_diagnostics
from .upgrade import upgrade


CAPABILITIES = [
    "DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING", "DETECT_DEVICE",
    "UPGRADE", "UPDATE_BACKEND_URL",
]


class AgentRuntime:
    """协调后端协议、只读设备发现、诊断和 Agent 自维护命令。"""

    def __init__(self, config: AgentConfig) -> None:
        self.config = config
        self.backend = BackendClient(config)
        self.running = True

    def stop(self, _signal: int, _frame: object) -> None:
        """在系统退出信号后结束下一轮轮询。"""
        self.running = False

    def run(self) -> None:
        """周期上报健康、只读同步设备并领取 Agent 维护命令。"""
        last_health = 0.0
        while self.running:
            try:
                if time.monotonic() - last_health >= 30:
                    self.report_health()
                    self.synchronize_devices()
                    last_health = time.monotonic()
                command = self.backend.lease_command(CAPABILITIES)
                if command:
                    self.execute(command)
            except (BackendError, ConfigError):
                time.sleep(5)
            except Exception:
                time.sleep(3)
            time.sleep(2)

    def report_health(self) -> None:
        """上报 Agent 与 Python 运行版本。"""
        self.backend.health({
            "status": "ONLINE", "agentVersion": __version__, "iosVersion": None,
            "lastErrorCode": None, "availableVersions": [],
        })

    def synchronize_devices(self) -> int:
        """只读发现设备并向后端同步不含原始 UDID 的完整快照。"""
        devices = detect_devices()
        self.backend.synchronize_devices([device.report(self.config.agent_id) for device in devices])
        return len(devices)

    def execute(self, command: dict[str, Any]) -> None:
        """执行不含设备控制动作的白名单命令并上报终态。"""
        command_id = int(command["commandId"])
        lease_token = str(command["leaseToken"])
        try:
            summary = self._dispatch(str(command.get("commandType") or ""),
                                     command.get("commandParams") or {})
            self.backend.report_command(command_id, lease_token, "COMPLETED", summary)
        except Exception as exception:
            code = str(exception)[:64] or "COMMAND_FAILED"
            self.backend.report_command(command_id, lease_token, "FAILED", "命令执行失败", code)

    def _dispatch(self, command_type: str, params: dict[str, Any]) -> str:
        """分派只读诊断、设备发现、改址和 Agent 自升级命令。"""
        if command_type in {"DIAGNOSTICS", "DETECT_SIGNING"}:
            self.backend.diagnostics(collect_diagnostics())
            return "只读环境诊断已上报"
        if command_type == "DETECT_DEVICE":
            return f"已只读同步 {self.synchronize_devices()} 台设备"
        if command_type in {"HEALTH_CHECK", "UPDATE_CONFIG"}:
            self.report_health()
            return "Agent 配置与健康状态已刷新"
        if command_type == "UPGRADE":
            version = upgrade(self.config.backend_url)
            return f"Agent 已切换到版本 {version}，等待服务重启"
        if command_type == "UPDATE_BACKEND_URL":
            return self._relocate(str(params.get("backendUrl") or ""))
        raise RuntimeError("COMMAND_NOT_SUPPORTED")

    def _relocate(self, backend_url: str) -> str:
        """先用原 Secret 探活新地址，再原子保存回连地址。"""
        if not backend_url.startswith(("http://", "https://")):
            raise RuntimeError("BACKEND_URL_INVALID")
        candidate = AgentConfig(backend_url.rstrip("/"), self.config.agent_id,
                                self.config.installation_id)
        BackendClient(candidate).request("GET", "/config")
        self.config.backend_url = candidate.backend_url
        self.config.save()
        self.backend = BackendClient(self.config)
        return "Agent 回连地址已更新"


def pair(backend_url: str, pairing_code: str) -> AgentConfig:
    """领取配对身份，把 Secret 写入 Keychain 并保存非敏感配置。"""
    result = BackendClient.claim(backend_url.rstrip("/"), pairing_code)
    agent_id = str(result.get("agentId") or "")
    secret = str(result.get("agentSecret") or "")
    effective_url = str(result.get("backendUrl") or backend_url).rstrip("/")
    if not agent_id or len(secret) < 32:
        raise BackendError("PAIRING_RESPONSE_INVALID")
    store_secret(agent_id, secret)
    config = AgentConfig(effective_url, agent_id)
    config.save()
    return config


def entrypoint() -> None:
    """解析 pair、run 和 diagnose 子命令。"""
    parser = argparse.ArgumentParser(prog="base-ai-device-agent")
    subcommands = parser.add_subparsers(dest="command", required=True)
    pairing = subcommands.add_parser("pair")
    pairing.add_argument("--backend-url", required=True)
    pairing.add_argument("--pairing-code", required=True)
    subcommands.add_parser("run")
    subcommands.add_parser("diagnose")
    arguments = parser.parse_args()
    if arguments.command == "pair":
        config = pair(arguments.backend_url, arguments.pairing_code)
        print(f"Paired Agent {config.agent_id}")
        return
    if arguments.command == "diagnose":
        import json
        print(json.dumps(collect_diagnostics(), ensure_ascii=False, indent=2))
        return
    runtime = AgentRuntime(AgentConfig.load())
    signal.signal(signal.SIGTERM, runtime.stop)
    signal.signal(signal.SIGINT, runtime.stop)
    runtime.run()


if __name__ == "__main__":
    entrypoint()
