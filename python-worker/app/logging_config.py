import json
import logging
import queue
import re
import threading
import time
import traceback
from datetime import datetime, timezone

import httpx

from app.config import Settings
from app.context import current_context

MAX_LOG_VALUE_LENGTH = 4000
SHIP_MAX_ATTEMPTS = 5
SHIP_RETRY_BASE_SECONDS = 0.25
SENSITIVE_PATTERNS = (
    re.compile(r"(?i)(authorization\s*[:=]\s*bearer\s+)[^\s,;]+"),
    re.compile(r"(?i)((?:api[_-]?key|token|secret|password)\s*[:=]\s*)[^\s,;]+"),
)


def sanitize_log_text(value: object, max_length: int = MAX_LOG_VALUE_LENGTH) -> str:
    """脱敏并截断可能包含凭据或超长内容的日志文本。"""
    text = str(value)
    for pattern in SENSITIVE_PATTERNS:
        text = pattern.sub(r"\1***", text)
    if len(text) <= max_length:
        return text
    return f"{text[:max_length]}...[truncated:{len(text) - max_length}]"


class JsonLogFormatter(logging.Formatter):
    """将控制台日志编码为便于采集的单行 JSON。"""

    def format(self, record: logging.LogRecord) -> str:
        payload = {
            "timestamp": datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
            "level": record.levelname,
            "logger": record.name,
            "thread": record.threadName,
            "requestId": getattr(record, "request_id", "-"),
            "traceId": getattr(record, "trace_id", "-"),
            "pythonTraceId": getattr(record, "python_trace_id", "-"),
            "message": sanitize_log_text(record.getMessage()),
        }
        if record.exc_info:
            payload["throwable"] = sanitize_log_text("".join(traceback.format_exception(*record.exc_info)), 16000)
        return json.dumps(payload, ensure_ascii=False, separators=(",", ":"))


class ContextFilter(logging.Filter):
    """把当前请求及任务编号注入每条日志。"""

    def filter(self, record: logging.LogRecord) -> bool:
        context = current_context()
        record.request_id = context.request_id if context else "-"
        record.trace_id = context.parent_trace_id if context else "-"
        record.python_trace_id = context.python_trace_id if context else "-"
        return True


class JavaLogShipHandler(logging.Handler):
    """通过有界队列和后台线程批量回传链路日志。"""

    def __init__(self, settings: Settings, capacity: int = 5000, batch_size: int = 100):
        super().__init__(logging.getLevelNamesMapping().get(settings.persist_level, logging.INFO))
        self.settings = settings
        self.batch_size = batch_size
        self.items: queue.Queue[dict | None] = queue.Queue(maxsize=capacity)
        self._dropped_count = 0
        self._dropped_lock = threading.Lock()
        self._last_drop_warning_at = 0.0
        self._last_drop_error: str | None = None
        self.worker = threading.Thread(target=self._run, name="java-log-shipper", daemon=True)
        self.worker.start()

    def emit(self, record: logging.LogRecord) -> None:
        """序列化带父链路编号的日志并非阻塞入队。"""
        context = current_context()
        if not context or not context.parent_trace_id:
            return
        item = {
            "traceId": context.parent_trace_id,
            "pythonTraceId": context.python_trace_id,
            "level": record.levelname,
            "loggerName": record.name,
            "message": record.getMessage(),
            "threadName": record.threadName,
            "throwable": "".join(traceback.format_exception(*record.exc_info)) if record.exc_info else None,
            "loggedAt": datetime.fromtimestamp(record.created, timezone.utc).isoformat(),
        }
        try:
            self.items.put_nowait(item)
        except queue.Full:
            self._record_drop()

    def close(self) -> None:
        """通知后台线程完成剩余日志发送。"""
        try:
            try:
                self.items.put(None, timeout=5)
            except queue.Full:
                self._record_drop(self.items.qsize(), "shutdown_queue_timeout")
            self.worker.join(timeout=20)
            if self.worker.is_alive():
                self._record_drop(self.items.qsize(), "shutdown_flush_timeout")
            self._report_drops(force=True)
        finally:
            super().close()

    def _run(self) -> None:
        """持续聚合日志批次并发送到 Java 内部接口。"""
        with httpx.Client(timeout=10) as client:
            while True:
                first = self.items.get()
                if first is None:
                    return
                batch = [first]
                while len(batch) < self.batch_size:
                    try:
                        next_item = self.items.get_nowait()
                    except queue.Empty:
                        break
                    if next_item is None:
                        self._send(client, batch)
                        self._report_drops()
                        return
                    batch.append(next_item)
                self._send(client, batch)
                self._report_drops()

    def _send(self, client: httpx.Client, batch: list[dict]) -> None:
        """最多重试五次发送日志批次，最终失败时记录丢弃数量。"""
        last_error = "unknown"
        for attempt in range(1, SHIP_MAX_ATTEMPTS + 1):
            try:
                client.post(
                    f"{self.settings.backend_url}/api/internal/trace-logs",
                    headers={"X-Internal-Token": self.settings.internal_token},
                    json={"logs": batch},
                ).raise_for_status()
                return
            except Exception as exception:
                last_error = sanitize_log_text(exception, 1000)
                if attempt < SHIP_MAX_ATTEMPTS:
                    time.sleep(SHIP_RETRY_BASE_SECONDS * (2 ** (attempt - 1)))
        self._record_drop(len(batch), f"attempts={SHIP_MAX_ATTEMPTS} error={last_error}")

    def _record_drop(self, count: int = 1, error: str | None = None) -> None:
        """线程安全累计无法回传的日志数量。"""
        with self._dropped_lock:
            self._dropped_count += count
            if error:
                self._last_drop_error = sanitize_log_text(error, 1000)

    def _report_drops(self, force: bool = False) -> None:
        """在后台线程限频输出并清零日志丢弃统计。"""
        current_time = time.monotonic()
        with self._dropped_lock:
            if not force and current_time - self._last_drop_warning_at < 30:
                return
            dropped = self._dropped_count
            error = self._last_drop_error
            self._dropped_count = 0
            self._last_drop_error = None
            self._last_drop_warning_at = current_time
        if dropped:
            logging.getLogger(__name__).warning(
                "event=python_trace_log_dropped count=%d error=%s", dropped, error or "queue_full"
            )


def setup_logging(settings: Settings) -> JavaLogShipHandler:
    """初始化控制台与 Java 日志回传处理器。"""
    root = logging.getLogger()
    root.setLevel(logging.getLevelNamesMapping().get(settings.log_level, logging.INFO))
    root.handlers.clear()
    context_filter = ContextFilter()
    console = logging.StreamHandler()
    console.setFormatter(JsonLogFormatter())
    console.addFilter(context_filter)
    shipper = JavaLogShipHandler(settings)
    shipper.addFilter(context_filter)
    root.addHandler(console)
    root.addHandler(shipper)
    for logger_name in ("uvicorn", "uvicorn.error"):
        framework_logger = logging.getLogger(logger_name)
        framework_logger.handlers.clear()
        framework_logger.propagate = True
    logging.getLogger("uvicorn.access").disabled = True
    return shipper
