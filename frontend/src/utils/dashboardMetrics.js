/** 仅接受有限的非负数值，避免将空值、布尔值误当成零。 */
export function metricValue(value) {
  if (typeof value !== 'number' && typeof value !== 'string') return null
  if (typeof value === 'string' && !value.trim()) return null
  const numeric = Number(value)
  return Number.isFinite(numeric) && numeric >= 0 ? numeric : null
}

/** 按当前语言显示计数，未知值使用统一占位符。 */
export function formatCount(value, locale) {
  const numeric = metricValue(value)
  return numeric === null ? '—' : Math.round(numeric).toLocaleString(locale)
}

/** 毫秒保持统一单位；缺失和非法延迟不能呈现为零耗时。 */
export function formatMilliseconds(value, locale) {
  const numeric = metricValue(value)
  return numeric === null ? '—' : `${Math.round(numeric).toLocaleString(locale)} ms`
}

/** 成功率以全部调用为分母，无调用或不一致的汇总不生成百分比。 */
export function formatSuccessRate(success, calls, locale) {
  const numerator = metricValue(success)
  const denominator = metricValue(calls)
  if (numerator === null || denominator === null || denominator === 0 || numerator > denominator) return '—'
  return (numerator / denominator).toLocaleString(locale, { style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1 })
}
