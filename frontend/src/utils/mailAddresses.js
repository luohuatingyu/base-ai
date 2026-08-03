/** 将邮箱输入行规范为去空白、去重后的提交数组。 */
export function normalizeMailAddressRows(rows) {
  return [...new Set((rows || []).map(value => String(value || '').trim()).filter(Boolean))]
}

/** 将接口邮箱数组转换为可编辑行，空数组至少保留一个输入框。 */
export function toMailAddressRows(addresses) {
  const normalized = normalizeMailAddressRows(addresses)
  return normalized.length ? [...normalized] : ['']
}
