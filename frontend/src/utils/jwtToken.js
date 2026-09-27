const AUTH_CLAIM = 'https://api.openai.com/auth'

/** 将 Base64URL 字符串解码为 UTF-8 文本，拒绝损坏的 JWT 片段。 */
function decodeBase64Url(value, partName) {
  const normalized = String(value || '').replace(/-/g, '+').replace(/_/g, '/')
  if (!normalized || !/^[A-Za-z0-9+/]*={0,2}$/.test(normalized)) {
    throw new Error(`${partName} 不是有效的 Base64URL 编码`)
  }
  const padded = normalized.padEnd(Math.ceil(normalized.length / 4) * 4, '=')
  let binary
  try {
    binary = atob(padded)
  } catch {
    throw new Error(`${partName} 不是有效的 Base64URL 编码`)
  }
  const bytes = Uint8Array.from(binary, character => character.charCodeAt(0))
  try {
    return new TextDecoder().decode(bytes)
  } catch {
    throw new Error(`${partName} 不是有效的 UTF-8 文本`)
  }
}

/** 将 JSON 值编码为不含填充符的 Base64URL 字符串。 */
function encodeBase64Url(value) {
  const bytes = new TextEncoder().encode(JSON.stringify(value))
  let binary = ''
  bytes.forEach(byte => { binary += String.fromCharCode(byte) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

/** 解析 JWT 的 Header、Payload 和签名，并提取 OpenAI 套餐字段。 */
export function parseJwtToken(token) {
  const normalizedToken = String(token || '').trim()
  const parts = normalizedToken.split('.')
  if (parts.length !== 3 || parts.some(part => !part)) throw new Error('Token 必须是 Header.Payload.Signature 格式')

  let header
  let payload
  try {
    header = JSON.parse(decodeBase64Url(parts[0], 'Header'))
    payload = JSON.parse(decodeBase64Url(parts[1], 'Payload'))
  } catch (error) {
    if (error instanceof SyntaxError) throw new Error('Header 或 Payload 不是有效的 JSON', { cause: error })
    throw error
  }
  if (!header || typeof header !== 'object' || Array.isArray(header)) throw new Error('Header 必须是 JSON 对象')
  if (!payload || typeof payload !== 'object' || Array.isArray(payload)) throw new Error('Payload 必须是 JSON 对象')

  const auth = payload[AUTH_CLAIM]
  const authObject = auth && typeof auth === 'object' && !Array.isArray(auth) ? auth : {}
  return {
    header,
    payload,
    signature: parts[2],
    planType: typeof authObject.chatgpt_plan_type === 'string' ? authObject.chatgpt_plan_type : '',
    auth: authObject,
    token: normalizedToken
  }
}

/** 修改套餐字段并重新编码 JWT；签名保持原值，因此结果不能通过原 RS256 验证。 */
export function buildTokenWithPlan(parsedToken, planType) {
  if (!parsedToken?.header || !parsedToken?.payload || typeof parsedToken.signature !== 'string') {
    throw new Error('请先解析有效的 Token')
  }
  const nextPlan = String(planType || '').trim()
  if (!nextPlan) throw new Error('套餐类型不能为空')
  const payload = JSON.parse(JSON.stringify(parsedToken.payload))
  const currentAuth = payload[AUTH_CLAIM]
  const auth = currentAuth && typeof currentAuth === 'object' && !Array.isArray(currentAuth)
    ? { ...currentAuth }
    : {}
  auth.chatgpt_plan_type = nextPlan
  payload[AUTH_CLAIM] = auth
  return `${encodeBase64Url(parsedToken.header)}.${encodeBase64Url(payload)}.${parsedToken.signature}`
}

/** 将 JWT Unix 秒时间戳转换为本地化日期文本，无法解析时返回空字符串。 */
export function formatJwtTimestamp(value, locale = 'zh-CN') {
  const seconds = Number(value)
  if (!Number.isFinite(seconds)) return ''
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium' }).format(new Date(seconds * 1000))
}

export { AUTH_CLAIM }
