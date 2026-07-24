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
