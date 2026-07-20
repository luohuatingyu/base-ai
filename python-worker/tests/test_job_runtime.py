import asyncio

from app.job_runtime import JobRuntimeRegistry


def test_registry_cancels_registered_task():
    """验证任务注册表只取消指定异步任务。"""
    async def scenario():
        registry = JobRuntimeRegistry()
        started = asyncio.Event()

        async def work():
            started.set()
            await asyncio.sleep(30)

        task = asyncio.create_task(work())
        await started.wait()
        await registry.register("job-1", task)
        assert await registry.cancel("job-1") is True
        try:
            await task
        except asyncio.CancelledError:
            pass
        assert task.cancelled()

    asyncio.run(scenario())
