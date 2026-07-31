const LOG_FIELD_PATTERN = /(?:^|\s)([A-Za-z_][A-Za-z0-9_.-]*)=/g

/** 将日志消息拆分为可独立展示和复制的键值字段。 */
export function parseTaskLogFields(message) {
  const text = String(message ?? '').trim()
  if (!text) return []

  const matches = [...text.matchAll(LOG_FIELD_PATTERN)]
  if (!matches.length || matches[0].index !== 0) return []

  return matches.map((match, index) => {
    const valueStart = match.index + match[0].length
    const valueEnd = matches[index + 1]?.index ?? text.length
    return formatTaskLogField(match[1], text.slice(valueStart, valueEnd).trim())
  })
}

/** 格式化单个日志字段，并识别需要折叠展示的 JSON 内容。 */
function formatTaskLogField(name, rawValue) {
  const jsonValue = parseJsonValue(rawValue)
  if (!jsonValue.matched) {
    return {
      name,
      rawValue,
      compactValue: rawValue,
      displayValue: rawValue,
      isJson: false,
      isCompact: !rawValue.includes('\n') && rawValue.length <= 40
    }
  }

  return {
    name,
    rawValue,
    compactValue: JSON.stringify(jsonValue.value),
    displayValue: JSON.stringify(jsonValue.value, null, 2),
    isJson: true,
    isCompact: false
  }
}

/** 仅将对象或数组文本识别为 JSON，避免普通数字和布尔值被折叠。 */
function parseJsonValue(value) {
  if (!value.startsWith('{') && !value.startsWith('[')) return { matched: false }
  try {
    const parsed = JSON.parse(value)
    return parsed !== null && typeof parsed === 'object'
      ? { matched: true, value: parsed }
      : { matched: false }
  } catch {
    return { matched: false }
  }
}
