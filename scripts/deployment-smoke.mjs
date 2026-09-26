import assert from 'node:assert/strict'
import https from 'node:https'
import { readFileSync } from 'node:fs'
import { randomUUID } from 'node:crypto'
import { pathToFileURL } from 'node:url'
import { StringDecoder } from 'node:string_decoder'

/** 逐帧消费真实流，拒绝错误、截断、空回答及完成后继续输出。 */
export async function consumeEvents(stream, onDelta = () => {}) {
  const decoder = new StringDecoder('utf8')
  let buffer = '', answer = '', done = false, bytes = 0
  for await (const chunk of stream) {
    bytes += chunk.length
    assert.ok(bytes <= 2 * 1024 * 1024, 'SSE response exceeds limit')
    buffer += decoder.write(chunk)
    let index
    while ((index = buffer.indexOf('\n\n')) >= 0) {
      const frame = buffer.slice(0, index)
      buffer = buffer.slice(index + 2)
      for (const line of frame.split('\n')) {
        if (!line.startsWith('data:')) continue
        let event
        try { event = JSON.parse(line.slice(5)) } catch { throw new Error('Malformed SSE event') }
        assert.ok(!done, 'SSE event after completion')
        assert.ok(['start', 'heartbeat', 'delta', 'done'].includes(event.type), 'SSE failed or unknown event')
        if (event.type === 'delta') {
          assert.equal(typeof event.content, 'string', 'Invalid SSE delta')
          answer += event.content
          onDelta(event.content)
        }
        if (event.type === 'done') done = true
      }
    }
  }
  assert.ok(!buffer.trim() && !decoder.end(), 'Truncated SSE frame')
  assert.ok(done && answer.trim(), 'Chat did not complete with content')
  return answer
}

/** 轮询异步工作流，失败终态和截止时间均作为验收失败。 */
export async function waitForWorkflow(fetchRun, { timeoutMs = 60000, intervalMs = 500 } = {}) {
  const deadline = Date.now() + timeoutMs
  while (Date.now() < deadline) {
    const run = await fetchRun()
    if (run.status === 'SUCCESS') return run
    assert.ok(['PENDING', 'QUEUED', 'RUNNING', 'WAITING'].includes(run.status), 'Workflow failed')
    await new Promise(resolve => setTimeout(resolve, intervalMs))
  }
  throw new Error('Workflow timeout')
}

/** 验证浏览器 Cookie 安全属性并构建真实 CSRF 双提交请求。 */
export function sessionFromCookies(cookies = []) {
  const session = cookies.find(cookie => /^[^=]+_SESSION=/.test(cookie))
  const csrf = cookies.find(cookie => /^[^=]+_CSRF=/.test(cookie))
  assert.ok(session && csrf, 'Missing session or CSRF cookie')
  assert.ok(/;\s*Secure(?:;|$)/i.test(session) && /;\s*HttpOnly(?:;|$)/i.test(session), 'Unsafe session cookie')
  assert.ok(/;\s*Secure(?:;|$)/i.test(csrf), 'Unsafe CSRF cookie')
  return { cookie: [session, csrf].map(cookie => cookie.split(';')[0]).join('; '), csrf: csrf.split(';')[0].split('=').slice(1).join('=') }
}

/** 只有已知 void 变更接口允许空响应，其余接口必须返回业务成功封装。 */
export function apiData(response, { allowEmpty = false } = {}) {
  assert.ok(response.status >= 200 && response.status < 300, 'API HTTP failure')
  if (allowEmpty && response.text === '') return null
  assert.equal(response.json?.success, true, 'API business failure')
  return response.json.data
}

/** 发出有总时限的 HTTPS 请求；使用受信 CA，不关闭证书校验，不跟随重定向。 */
function request(base, path, { method = 'GET', body, session, csrf = true, ca, stream = false } = {}) {
  return new Promise((resolve, reject) => {
    const data = body === undefined ? undefined : JSON.stringify(body)
    const headers = { Accept: stream ? 'text/event-stream' : 'application/json', Origin: base.origin }
    if (data !== undefined) headers['Content-Type'] = 'application/json'
    if (session) {
      headers.Cookie = session.cookie
      if (csrf) headers['X-CSRF-Token'] = session.csrf
    }
    const req = https.request(new URL(path, base), { method, headers, ca }, async response => {
      try {
        if (stream) {
          assert.equal(response.statusCode, 200, 'Chat HTTP failure')
          assert.match(response.headers['content-type'] || '', /^text\/event-stream/, 'Chat is not SSE')
          const started = Date.now()
          let firstDelta
          const answer = await consumeEvents(response, () => { firstDelta ??= Date.now() - started })
          resolve({ answer, firstDelta })
        } else {
          let size = 0
          const chunks = []
          for await (const chunk of response) {
            size += chunk.length
            assert.ok(size <= 2 * 1024 * 1024, 'Response exceeds limit')
            chunks.push(chunk)
          }
          const text = Buffer.concat(chunks).toString('utf8')
          let json
          try { json = JSON.parse(text) } catch { /* 页面响应保留为文本。 */ }
          resolve({ status: response.statusCode, headers: response.headers, json, text })
        }
      } catch (error) { response.destroy(); reject(error) }
      finally { clearTimeout(timer) }
    })
    const timer = setTimeout(() => req.destroy(new Error('Request timeout')), 90000)
    req.on('error', () => { clearTimeout(timer); reject(new Error(`HTTPS request failed: ${method} ${path}`)) })
    req.end(data)
  })
}

/** 经真实 HTTPS 入口检查页面、鉴权、流式聊天、工作流结果及清理。 */
export async function runSmoke(env = process.env) {
  const base = new URL(env.SMOKE_BASE_URL || '')
  assert.equal(base.protocol, 'https:', 'SMOKE_BASE_URL must use HTTPS')
  assert.ok(!base.username && !base.password && base.pathname === '/' && !base.search && !base.hash, 'Use a bare HTTPS origin')
  const username = env.SMOKE_USERNAME
  const password = env.SMOKE_PASSWORD_FILE ? readFileSync(env.SMOKE_PASSWORD_FILE, 'utf8').trimEnd() : env.SMOKE_PASSWORD
  assert.ok(username && password, 'Set SMOKE_USERNAME and SMOKE_PASSWORD_FILE or SMOKE_PASSWORD')
  const ca = env.SMOKE_CA_FILE ? readFileSync(env.SMOKE_CA_FILE) : undefined
  const call = (path, options = {}) => request(base, path, { ca, ...options })
  const api = async (path, options) => {
    const response = await call(path, options)
    try { return apiData(response, options) } catch { throw new Error(`API failed: ${path} (HTTP ${response.status})`) }
  }
  const page = await call('/')
  assert.equal(page.status, 200, 'Frontend not available')
  assert.match(page.text, /<div[^>]+id=["']app["']/, 'Frontend app is missing')
  const ready = await call('/api/open/health/ready')
  assert.equal(ready.status, 200, 'Backend readiness HTTP failure')
  assert.equal(ready.json?.status, 'UP', 'Backend dependencies not ready')
  assert.equal((await call('/api/auth/me')).status, 401, 'Anonymous request accepted')
  assert.equal((await call('/api/auth/login', { method: 'POST', body: { username, password: randomUUID() } })).status, 401, 'Invalid password accepted')
  const login = await call('/api/auth/login', { method: 'POST', body: { username, password } })
  assert.equal(login.status, 200, 'Login failed')
  assert.equal(login.json?.success, true, 'Login business failure')
  const session = sessionFromCookies(login.headers['set-cookie'])
  let conversationId, workflowId, failure
  const cleanupFailures = []
  try {
    const me = await api('/api/auth/me', { session })
    assert.equal(me.username, username, 'Wrong authenticated identity')
    assert.equal((await call('/api/ai/conversations', { method: 'POST', session, csrf: false })).status, 403, 'CSRF not enforced')
    console.log('PASS login, identity, rejected credentials, anonymous access and CSRF')
    const conversation = await api('/api/ai/conversations', { method: 'POST', session })
    conversationId = conversation.id
    assert.ok(conversationId, 'Conversation not created')
    const prompt = `Reply briefly to deployment smoke ${randomUUID()}`
    const stream = await call(`/api/ai/conversations/${conversationId}/messages/stream`, {
      method: 'POST', session, stream: true, body: { requestId: randomUUID(), content: prompt,
        settings: { modelType: 'text_model', featureCode: env.SMOKE_FEATURE_CODE || 'chat',
          modelId: env.SMOKE_MODEL_ID ? Number(env.SMOKE_MODEL_ID) : null, enableThinking: false } },
    })
    if (env.SMOKE_EXPECTED_TEXT) assert.ok(stream.answer.includes(env.SMOKE_EXPECTED_TEXT), 'Unexpected model response')
    const history = await api(`/api/ai/conversations/${conversationId}`, { session })
    assert.ok(history.messages.some(message => message.role === 'user' && message.content === prompt), 'User message not persisted')
    assert.ok(history.messages.some(message => message.role === 'assistant' && message.status === 'COMPLETED' && message.content === stream.answer), 'Completed answer not persisted')
    console.log(`PASS chat SSE and persisted history (first delta ${stream.firstDelta}ms)`)
    const marker = randomUUID()
    const workflow = await api('/api/workflow/canvases', { method: 'POST', session, body: {
      code: `smoke-${marker}`, name: 'Deployment acceptance', description: 'Temporary acceptance workflow',
      graph: { nodes: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END', data: { config: { output: { marker: '{{input.marker}}' } } } }],
        edges: [{ id: 'start-end', source: 'start', target: 'end' }] },
      inputSchema: { type: 'object', properties: { marker: { type: 'string' } }, required: ['marker'] },
    } })
    workflowId = workflow.id
    assert.ok(workflowId, 'Workflow not created')
    const published = await api(`/api/workflow/canvases/${workflowId}/publish`, { method: 'POST', session })
    assert.ok(published.publishedVersionId, 'Workflow not published')
    const accepted = await api(`/api/workflow/canvases/${workflowId}/runs`, { method: 'POST', session, body: { inputs: { marker } } })
    assert.ok(accepted.runId, 'Workflow run not accepted')
    const run = await waitForWorkflow(() => api(`/api/workflow/runs/${accepted.runId}`, { session }))
    assert.equal(run.output?.marker, marker, 'Workflow output mismatch')
    assert.ok(['start', 'end'].every(id => run.nodes.some(node => node.nodeId === id && node.status === 'SUCCESS')), 'Successful node logs missing')
    console.log('PASS workflow publish, execution, output and node logs')
  } catch (error) {
    failure = error
    throw error
  } finally {
    // 失败路径也清理本次数据；工作流历史按服务既有保留策略保留。
    for (const [path, id] of [['/api/ai/conversations', conversationId], ['/api/workflow/canvases', workflowId]]) {
      if (id) try { await api(`${path}/${id}`, { method: 'DELETE', session, allowEmpty: true }) } catch { cleanupFailures.push(path) }
    }
    try {
      await api('/api/auth/logout', { method: 'POST', session, allowEmpty: true })
      assert.equal((await call('/api/auth/me', { session })).status, 401, 'Revoked session still accepted')
    } catch { cleanupFailures.push('logout') }
    if (cleanupFailures.length) throw new Error(`${failure ? failure.message + '; ' : ''}Smoke cleanup failed: ${cleanupFailures.join(', ')}`)
  }
  console.log('PASS cleanup and session revocation')
}

// CLI 只输出验收阶段及脱敏错误，不打印 HTTP 响应、Cookie 或凭据。
if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  runSmoke().catch(error => { console.error(`FAIL ${error.message}`); process.exitCode = 1 })
}
