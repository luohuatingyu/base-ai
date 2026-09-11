/**
 * 创建 Registry 状态轮询器，保证同一时刻最多执行一次刷新。
 */
export function createRegistryStatusPoller ({
  refresh,
  intervalMs = 5000,
  setIntervalFn = setInterval,
  clearIntervalFn = clearInterval
}) {
  let timer = null
  let refreshInFlight = false

  /** 执行一次静默刷新；前一轮未完成时跳过，避免请求堆积。 */
  const tick = async () => {
    if (refreshInFlight) return
    refreshInFlight = true
    try {
      await refresh()
    } catch (_error) {
      // 后台轮询失败留待下一轮恢复，手动刷新负责向用户展示错误。
    } finally {
      refreshInFlight = false
    }
  }

  return {
    /** 启动轮询；重复调用不会创建多个定时器。 */
    start () {
      if (timer !== null) return
      timer = setIntervalFn(tick, intervalMs)
    },
    /** 停止轮询并释放定时器。 */
    stop () {
      if (timer === null) return
      clearIntervalFn(timer)
      timer = null
    }
  }
}
