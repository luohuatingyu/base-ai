/** n8n 插件兼容 Worker 的受鉴权 HTTP 入口。 */

import { createServer } from 'node:http'
import { InternalRequestVerifier } from './internal-auth.mjs'
import { SandboxClient, SandboxError } from './sandbox-client.mjs'

const token = process.env.PLUGIN_WORKER_INTERNAL_TOKEN || ''
const auth = new InternalRequestVerifier(token)
const sandbox = new SandboxClient(process.env.PLUGIN_SANDBOX_BROKER_SOCKET || '/run/plugin-sandbox/broker.sock')
const maximum = Number(process.env.PLUGIN_WORKER_MAX_REQUEST_BYTES || 15 * 1024 * 1024)

/** 写入有限 JSON 响应。 */
function respond(response, status, value) {
  const body = Buffer.from(JSON.stringify(value))
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': body.length })
  response.end(body)
}

/** 读取并限制签名绑定的原始请求体。 */
async function body(request) {
  const declared = Number(request.headers['content-length'] || 0)
  if (declared <= 0 || declared > maximum) throw new SandboxError(400, 'REQUEST_SIZE_INVALID')
  const chunks = []; let size = 0
  for await (const chunk of request) {
    size += chunk.length
    if (size > maximum) throw new SandboxError(400, 'REQUEST_SIZE_INVALID')
    chunks.push(chunk)
  }
  return Buffer.concat(chunks)
}

if (token.length < 24) throw new Error('PLUGIN_WORKER_INTERNAL_TOKEN 至少需要 24 个字符')

createServer(async (request, response) => {
  try {
    if (request.method === 'GET' && request.url === '/health') {
      const healthy = await sandbox.healthy()
      respond(response, healthy ? 200 : 503,
        { status: healthy ? 'UP' : 'DOWN', runtime: 'base-ai-node-abi', node: '24' }); return
    }
    const raw = await body(request)
    if (!auth.verify(request.method, request.url, raw, request.headers)) {
      respond(response, 401, { error: 'UNAUTHORIZED' }); return
    }
    const value = JSON.parse(raw.toString('utf8'))
    if (!value || typeof value !== 'object' || Array.isArray(value)) throw new SandboxError(400, 'REQUEST_JSON_INVALID')
    if (request.method === 'POST' && request.url === '/packages/inspect') respond(response, 200, await sandbox.request('inspect', value))
    else if (request.method === 'POST' && request.url === '/packages/remove') respond(response, 200, await sandbox.request('remove', value))
    else if (request.method === 'POST' && request.url === '/invocations') respond(response, 200, await sandbox.request('invoke', value))
    else respond(response, 404, { error: 'NOT_FOUND' })
  } catch (error) {
    const status = error instanceof SandboxError ? error.status : 500
    respond(response, status, { error: status === 500 ? 'PLUGIN_WORKER_FAILURE' : error.code })
  }
}).listen(Number(process.env.PORT || 8102), '0.0.0.0')
