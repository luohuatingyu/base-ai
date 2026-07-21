import logging
import queue
import threading
import traceback
from datetime import datetime, timezone

import httpx

from app.config import Settings
from app.context import current_context

LOG_FORMAT = (
    "%(asctime)s | %(levelname)-7s | %(name)s | rid=%(request_id)s | "
    "jobId=%(job_id)s | pythonJobId=%(python_job_id)s | %(message)s"
)


class ContextFilter(logging.Filter):
    """把当前请求及任务编号注入每条日志。"""

    def filter(self, record: logging.LogRecord) -> bool:
        context = current_context()
        record.request_id = context.request_id if context else "-"
        record.job_id = context.parent_job_id if context else "-"
        record.python_job_id = context.python_job_id if context else "-"
        return True


class JavaLogShipHandler(logging.Handler):
    """通过有界队列和后台线程批量回传任务日志。"""

    def __init__(self, settings: Settings, capacity: int = 5000, batch_size: int = 100):
        super().__init__(logging._nameToLevel.get(settings.persist_level, logging.INFO))
        self.settings = settings
        self.batch_size = batch_size
        self.items: queue.Queue[dict | None] = queue.Queue(maxsize=capacity)
        self._dropped_count = 0
        self._dropped_lock = threading.Lock()
        self.worker = threading.Thread(target=self._run, name="java-log-shipper", daemon=True)
        self.worker.start()

    def emit(self, record: logging.LogRecord) -> None:
        """序列化带父任务编号的日志并非阻塞入队。"""
        context = current_context()
        if not context or not context.parent_job_id:
            return
        item = {
            "jobId": context.parent_job_id,
            "pythonJobId": context.python_job_id,
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
            self.items.put(None, timeout=1)
            self.worker.join(timeout=5)
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
        """发送单个日志批次，失败时丢弃以避免阻塞业务。"""
        try:
            client.post(
                f"{self.settings.backend_url}/api/internal/job-logs",
                headers={"X-Internal-Token": self.settings.internal_token},
                json={"logs": batch},
            ).raise_for_status()
        except Exception as exception:
            self._record_drop(len(batch))
            logging.getLogger(__name__).warning(
                "event=java_log_ship_failed batch_size=%d error=%s", len(batch), exception
            )

    def _record_drop(self, count: int = 1) -> None:
        """线程安全累计无法回传的日志数量。"""
        with self._dropped_lock:
            self._dropped_count += count

    def _report_drops(self) -> None:
        """在后台线程输出并清零日志丢弃统计。"""
        with self._dropped_lock:
            dropped = self._dropped_count
            self._dropped_count = 0
        if dropped:
            logging.getLogger(__name__).warning("event=python_job_log_dropped count=%d", dropped)


def setup_logging(settings: Settings) -> JavaLogShipHandler:
    """初始化控制台与 Java 日志回传处理器。"""
    root = logging.getLogger()
    root.setLevel(logging.INFO)
    root.handlers.clear()
    context_filter = ContextFilter()
    console = logging.StreamHandler()
    console.setFormatter(logging.Formatter(LOG_FORMAT, datefmt="%Y-%m-%d %H:%M:%S"))
    console.addFilter(context_filter)
    shipper = JavaLogShipHandler(settings)
    shipper.addFilter(context_filter)
    root.addHandler(console)
    root.addHandler(shipper)
    return shipper
