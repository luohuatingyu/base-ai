/** n8n Worker 内部 HMAC 验证测试。 */

import assert from 'node:assert/strict'
import { createHash, createHmac } from 'node:crypto'
import test from 'node:test'
import { InternalRequestVerifier } from '../app/internal-auth.mjs'

const secret = 'n'.repeat(32)
const now = 1_788_000_000

/** 生成固定时间和 nonce 的跨语言协议头。 */
function headers(body, nonce = '0123456789abcdef0123456789abcdef', timestamp = now) {
  const digest = createHash('sha256').update(body).digest('hex')
  const target = '/invocations'
  const signature = createHmac('sha256', secret)
    .update(`POST\n${target}\n${timestamp}\n${nonce}\n${digest}`).digest('hex')
  return { 'x-internal-timestamp': String(timestamp), 'x-internal-nonce': nonce,
    'x-internal-target': target, 'x-internal-content-sha256': digest,
    'x-internal-signature': signature }
}

test('valid signature is accepted once and body tampering is rejected', () => {
  const body = Buffer.from('{"value":1}')
  const signed = headers(body)
  const verifier = new InternalRequestVerifier(secret, () => now)
  assert.equal(verifier.verify('POST', '/invocations', body, signed), true)
  assert.equal(verifier.verify('POST', '/invocations', body, signed), false)
  assert.equal(new InternalRequestVerifier(secret, () => now)
    .verify('POST', '/invocations', Buffer.from('{}'), signed), false)
})

test('expired signature is rejected', () => {
  const body = Buffer.from('{}')
  const signed = headers(body, 'fedcba9876543210fedcba9876543210', now - 61)
  assert.equal(new InternalRequestVerifier(secret, () => now)
    .verify('POST', '/invocations', body, signed), false)
})
