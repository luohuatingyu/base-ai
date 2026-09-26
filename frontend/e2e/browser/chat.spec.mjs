import { test, expect } from './fixtures.mjs'

/** 等待真实页面加载模型选项及历史，再通过输入框发送问题。 */
async function send(page, app) {
  await page.goto(`${app.origin}/ai-chat`)
  await expect.poll(() => app.state.requests.some(request => request.path === '/api/ai/conversations')).toBe(true)
  await page.context().addCookies([{ name: 'BAI_ai-platform_CSRF', value: 'fixture-csrf', url: app.origin }])
  await page.locator('.chat-question-input textarea').fill('Explain streaming')
  await page.getByRole('button', { name: 'Send', exact: true }).click()
  await expect.poll(() => app.state.requests.some(request => request.path.endsWith('/messages/stream'))).toBe(true)
}

/** 保存与后端协议一致的恢复数据，再结束流以触发页面回读。 */
function persist(app, status, content) {
  app.state.messages = [
    { id: 1, role: 'user', content: 'Explain streaming', status: 'COMPLETED' },
    { id: 2, role: 'assistant', content, status, traceId: 'trace-browser', metadata: { model: 'fixture-model' } }
  ]
}

// 完成帧由断言之后发送，证明页面确实在流结束前显示增量。
test('renders incremental SSE before done, persists history and forwards CSRF', async ({ page, app }) => {
  await send(page, app)
  await expect.poll(() => Boolean(app.state.stream)).toBe(true)
  app.event({ type: 'start', messageId: 2, traceId: 'trace-browser' })
  app.event({ type: 'delta', content: 'First 中文' })
  await expect(page.locator('.assistant .message-content')).toHaveText('First 中文')
  await expect(page.getByRole('button', { name: 'Send', exact: true })).toHaveClass(/is-loading/)
  expect(app.state.streamClosed).toBe(false)
  app.event({ type: 'delta', content: ' second' })
  await expect(page.locator('.assistant .message-content')).toHaveText('First 中文 second')
  persist(app, 'COMPLETED', 'First 中文 second')
  app.event({ type: 'done', content: 'First 中文 second', model: 'fixture-model' })
  app.state.stream.end()
  await expect(page.getByRole('button', { name: 'Send', exact: true })).not.toHaveClass(/is-loading/)
  await expect(page.locator('.assistant .message-content')).toHaveText('First 中文 second')
  await expect(page.locator('.message.assistant')).toHaveCount(1)
  const request = app.state.requests.find(request => request.path.endsWith('/messages/stream'))
  expect(request.body).toMatchObject({ content: 'Explain streaming', settings: { modelType: 'text_model' } })
  expect(request.body.requestId).toMatch(/^[0-9a-f-]{36}$/)
  expect(request.headers['x-csrf-token']).toBe('fixture-csrf')
  // 无日志查看权限时，Trace 只展示文本，不能提供跳转链接。
  await expect(page.locator('.assistant .el-link')).toHaveCount(0)
  await page.reload()
  await expect(page.locator('.assistant .message-content')).toHaveText('First 中文 second')
})

for (const input of ['', '   ']) {
  // 空白问题不得创建会话或请求模型。
  test(`empty prompt ${JSON.stringify(input)} has no write side effects`, async ({ page, app }) => {
    await page.goto(`${app.origin}/ai-chat`)
    await page.locator('.chat-question-input textarea').fill(input)
    await page.getByRole('button', { name: 'Send', exact: true }).click()
    await expect(page.locator('.message')).toHaveCount(0)
    expect(app.state.requests.filter(request => request.method === 'POST')).toEqual([])
  })
}

for (const mode of ['error', 'disconnect']) {
  // 错误事件和提前结束都应保留已生成部分，并解除发送中的状态。
  test(`stream ${mode} restores partial history and allows another request`, async ({ page, app }) => {
    await send(page, app)
    await expect.poll(() => Boolean(app.state.stream)).toBe(true)
    app.event({ type: 'start', messageId: 2 })
    app.event({ type: 'delta', content: 'Partial answer' })
    await expect(page.locator('.assistant .message-content')).toHaveText('Partial answer')
    persist(app, 'INTERRUPTED', 'Partial answer')
    if (mode === 'error') app.event({ type: 'error', message: 'Provider failed' })
    app.state.stream.end()
    await expect(page.locator('.el-message')).toBeVisible()
    await expect(page.locator('.assistant .message-content')).toHaveText('Partial answer')
    await expect(page.locator('.assistant small')).toBeVisible()
    await expect(page.getByRole('button', { name: 'Send', exact: true })).not.toHaveClass(/is-loading/)
    await page.locator('.chat-question-input textarea').fill('Retry')
    await page.getByRole('button', { name: 'Send', exact: true }).click()
    await expect.poll(() => app.state.requests.filter(request => request.path.endsWith('/messages/stream')).length).toBe(2)
  })
}

for (const status of [401, 403]) {
  // 流式 fetch 的鉴权错误也必须走页面的真实错误处理分支。
  test(`stream HTTP ${status} applies authentication or permission handling`, async ({ page, app }) => {
    app.state.streamStatus = status
    await send(page, app)
    if (status === 401) {
      await expect(page).toHaveURL(/\/login\?redirect=/)
    } else {
      await expect(page.locator('.el-message')).toContainText('Stream rejected')
      await expect(page).toHaveURL(/\/ai-chat/)
      await expect(page.getByRole('button', { name: 'Send', exact: true })).not.toHaveClass(/is-loading/)
    }
  })
}

// SPA 导航卸载聊天组件时，AbortController 应关闭仍在生成的上游连接。
test('leaving chat closes an in-flight stream', async ({ page, app }) => {
  await send(page, app)
  await expect.poll(() => Boolean(app.state.stream)).toBe(true)
  app.event({ type: 'start', messageId: 2 })
  app.event({ type: 'delta', content: 'Still generating' })
  await expect(page.locator('.assistant .message-content')).toHaveText('Still generating')
  await page.locator('.topbar-open-platform-link').click()
  await expect(page).toHaveURL(/\/open-platform$/)
  await expect.poll(() => app.state.streamClosed).toBe(true)
})
