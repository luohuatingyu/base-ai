import asyncio
import contextlib
import logging

import httpx

from app.config import Settings

logger = logging.getLogger(__name__)


class JobRuntimeRegistry:
    """登记正在运行的异步任务并支持按 Python 任务编号取消。"""

    def __init__(self) -> None:
        self._tasks: dict[str, asyncio.Task] = {}
        self._lock = asyncio.Lock()

    async def register(self, job_id: str, task: asyncio.Task) -> None:
        """登记当前异步任务。"""
        async with self._lock:
            self._tasks[job_id] = task

    async def remove(self, job_id: str) -> None:
        """移除已完成的异步任务。"""
        async with self._lock:
            self._tasks.pop(job_id, None)

    async def cancel(self, job_id: str) -> bool:
        """取消指定异步任务且不终止共享 Worker 进程。"""
        async with self._lock:
            task = self._tasks.get(job_id)
        if task is None or task.done():
            return False
        task.cancel()
        return True


class JavaJobReporter:
    """向 Java 后端回传子任务状态和周期心跳。"""

    def __init__(self, settings: Settings) -> None:
        self.settings = settings
        self.client = httpx.AsyncClient(timeout=5)

    async def report(self, python_job_id: str, status: str, error_message: str | None = None) -> None:
        """回传单次任务状态，后端不可用时仅记录警告。"""
        try:
            await self.client.post(
                f"{self.settings.backend_url}/api/internal/jobs/python/events",
                headers={"X-Internal-Token": self.settings.internal_token},
                json={
                    "pythonJobId": python_job_id,
                    "status": status,
                    "workerInstanceId": self.settings.instance_id,
                    "errorMessage": error_message,
                },
            )
        except Exception as exception:
            logger.warning("event=python_job_report_failed job_id=%s error=%s", python_job_id, exception)

    async def heartbeat(self, python_job_id: str) -> None:
        """任务运行期间每十五秒发送一次心跳。"""
        try:
            while True:
                await asyncio.sleep(15)
                await self.report(python_job_id, "RUNNING")
        except asyncio.CancelledError:
            return

    async def close(self) -> None:
        """关闭内部状态回传连接池。"""
        await self.client.aclose()


async def stop_heartbeat(task: asyncio.Task | None) -> None:
    """取消并等待心跳协程安全结束。"""
    if task is None:
        return
    task.cancel()
    with contextlib.suppress(asyncio.CancelledError):
        await task
