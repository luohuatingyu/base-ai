/** 将 API Key 文本统一为一行一个非空密钥。 */
export function normalizeApiKeys(value) {
  return String(value ?? '')
    .split(/[,\n]/)
    .map(key => key.trim())
    .filter(Boolean)
    .join('\n')
}
