import http from 'node:http'
import { fileURLToPath } from 'node:url'
import { createServer } from 'vite'
import vue from '@vitejs/plugin-vue'
import { test as base, expect } from '@playwright/test'
import { createWorkflowGraph } from '../../src/utils/workflowGraph.js'

/** 每例提供独立 HTTP API，保留浏览器中的真实路由、组件和流式解析。 */
async function createApp() {
  const user = { id: 7, username: 'reader', displayName: 'Reader', roles: ['USER'],
    permissions: ['ai:chat:invoke', 'automation:workflow:canvas:list', 'automation:workflow:canvas:execute'], menus: [] }
  const state = { user, authenticated: true, requests: [], unexpected: [], stream: null, streamClosed: false,
    streamStatus: 200, messages: [], runStatuses: ['RUNNING', 'SUCCESS'], polls: 0, runInput: null }
  // 未声明的端点直接失败，防止宽松兜底掩盖页面新增请求或接口拼写错误。
  const api = http.createServer(async (request, response) => {
    const path = new URL(request.url, 'http://localhost').pathname
    const chunks = []
    for await (const chunk of request) chunks.push(chunk)
    const raw = Buffer.concat(chunks).toString()
    const body = raw ? JSON.parse(raw) : null
    state.requests.push({ method: request.method, path, body, headers: request.headers })
    /** 返回与 Axios 解包协议兼容的普通 JSON 响应。 */
    const json = (data, status = 200) => {
      response.writeHead(status, { 'content-type': 'application/json' })
      response.end(JSON.stringify(data))
    }
    if (path === '/api/auth/me') return json(state.authenticated ? user : { message: 'Session expired' }, state.authenticated ? 200 : 401)
    if (path === '/api/auth/login') {
      state.authenticated = body.username === 'reader' && body.password === 'correct-password'
      if (!state.authenticated) return json({ message: 'Invalid credentials' }, 401)
      response.setHeader('set-cookie', ['fixture-session=valid; HttpOnly; SameSite=Lax; Path=/', 'BAI_ai-platform_CSRF=fixture-csrf; SameSite=Lax; Path=/'])
      return json({ user })
    }
    if (path === '/api/auth/logout') { state.authenticated = false; return json({}) }
    if (path === '/api/open/platform/endpoints') return json([])
    if (path === '/api/operations/dashboard') return json({ tasks24h: 3, success24h: 2, failed24h: 1, running: 0 })
    if (path === '/api/ai/chat/routes') return json([{ id: 1, featureCode: 'DEFAULT', name: 'Default', supportedModelTypes: ['text_model'] }])
    if (path === '/api/ai/chat/providers') return json([])
    if (path === '/api/ai/chat/model-types') return json([{ value: 'text_model', label: 'Text' }])
    if (path === '/api/ai/conversations') return json(request.method === 'POST' ? { id: 1 } : { items: [], total: 0 })
    if (path === '/api/ai/conversations/1') return json({ id: 1, messages: state.messages, settings: { modelType: 'text_model' } })
    if (path === '/api/ai/conversations/1/messages/stream') {
      if (state.streamStatus !== 200) return json({ message: 'Stream rejected' }, state.streamStatus)
      state.stream = response
      response.writeHead(200, { 'content-type': 'text/event-stream', 'cache-control': 'no-cache' })
      response.flushHeaders()
      response.on('close', () => { state.streamClosed = true })
      return
    }
    if (path === '/api/workflow/canvases') return json([{ id: 1, code: 'REGRESSION', name: 'Regression workflow',
      currentVersion: 1, revision: 1, status: 'DRAFT', graph: createWorkflowGraph(), inputSchema: {} }])
    if (path === '/api/workflow/nodes') return json([])
    if (path === '/api/workflow/canvases/1/runs' && request.method === 'POST') {
      state.runInput = body.inputs
      return json({ runId: 'run-1', status: 'QUEUED' })
    }
    if (path === '/api/workflow/runs/run-1') {
      const status = state.runStatuses[Math.min(state.polls++, state.runStatuses.length - 1)]
      return json({ id: 'run-1', status, output: status === 'SUCCESS' ? { answer: 42 } : null, nodes: [] })
    }
    if (path === '/api/workflow/runs/run-1/cancel') return json({ id: 'run-1', status: 'CANCELLED' })
    state.unexpected.push(`${request.method} ${path}`)
    json({ message: `Unexpected fixture request: ${path}` }, 500)
  })
  await new Promise(resolve => api.listen(0, '127.0.0.1', resolve))
  const vite = await createServer({
    configFile: false,
    root: fileURLToPath(new URL('../..', import.meta.url)),
    plugins: [vue()],
    logLevel: 'error',
    server: { host: '127.0.0.1', port: 0, proxy: { '/api': `http://127.0.0.1:${api.address().port}` } }
  })
  await vite.listen()
  return { state, origin: `http://127.0.0.1:${vite.httpServer.address().port}`,
    /** 逐帧写入真实 TCP 响应，由测试控制完成时机而非固定睡眠。 */
    event(event) { state.stream.write(`data: ${JSON.stringify(event)}\n\n`) },
    /** 清理 Vite、HTTP 连接及未结束的 SSE，防止用例之间泄漏状态。 */
    async close() {
      state.stream?.destroy()
      await vite.close()
      api.closeAllConnections()
      await new Promise(resolve => api.close(resolve))
    }
  }
}

export const test = base.extend({
  /** 为每个浏览器用例提供独立服务，并断言没有漏配 API 或页面异常。 */
  app: async ({ page }, use) => {
    const app = await createApp()
    const errors = []
    page.on('pageerror', error => errors.push(error.message))
    await page.addInitScript(() => { window.__APP_CONFIG__ = { code: 'ai-platform', defaultLocale: 'en-US' } })
    try {
      await use(app)
      expect(app.state.unexpected).toEqual([])
      expect(errors).toEqual([])
    } finally { await app.close() }
  }
})
export { expect }
