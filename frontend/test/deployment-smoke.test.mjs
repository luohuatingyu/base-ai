import assert from 'node:assert/strict'
import test from 'node:test'
import { consumeEvents, waitForWorkflow, sessionFromCookies, apiData } from '../../scripts/deployment-smoke.mjs'

/** 模拟分片边界而非替换 SSE 解析逻辑。 */
async function* chunks(parts) { for (const part of parts) yield Buffer.from(part) }

test('chat validates incremental deltas across transport boundaries and requires completion', async () => {
  const deltas = []
  const answer = await consumeEvents(chunks(['data: {"type":"sta', 'rt"}\n\ndata: {"type":"delta","content":"hello"}\n',
    '\ndata: {"type":"heartbeat"}\n\ndata: {"type":"delta","content":" world"}\n\ndata: {"type":"done"}\n\n']), value => deltas.push(value))
  assert.equal(answer, 'hello world')
  assert.deepEqual(deltas, ['hello', ' world'])
})

for (const [name, stream] of Object.entries({
  interrupted: 'data: {"type":"delta","content":"partial"}\n\n',
  error: 'data: {"type":"error","message":"secret"}\n\n',
  empty: 'data: {"type":"done"}\n\n',
  malformed: 'data: {bad-secret}\n\n',
  truncated: 'data: {"type":"done"}',
  late: 'data: {"type":"delta","content":"ok"}\n\ndata: {"type":"done"}\n\ndata: {"type":"delta","content":"late"}\n\n',
})) {
  test(`chat rejects ${name} SSE without reporting message content`, async () => {
    await assert.rejects(consumeEvents(chunks([stream])), error => !error.message.includes('secret'))
  })
}

test('workflow polling returns the final business result', async () => {
  const runs = [{ status: 'RUNNING' }, { status: 'SUCCESS', output: { marker: 'verified' } }]
  const result = await waitForWorkflow(async () => runs.shift(), { timeoutMs: 200, intervalMs: 1 })
  assert.deepEqual(result.output, { marker: 'verified' })
})

for (const status of ['FAILED', 'CANCELLED', 'FAILED_CONTINUED']) {
  test(`workflow ${status} is never accepted as a passing smoke test`, async () => {
    await assert.rejects(waitForWorkflow(async () => ({ status }), { timeoutMs: 100, intervalMs: 1 }))
  })
}

test('workflow polling has a bounded deadline', async () => {
  await assert.rejects(waitForWorkflow(async () => ({ status: 'RUNNING' }), { timeoutMs: 5, intervalMs: 1 }), /timeout/)
})

test('browser session requires Secure HttpOnly session and separate CSRF cookie', () => {
const session = sessionFromCookies(['BAI_ai_SESSION=token; Path=/api; Secure; HttpOnly; SameSite=Strict',
    'BAI_ai_CSRF=csrf; Path=/; Secure; SameSite=Strict'])
  assert.equal(session.csrf, 'csrf')
  assert.equal(session.cookie, 'BAI_ai_SESSION=token; BAI_ai_CSRF=csrf')
  assert.throws(() => sessionFromCookies(['BAI_ai_SESSION=token; HttpOnly', 'BAI_ai_CSRF=csrf; Secure']))
  assert.throws(() => sessionFromCookies(['BAI_ai_SESSION=token; Secure; HttpOnly']))
})

test('void mutation responses are accepted only when explicitly expected', () => {
  assert.equal(apiData({ status: 200, text: '' }, { allowEmpty: true }), null)
  assert.equal(apiData({ status: 204, text: '' }, { allowEmpty: true }), null)
  assert.throws(() => apiData({ status: 200, text: '' }))
  assert.throws(() => apiData({ status: 401, text: '' }, { allowEmpty: true }))
  assert.throws(() => apiData({ status: 200, text: '{}', json: { success: false } }, { allowEmpty: true }))
  assert.deepEqual(apiData({ status: 200, text: '{}', json: { success: true, data: { id: 9 } } }), { id: 9 })
})
