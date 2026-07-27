export const THINKING_LEVELS = ['LOW', 'MEDIUM', 'HIGH', 'EXTRA_HIGH', 'MAX', 'ULTRA']

const THINKING_LEVEL_SET = new Set(THINKING_LEVELS)

/** 解析思考等级映射，兼容逗号或换行分隔，并忽略未知等级和空值。 */
export function parseThinkingMappings(value) {
  const result = {}
  String(value || '').split(/[,\n]/).forEach(item => {
    const separatorIndex = item.indexOf('=')
    if (separatorIndex < 0) return
    const level = item.slice(0, separatorIndex).trim().toUpperCase()
    const mappedValue = item.slice(separatorIndex + 1).trim()
    if (THINKING_LEVEL_SET.has(level) && mappedValue) result[level] = mappedValue
  })
  return result
}

/** 按标准等级顺序序列化非空映射，保持后端接口使用的字符串协议。 */
export function serializeThinkingMappings(mapping) {
  return THINKING_LEVELS
    .filter(level => String(mapping?.[level] || '').trim())
    .map(level => `${level}=${String(mapping[level]).trim()}`)
    .join(',')
}

/** 将映射字符串转换为按标准等级排序的展示条目。 */
export function thinkingMappingEntries(value) {
  const mappings = parseThinkingMappings(value)
  return THINKING_LEVELS
    .filter(level => mappings[level])
    .map(level => ({ level, value: mappings[level] }))
}
