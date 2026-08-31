/** 验证控制 Worker 只通过来源专用 Unix Socket 调用 Docker 沙箱。 */

import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, rm } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import test from 'node:test'
import { SandboxClient, SandboxError } from '../app/sandbox-client.mjs'

test('固定 Unix Socket 转发有限 JSON 且不附加 Worker 密钥', async () => {
  const directory = await mkdtemp(join(tmpdir(), 'base-ai-n8n-sandbox-client-'))
  const socketPath = join(directory, 'broker.sock')
  let received
  const server = createServer((request, response) => {
    const chunks = []
    request.on('data', chunk => chunks.push(chunk))
    request.on('end', () => {
      received = { path: request.url, headers: request.headers, body: Buffer.concat(chunks).toString('utf8') }
      response.setHeader('content-type', 'application/json')
      response.end('{"success":true,"output":{"ok":true}}')
    })
  })
  await new Promise(resolve => server.listen(socketPath, resolve))
  try {
    const result = await new SandboxClient(socketPath).request('invoke', {
      fingerprint: 'a'.repeat(64), componentId: 'action',
    })
    assert.equal(result.output.ok, true)
    assert.equal(received.path, '/sandbox/invoke')
    assert.equal(received.headers['x-internal-token'], undefined)
    assert.equal(received.body.includes('PLUGIN_WORKER_INTERNAL_TOKEN'), false)
  } finally {
    await new Promise(resolve => server.close(resolve))
    await rm(directory, { recursive: true, force: true })
  }
})

test('未知操作在连接 Broker 前拒绝', async () => {
  await assert.rejects(() => new SandboxClient('/missing.sock').request('run', { image: 'evil' }),
    error => error instanceof SandboxError && error.code === 'PLUGIN_SANDBOX_OPERATION_INVALID')
})
