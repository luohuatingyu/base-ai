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
