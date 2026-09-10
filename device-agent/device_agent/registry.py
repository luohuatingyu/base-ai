"""Remote XPC Registry 的受限本地协议与 root 监督进程。"""

from __future__ import annotations

import argparse
import http.client
import json
import os
import re
import signal
import socket
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


HELPER_VERSION = "1.0.0"
ROOT_DIR = Path("/Library/Application Support/BaseAI/DeviceAgentRegistry")
SOCKET_PATH = ROOT_DIR / "registry.sock"
MAX_MESSAGE_BYTES = 8192
AGENT_ID_PATTERN = re.compile(r"[A-Za-z0-9][A-Za-z0-9._-]{2,63}")
UDID_PATTERN = re.compile(r"[A-Za-z0-9._:-]{1,128}")


class RegistryError(RuntimeError):
    """表示 Registry 配置、IPC 或进程管理失败。"""


@dataclass(frozen=True, slots=True)
class RegistryConfig:
    """仅在本机 IPC 中携带原始设备标识的 Registry 配置。"""

    agent_id: str
    port: int
    desired_state: str
    config_version: int
    device_udids: tuple[str, ...]

    @classmethod
    def from_payload(cls, payload: dict[str, Any]) -> "RegistryConfig":
        """严格解析后端字段与本机补充的设备列表。"""
        try:
            port = int(payload.get("effectivePort"))
            version = int(payload.get("configVersion"))
        except (TypeError, ValueError) as exception:
            raise RegistryError("REGISTRY_CONFIG_INVALID") from exception
        agent_id = str(payload.get("agentId") or "").strip()
        desired = str(payload.get("desiredState") or "").strip().upper()
        raw_devices = payload.get("deviceUdids") or []
        if not isinstance(raw_devices, list):
            raise RegistryError("REGISTRY_DEVICE_INVALID")
        devices = tuple(dict.fromkeys(str(value or "").strip() for value in raw_devices
                                      if str(value or "").strip()))
        if (not AGENT_ID_PATTERN.fullmatch(agent_id) or not 1024 <= port <= 65535
                or desired not in {"ONLINE", "OFFLINE"} or version < 1
                or len(devices) > 100 or any(not UDID_PATTERN.fullmatch(value) for value in devices)):
            raise RegistryError("REGISTRY_CONFIG_INVALID")
        return cls(agent_id, port, desired, version, devices)

    def payload(self) -> dict[str, object]:
        """生成 root Helper 使用的固定字段对象。"""
        return {"agentId": self.agent_id, "effectivePort": self.port,
                "desiredState": self.desired_state, "configVersion": self.config_version,
                "deviceUdids": list(self.device_udids)}


class RegistryClient:
    """通过 root-owned Unix Socket 调用三种固定 Registry 操作。"""

    def __init__(self, socket_path: Path = SOCKET_PATH) -> None:
        self.socket_path = socket_path

    def apply(self, config: RegistryConfig) -> dict[str, Any]:
        """把期望配置和当前内存设备列表交给特权服务。"""
        return self._request({"operation": "APPLY_CONFIG", "config": config.payload()})

    def action(self, action: str) -> dict[str, Any]:
        """执行上线、下线或重建中的一个固定动作。"""
        normalized = str(action or "").upper()
        if normalized not in {"ONLINE", "OFFLINE", "RECREATE"}:
            raise RegistryError("REGISTRY_ACTION_INVALID")
        return self._request({"operation": "ACTION", "action": normalized})

    def status(self) -> dict[str, Any]:
        """读取不含原始设备标识和日志的状态。"""
        return self._request({"operation": "STATUS"})

    def _request(self, payload: dict[str, Any]) -> dict[str, Any]:
        """发送单行有界 JSON 并验证响应。"""
        request = json.dumps(payload, separators=(",", ":")).encode() + b"\n"
        if len(request) > MAX_MESSAGE_BYTES:
            raise RegistryError("REGISTRY_REQUEST_TOO_LARGE")
        try:
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as connection:
                connection.settimeout(10)
                connection.connect(str(self.socket_path))
                connection.sendall(request)
                chunks = bytearray()
                while b"\n" not in chunks and len(chunks) <= MAX_MESSAGE_BYTES:
                    block = connection.recv(2048)
                    if not block:
                        break
                    chunks.extend(block)
        except OSError as exception:
            raise RegistryError("REGISTRY_HELPER_UNAVAILABLE") from exception
        try:
            response = json.loads(bytes(chunks).split(b"\n", 1)[0])
        except (ValueError, json.JSONDecodeError) as exception:
            raise RegistryError("REGISTRY_PROTOCOL_INVALID") from exception
        if not isinstance(response, dict) or not response.get("ok"):
            raise RegistryError(str(response.get("errorCode") or "REGISTRY_OPERATION_FAILED"))
        status = response.get("status")
        if not isinstance(status, dict):
            raise RegistryError("REGISTRY_PROTOCOL_INVALID")
        return status


class RegistrySupervisor:
    """以 root 身份持有 Appium XCUITest tunnel-creation 子进程。"""

    def __init__(self, root_dir: Path = ROOT_DIR,
                 popen: Callable[..., Any] = subprocess.Popen) -> None:
        self.root_dir = root_dir
        self.state_file = root_dir / "state.json"
        self.log_file = root_dir / "logs" / "registry.log"
        self.appium = root_dir / "runtime" / "appium" / "bin" / "appium"
        self.node_bin = root_dir / "runtime" / "node" / "bin"
        self.popen = popen
        self.process: Any | None = None
        self.config = RegistryConfig("base-ai-agent", 42314, "OFFLINE", 1, ())
        self.state = "OFFLINE"
        self.error_code: str | None = None
        self._load()

    def handle(self, payload: dict[str, Any]) -> dict[str, Any]:
        """分派配置、动作与状态三类固定操作。"""
        operation = str(payload.get("operation") or "").upper()
        if operation == "APPLY_CONFIG":
            raw = payload.get("config")
            if not isinstance(raw, dict):
                raise RegistryError("REGISTRY_CONFIG_INVALID")
            config = RegistryConfig.from_payload(raw)
            if (config.agent_id == self.config.agent_id
                    and config.config_version < self.config.config_version):
                raise RegistryError("REGISTRY_CONFIG_STALE")
            changed = config.port != self.config.port or config.device_udids != self.config.device_udids
            if changed:
                self._stop("RESTARTING")
            self.config = config
            self._persist()
        elif operation == "ACTION":
            action = str(payload.get("action") or "").upper()
            if action not in {"ONLINE", "OFFLINE", "RECREATE"}:
                raise RegistryError("REGISTRY_ACTION_INVALID")
            if action == "RECREATE" and self.config.desired_state != "ONLINE":
                raise RegistryError("REGISTRY_RECREATE_REQUIRES_ONLINE")
            if action in {"ONLINE", "OFFLINE"}:
                self.config = RegistryConfig(self.config.agent_id, self.config.port, action,
                                             self.config.config_version, self.config.device_udids)
                self._persist()
            if action in {"OFFLINE", "RECREATE"}:
                self._stop("OFFLINE" if action == "OFFLINE" else "RESTARTING")
        elif operation != "STATUS":
            raise RegistryError("REGISTRY_OPERATION_INVALID")
        self.reconcile()
        return self.status()

    def reconcile(self) -> None:
        """对齐持久期望状态和当前受管子进程。"""
        if self.config.desired_state == "OFFLINE":
            self._stop("OFFLINE")
            return
        if self.process is not None and self.process.poll() is not None:
            self.process = None
            self.state = "ERROR"
            self.error_code = "REGISTRY_PROCESS_EXITED"
        if self.process is None:
            self._start()
            if self.process is None:
                return
        count = _probe(self.config.port)
        if count is not None:
            self.state = "ONLINE"
            self.error_code = None

    def status(self) -> dict[str, object]:
        """生成允许上报后端的脱敏状态。"""
        count = _probe(self.config.port) if self.process is not None else None
        return {"state": self.state, "effectivePort": self.config.port,
                "tunnelCount": count or 0, "helperVersion": HELPER_VERSION,
                "errorCode": self.error_code}

    def _start(self) -> None:
        """使用 root-owned 文件和固定参数启动 Registry。"""
        if not self.config.device_udids:
            self.state, self.error_code = "ERROR", "REGISTRY_DEVICE_NOT_CONFIGURED"
            return
        if not self.appium.is_file():
            self.state, self.error_code = "ERROR", "REGISTRY_RUNTIME_MISSING"
            return
        self.log_file.parent.mkdir(parents=True, exist_ok=True)
        arguments = [str(self.appium), "driver", "run", "xcuitest", "tunnel-creation", "--",
                     "--tunnel-registry-port", str(self.config.port)]
        for udid in self.config.device_udids:
            arguments.extend(["--udid", udid])
        arguments.extend(["--disconnect-retry-max-attempts", "0"])
        environment = {**os.environ, "PATH": f"{self.node_bin}:/usr/bin:/bin:/usr/sbin:/sbin",
                       "HOME": str(self.root_dir),
                       "APPIUM_HOME": str(self.root_dir / "appium-home")}
        try:
            with self.log_file.open("ab", buffering=0) as output:
                os.chmod(self.log_file, 0o600)
                self.process = self.popen(arguments, stdin=subprocess.DEVNULL,
                                          stdout=output, stderr=output, env=environment,
                                          cwd=str(self.root_dir), start_new_session=True)
            self.state, self.error_code = "STARTING", None
        except OSError:
            self.process = None
            self.state, self.error_code = "ERROR", "REGISTRY_START_FAILED"

    def _stop(self, state: str) -> None:
        """仅终止本监督器持有的进程组。"""
        process, self.process = self.process, None
        if process is not None and process.poll() is None:
            try:
                os.killpg(process.pid, signal.SIGTERM)
                process.wait(timeout=5)
            except (OSError, subprocess.TimeoutExpired):
                try:
                    os.killpg(process.pid, signal.SIGKILL)
                except OSError:
                    pass
        self.state, self.error_code = state, None

    def _persist(self) -> None:
        """原子保存 root-only 期望配置。"""
        temporary = self.state_file.with_suffix(".tmp")
        temporary.write_text(json.dumps(self.config.payload(), separators=(",", ":")), encoding="utf-8")
        os.chmod(temporary, 0o600)
        os.replace(temporary, self.state_file)

    def _load(self) -> None:
        """加载此前持久化的期望配置，损坏文件按离线处理。"""
        try:
            self.config = RegistryConfig.from_payload(json.loads(self.state_file.read_text("utf-8")))
        except (OSError, ValueError, RegistryError):
            return


def serve(authorized_uid: int, socket_path: Path = SOCKET_PATH) -> None:
    """运行仅授权安装用户访问的 root Unix Socket 服务。"""
    if os.geteuid() != 0 or authorized_uid <= 0:
        raise RegistryError("REGISTRY_ROOT_REQUIRED")
    socket_path.unlink(missing_ok=True)
    server = socket.socket(socket.AF_UNIX, socket.SOCK_STREAM)
    server.bind(str(socket_path))
    os.chown(socket_path, authorized_uid, -1)
    os.chmod(socket_path, 0o600)
    server.listen(8)
    server.settimeout(1)
    supervisor = RegistrySupervisor(socket_path.parent)
    running = True

    def stop(_signal: int, _frame: object) -> None:
        """收到退出信号后终止循环。"""
        nonlocal running
        running = False

    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    try:
        while running:
            supervisor.reconcile()
            try:
                connection, _ = server.accept()
            except TimeoutError:
                continue
            with connection:
                try:
                    raw = connection.recv(MAX_MESSAGE_BYTES + 1)
                    if len(raw) > MAX_MESSAGE_BYTES:
                        raise RegistryError("REGISTRY_REQUEST_TOO_LARGE")
                    payload = json.loads(raw.split(b"\n", 1)[0] or b"{}")
                    if not isinstance(payload, dict):
                        raise RegistryError("REGISTRY_PROTOCOL_INVALID")
                    response = {"ok": True, "status": supervisor.handle(payload)}
                except (ValueError, json.JSONDecodeError, RegistryError) as exception:
                    response = {"ok": False, "errorCode": str(exception)}
                connection.sendall(json.dumps(response, separators=(",", ":")).encode() + b"\n")
    finally:
        supervisor._stop("OFFLINE")
        server.close()
        socket_path.unlink(missing_ok=True)


def _probe(port: int) -> int | None:
    """通过 Registry HTTP 目录探测当前隧道数量。"""
    connection = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        connection.request("GET", "/remotexpc/tunnels")
        response = connection.getresponse()
        payload = json.loads(response.read(1024 * 1024))
        tunnels = payload.get("tunnels") if isinstance(payload, dict) else None
        return len(tunnels) if response.status == 200 and isinstance(tunnels, dict) else None
    except (OSError, ValueError, http.client.HTTPException):
        return None
    finally:
        connection.close()


def entrypoint() -> None:
    """解析 LaunchDaemon 固定参数并启动特权服务。"""
    parser = argparse.ArgumentParser()
    parser.add_argument("--serve", action="store_true", required=True)
    parser.add_argument("--authorized-uid", type=int, required=True)
    arguments = parser.parse_args()
    serve(arguments.authorized_uid)


if __name__ == "__main__":
    entrypoint()
