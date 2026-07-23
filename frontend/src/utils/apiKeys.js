/** 将 API Key 文本统一为一行一个非空密钥。 */
export function normalizeApiKeys(value) {
  return String(value ?? '')
    .split(/[,\n]/)
    .map(key => key.trim())
    .filter(Boolean)
    .join('\n')
}

/** 将 API Key 文本转换为逐行展示的密钥列表。 */
export function splitApiKeys(value) {
  return normalizeApiKeys(value).split('\n').filter(Boolean)
}

/** 复制单个 API Key，便于调用方处理浏览器权限或写入失败。 */
export async function copyApiKey(value, clipboard = navigator.clipboard) {
  if (!clipboard?.writeText) throw new Error('Clipboard is unavailable')
  await clipboard.writeText(value)
}
