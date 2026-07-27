export const HOST_RULE_TYPES = ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'ANY']

/** 创建一条默认的精确 Host 编辑行。 */
export function createHostRule() {
  return { type: 'EXACT', value: '' }
}

/** 将接口规则转换为编辑行，至少保留一条空白行。 */
export function toHostRuleRows(rules) {
  const rows = normalizeHostRules(rules)
  return rows.length ? rows : [createHostRule()]
}

/** 规范化规则类型和值，并按类型和值去重。 */
export function normalizeHostRules(rules) {
  const seen = new Set()
  const normalized = []
  for (const rule of rules || []) {
    const type = String(rule?.type || '').trim().toUpperCase()
    if (!HOST_RULE_TYPES.includes(type)) continue
    const value = type === 'ANY' ? null : String(rule?.value || '').trim().toLowerCase()
    if (type !== 'ANY' && !value) continue
    const key = `${type}:${value || ''}`
    if (seen.has(key)) continue
    seen.add(key)
    normalized.push({ type, value })
  }
  return normalized
}

/**
 * 创建只保留最新待提交值的自动保存器。
 * 文本修改可防抖提交，离散修改可立即提交；已有请求执行时不会并发发送下一次请求。
 */
export function createLatestAutoSaver(save, delay = 500) {
  let timer = null
  let pending
  let running = false
  let disposed = false

  /** 启动异步提交并消化由调用方处理过的异常，避免事件回调产生未处理拒绝。 */
  function launch() {
    void flush().catch(() => {})
  }

  /** 串行提交当前最新值，请求结束后继续处理等待中的立即修改。 */
  async function flush() {
    if (disposed || running || pending === undefined) return
    if (timer !== null) clearTimeout(timer)
    timer = null
    const value = pending
    pending = undefined
    running = true
    try {
      await save(value)
    } finally {
      running = false
      if (!disposed && pending !== undefined && timer === null) launch()
    }
  }

  /** 记录最新值，并按操作类型选择立即提交或等待防抖时间。 */
  function schedule(value, immediate = false) {
    if (disposed) return
    pending = value
    if (timer !== null) clearTimeout(timer)
    timer = null
    if (immediate) {
      launch()
      return
    }
    timer = setTimeout(() => {
      timer = null
      launch()
    }, delay)
  }

  /** 清除尚未发送的修改，供确认取消或请求失败后恢复已生效值。 */
  function clear() {
    pending = undefined
    if (timer !== null) clearTimeout(timer)
    timer = null
  }

  /** 页面卸载时终止计时器并禁止继续调度。 */
  function dispose() {
    clear()
    disposed = true
  }

  return { schedule, flush, clear, dispose }
}
