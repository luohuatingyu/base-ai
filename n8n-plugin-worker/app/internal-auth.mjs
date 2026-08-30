/** n8n 插件 Worker 的正文绑定 HMAC 请求验证。 */

import { createHash, createHmac, timingSafeEqual } from 'node:crypto'

const hex32 = /^[a-f0-9]{32}$/
const hex64 = /^[a-f0-9]{64}$/

export class InternalRequestVerifier {
  /** 保存独立密钥、时钟和进程内 nonce 缓存。 */
  constructor(secret, clock = () => Math.floor(Date.now() / 1000)) {
    this.secret = secret
    this.clock = clock
    this.used = new Map()
  }

  /** 校验目标、正文摘要和签名，并拒绝时间窗内 nonce 重放。 */
  verify(method, actualTarget, body, headers) {
    try {
      const timestampText = String(headers['x-internal-timestamp'] || '')
      const nonce = String(headers['x-internal-nonce'] || '')
      const target = String(headers['x-internal-target'] || '')
      const digest = String(headers['x-internal-content-sha256'] || '')
      const signature = String(headers['x-internal-signature'] || '')
      if (this.secret.length < 24 || !/^\d{1,12}$/.test(timestampText) || !hex32.test(nonce)
        || !hex64.test(digest) || !hex64.test(signature) || target !== actualTarget) return false
      const now = this.clock(); const signedAt = Number(timestampText)
      if (!Number.isSafeInteger(signedAt) || Math.abs(now - signedAt) > 60) return false
      const actualDigest = createHash('sha256').update(body).digest('hex')
      if (!equal(digest, actualDigest)) return false
      const canonical = `${String(method).toUpperCase()}\n${target}\n${timestampText}\n${nonce}\n${digest}`
      const expected = createHmac('sha256', this.secret).update(canonical).digest('hex')
      if (!equal(signature, expected)) return false
      for (const [value, usedAt] of this.used) if (usedAt < now - 60) this.used.delete(value)
      if (this.used.has(nonce)) return false
      this.used.set(nonce, now)
      return true
    } catch { return false }
  }
}

/** 对固定长度十六进制摘要执行常量时间比较。 */
function equal(left, right) {
  const first = Buffer.from(left, 'ascii'); const second = Buffer.from(right, 'ascii')
  return first.length === second.length && timingSafeEqual(first, second)
}
