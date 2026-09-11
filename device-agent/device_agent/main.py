"""Base AI 通用 iOS 设备自动化 Agent 命令行和轮询主循环。"""

from __future__ import annotations

import argparse
import json
import signal
import time
from typing import Any

from . import __version__
from .backend import BackendClient, BackendError
from .config import AgentConfig, ConfigError, store_secret
from .device_detect import detect_devices
from .diagnostics import collect_diagnostics
from .registry import RegistryClient, RegistryConfig, RegistryError
from .signing_detect import detect_signing_candidates
from .upgrade import active_version, available_versions, upgrade
from .ida import IdaConfig, IdaError, IdaRuntime, xcuitest_driver_version


CAPABILITIES = [
    "DIAGNOSTICS", "UPDATE_CONFIG", "HEALTH_CHECK", "DETECT_SIGNING", "DETECT_DEVICE",
    "SETUP_IDA", "START_IDA", "REGISTRY_ONLINE", "REGISTRY_OFFLINE",
    "REGISTRY_RECREATE", "UPGRADE", "UPDATE_BACKEND_URL",
]


class AgentRuntime:
    """协调后端协议、多设备 IDA、Registry 和 Agent 自维护命令。"""

    def __init__(self, config: AgentConfig) -> None:
        self.config = config
        self.backend = BackendClient(config)
        self.ida = IdaRuntime()
        self.registry = RegistryClient()
        self.running = True

    def stop(self, _signal: int, _frame: object) -> None:
        """在系统退出信号后结束下一轮轮询。"""
        self.running = False
        self.ida.close_all()

    def run(self) -> None:
        """周期上报健康、同步设备状态并领取自动化或维护命令。"""
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
            "status": "ONLINE", "agentVersion": active_version(__version__), "iosVersion": None,
            "xcuitestDriverVersion": xcuitest_driver_version(),
            "lastErrorCode": None, "availableVersions": available_versions(),
        })

    def synchronize_devices(self) -> int:
        """发现设备、同步脱敏自动化状态并应用端口与 Registry 配置。"""
        devices = detect_devices()
        self.ida.refresh(self.config.agent_id, devices)
        response = self.backend.synchronize_devices([
            device.report(self.config.agent_id, self.ida.report(device.device_id(self.config.agent_id)))
            for device in devices
        ])
        self.ida.apply_assignments(response)
        self._reconcile_registry(devices)
        return len(devices)

    def execute(self, command: dict[str, Any]) -> None:
        """执行不含设备控制动作的白名单命令并上报终态。"""
        command_id = int(command["commandId"])
        lease_token = str(command["leaseToken"])
        command_type = str(command.get("commandType") or "")
        try:
            summary = self._dispatch(command_type, command.get("commandParams") or {},
                                     str(command.get("targetDeviceId") or ""))
            self.backend.report_command(command_id, lease_token, "COMPLETED", summary)
            if command_type == "UPGRADE":
                self.running = False
        except Exception as exception:
            code = str(exception)[:64] or "COMMAND_FAILED"
            target = str(command.get("targetDeviceId") or "")
            if target:
                self.ida.fail(target, code)
            self.backend.report_command(command_id, lease_token, "FAILED", "命令执行失败", code)

    def _dispatch(self, command_type: str, params: dict[str, Any], target_device_id: str = "") -> str:
        """分派诊断、IDA、Registry、改址和 Agent 自升级命令。"""
        if command_type == "DIAGNOSTICS":
            self.backend.diagnostics(collect_diagnostics())
            return "只读环境诊断已上报"
        if command_type == "DETECT_SIGNING":
            identities = [item.as_payload() for item in detect_signing_candidates()]
            return json.dumps({"identities": identities}, ensure_ascii=False, separators=(",", ":"))
        if command_type == "DETECT_DEVICE":
            count = self.synchronize_devices()
            return json.dumps({"synchronized": True, "deviceCount": count}, separators=(",", ":"))
        if command_type in {"HEALTH_CHECK", "UPDATE_CONFIG"}:
            self.report_health()
            self.synchronize_devices()
            return "Agent 配置与健康状态已刷新"
        if command_type == "SETUP_IDA":
            config = IdaConfig.from_payload(self.backend.ida_config())
            # xcodebuild 构建前先做签名预检：缺签名时回稳定错误码，
            # 控制台按码展示补救指引，而不是透传 Appium 的长日志
            if config.launch_mode == "XCODEBUILD" and not (
                config.xcode_org_id and config.xcode_signing_id):
                raise IdaError("SIGNING_IDENTITY_MISSING")
            return self.ida.setup(target_device_id, config)
        if command_type == "START_IDA":
            return self.ida.start(target_device_id, IdaConfig.from_payload(self.backend.ida_config()))
        if command_type in {"REGISTRY_ONLINE", "REGISTRY_OFFLINE", "REGISTRY_RECREATE"}:
            action = command_type.removeprefix("REGISTRY_")
            self._apply_registry_config()
            status = self.registry.action(action)
            self.backend.registry_status(status)
            return f"Remote XPC Registry 已执行 {action}"
        if command_type == "UPGRADE":
            target_version = str(params.get("targetVersion") or "") or None
            version = upgrade(self.config.backend_url, target_version, self.config.ca_file)
            return json.dumps(
                {"status": "SWITCHED", "version": version, "summary": "等待服务重启"},
                ensure_ascii=False, separators=(",", ":"),
            )
        if command_type == "UPDATE_BACKEND_URL":
            return self._relocate(str(params.get("backendUrl") or ""))
        raise RuntimeError("COMMAND_NOT_SUPPORTED")

    def _reconcile_registry(self, devices: list[Any]) -> None:
        """把后端期望和本机原始设备列表应用到受限 root Helper。"""
        try:
            status = self._apply_registry_config(devices)
        except RegistryError as exception:
            status = {"state": "NOT_INSTALLED", "effectivePort": None, "tunnelCount": 0,
                      "helperVersion": None, "errorCode": str(exception)[:64]}
        self.backend.registry_status(status)

    def _apply_registry_config(self, devices: list[Any] | None = None) -> dict[str, Any]:
        """拉取 Registry 配置并仅在本机补充所有已发现设备的原始 UDID。"""
        payload = dict(self.backend.registry_config())
        candidates = devices if devices is not None else list(self.ida.devices.values())
        payload["agentId"] = self.config.agent_id
        payload["deviceUdids"] = [device.udid for device in candidates]
        return self.registry.apply(RegistryConfig.from_payload(payload))

    def _relocate(self, backend_url: str) -> str:
        """先用原 Secret 探活新地址，再原子保存回连地址。"""
        if not backend_url.startswith(("http://", "https://")):
            raise RuntimeError("BACKEND_URL_INVALID")
        candidate = AgentConfig(backend_url.rstrip("/"), self.config.agent_id,
                                self.config.installation_id, self.config.ca_file)
        BackendClient(candidate).request("GET", "/config")
        self.config.backend_url = candidate.backend_url
        self.config.save()
        self.backend = BackendClient(self.config)
        return "Agent 回连地址已更新"


def pair(backend_url: str, pairing_code: str, ca_file: str | None = None) -> AgentConfig:
    """领取配对身份，把 Secret 写入 Keychain 并保存非敏感配置。"""
    result = BackendClient.claim(backend_url.rstrip("/"), pairing_code, ca_file)
    agent_id = str(result.get("agentId") or "")
    secret = str(result.get("agentSecret") or "")
    effective_url = str(result.get("backendUrl") or backend_url).rstrip("/")
    if not agent_id or len(secret) < 32:
        raise BackendError("PAIRING_RESPONSE_INVALID")
    store_secret(agent_id, secret)
    config = AgentConfig(effective_url, agent_id, ca_file=ca_file)
    config.save()
    return config


def entrypoint() -> None:
    """解析 pair、run 和 diagnose 子命令。"""
    parser = argparse.ArgumentParser(prog="base-ai-device-agent")
    subcommands = parser.add_subparsers(dest="command", required=True)
    pairing = subcommands.add_parser("pair")
    pairing.add_argument("--backend-url", required=True)
    pairing.add_argument("--pairing-code", required=True)
    pairing.add_argument("--ca-file")
    subcommands.add_parser("run")
    subcommands.add_parser("diagnose")
    relocate = subcommands.add_parser("set-server")
    relocate.add_argument("--backend-url", required=True)
    relocate.add_argument("--ca-file")
    arguments = parser.parse_args()
    if arguments.command == "pair":
        config = pair(arguments.backend_url, arguments.pairing_code, arguments.ca_file)
        print(f"Paired Agent {config.agent_id}")
        return
    if arguments.command == "diagnose":
        print(json.dumps(collect_diagnostics(), ensure_ascii=False, indent=2))
        return
    if arguments.command == "set-server":
        config = AgentConfig.load()
        if arguments.ca_file:
            config.ca_file = arguments.ca_file
        runtime = AgentRuntime(config)
        print(runtime._relocate(arguments.backend_url))
        return
    runtime = AgentRuntime(AgentConfig.load())
    signal.signal(signal.SIGTERM, runtime.stop)
    signal.signal(signal.SIGINT, runtime.stop)
    runtime.run()


if __name__ == "__main__":
    entrypoint()
