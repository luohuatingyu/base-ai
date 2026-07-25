/** 返回同步结果对应的视觉状态类名。 */
export function healthStatusClass(status) {
  return {
    HEALTHY: 'is-healthy',
    WARNING: 'is-warning',
    SLOW: 'is-slow',
    FAILED: 'is-failed'
  }[status] || 'is-unknown'
}

/** 只有十秒内成功的模型供应不允许从同步结果中删除。 */
export function canRemoveModelProvider(status) {
  return status !== 'HEALTHY'
}

/** 将毫秒间隔逐级拆分为天、小时、分钟和秒。 */
export function formatSyncInterval(intervalMs, formatUnit) {
  const numericInterval = Number(intervalMs)
  let remainingSeconds = Number.isFinite(numericInterval) && numericInterval > 0 ? Math.ceil(numericInterval / 1000) : 3600
  const units = [
    ['day', 86400],
    ['hour', 3600],
    ['minute', 60],
    ['second', 1]
  ]

  return units.flatMap(([unit, seconds]) => {
    const value = Math.floor(remainingSeconds / seconds)
    remainingSeconds %= seconds
    return value ? [formatUnit(unit, value)] : []
  }).join(' ')
}
