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

/** 将 PKCS#8 PEM 私钥转换为 Web Crypto 可导入的 DER 字节。 */
function decodePrivateKeyPem(privateKeyPem) {
  const normalized = String(privateKeyPem || '').trim()
  const match = normalized.match(/^-----BEGIN PRIVATE KEY-----([\s\S]+)-----END PRIVATE KEY-----$/)
  if (!match) throw new Error('私钥必须是 PKCS#8 PEM 格式（BEGIN PRIVATE KEY）')
  const body = match[1].replace(/\s+/g, '')
  if (!body || !/^[A-Za-z0-9+/]+={0,2}$/.test(body)) throw new Error('私钥不是有效的 Base64 编码')
  let binary
  try {
    binary = atob(body)
  } catch {
    throw new Error('私钥不是有效的 Base64 编码')
  }
  return Uint8Array.from(binary, character => character.charCodeAt(0))
}

/** 使用当前浏览器的 Web Crypto API 导入 RSA 私钥。 */
async function importSigningKey(privateKeyPem) {
  const subtle = globalThis.crypto?.subtle
  if (!subtle) throw new Error('当前浏览器不支持 Web Crypto，无法生成 RS256 Token')
  const keyData = decodePrivateKeyPem(privateKeyPem)
  try {
    return await subtle.importKey('pkcs8', keyData, { name: 'RSASSA-PKCS1-v1_5', hash: 'SHA-256' }, false, ['sign'])
  } catch (error) {
    throw new Error('无法导入 RSA 私钥，请确认是与 issuer 公钥匹配的 PKCS#8 私钥', { cause: error })
  }
}

/** 使用 issuer 私钥为 JWT 内容生成 RS256 签名。 */
async function signRs256(content, privateKeyPem) {
  const subtle = globalThis.crypto?.subtle
  if (!subtle) throw new Error('当前浏览器不支持 Web Crypto，无法生成 RS256 Token')
  const key = await importSigningKey(privateKeyPem)
  const signature = await subtle.sign(
    { name: 'RSASSA-PKCS1-v1_5' },
    key,
    new TextEncoder().encode(content)
  )
  return encodeBase64UrlBytes(new Uint8Array(signature))
}

/** 将二进制签名编码为不含填充符的 Base64URL 字符串。 */
function encodeBase64UrlBytes(bytes) {
  let binary = ''
  bytes.forEach(byte => { binary += String.fromCharCode(byte) })
  return btoa(binary).replace(/\+/g, '-').replace(/\//g, '_').replace(/=+$/g, '')
}

/** 修改套餐字段并使用 issuer 私钥重新生成可验签的 RS256 JWT。 */
export async function buildTokenWithPlan(parsedToken, planType, privateKeyPem) {
  if (!parsedToken?.header || !parsedToken?.payload || typeof parsedToken.signature !== 'string') {
    throw new Error('请先解析有效的 Token')
  }
  if (parsedToken.header.alg !== 'RS256') throw new Error('仅支持 Header.alg 为 RS256 的 Token')
  const nextPlan = String(planType || '').trim()
  if (!nextPlan) throw new Error('套餐类型不能为空')
  if (!String(privateKeyPem || '').trim()) throw new Error('请提供 issuer 的 PKCS#8 RSA 私钥')
  const payload = JSON.parse(JSON.stringify(parsedToken.payload))
  const currentAuth = payload[AUTH_CLAIM]
  const auth = currentAuth && typeof currentAuth === 'object' && !Array.isArray(currentAuth)
    ? { ...currentAuth }
    : {}
  auth.chatgpt_plan_type = nextPlan
  payload[AUTH_CLAIM] = auth
  const content = `${encodeBase64Url(parsedToken.header)}.${encodeBase64Url(payload)}`
  return `${content}.${await signRs256(content, privateKeyPem)}`
}

/** 将 JWT Unix 秒时间戳转换为本地化日期文本，无法解析时返回空字符串。 */
export function formatJwtTimestamp(value, locale = 'zh-CN') {
  const seconds = Number(value)
  if (!Number.isFinite(seconds)) return ''
  const date = new Date(seconds * 1000)
  if (Number.isNaN(date.getTime())) return ''
  return new Intl.DateTimeFormat(locale, { dateStyle: 'medium', timeStyle: 'medium' }).format(date)
}

export { AUTH_CLAIM }
