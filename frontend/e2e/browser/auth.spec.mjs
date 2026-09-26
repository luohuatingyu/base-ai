import { test, expect } from './fixtures.mjs'

/** 通过可见登录表单提交，覆盖真实 Pinia 状态及路由跳转。 */
async function login(page, password = 'correct-password') {
  await page.getByPlaceholder('Username', { exact: true }).fill('reader')
  await page.getByPlaceholder('Password', { exact: true }).fill(password)
  await page.getByRole('button', { name: 'Sign In', exact: true }).click()
}

// 未登录访问保留原地址，登录后回跳，刷新后继续从会话恢复用户。
test('login restores the protected destination and session after reload', async ({ page, app }) => {
  app.state.authenticated = false
  await page.goto(`${app.origin}/ai-chat`)
  await expect(page).toHaveURL(url => url.pathname === '/login' && url.searchParams.get('redirect') === '/ai-chat')
  await login(page)
  await expect(page.locator('.chat-composer')).toBeVisible()
  await page.reload()
  await expect(page.locator('.chat-composer')).toBeVisible()
  const cookies = await page.context().cookies()
  expect(cookies.find(cookie => cookie.name === 'fixture-session')?.httpOnly).toBe(true)
  expect(await page.evaluate(() => localStorage.getItem('ai-platform-token'))).toBeNull()
})

// 错误密码显示失败，随后正确登录应恢复按钮及认证流程。
test('invalid credentials stay on login and allow a successful retry', async ({ page, app }) => {
  app.state.authenticated = false
  await page.goto(`${app.origin}/login`)
  await login(page, 'wrong-password')
  await expect(page.locator('.el-message')).toContainText('Invalid credentials')
  await expect(page).toHaveURL(/\/login$/)
  expect(app.state.authenticated).toBe(false)
  await login(page)
  await expect(page).toHaveURL(/\/dashboard$/)
})

for (const path of ['/ai-chat', '/workflow/canvases']) {
  // 直接输入受限 URL 也必须经过路由权限判断。
  test(`restricted user cannot navigate directly to ${path}`, async ({ page, app }) => {
    app.state.user.permissions = []
    await page.goto(`${app.origin}${path}`)
    await expect(page).toHaveURL(/\/dashboard$/)
    expect(app.state.requests.some(request => request.path.startsWith('/api/ai/') || request.path.startsWith('/api/workflow/'))).toBe(false)
  })
}

// 会话过期后重新加载受保护页面必须回到登录入口。
test('expired session returns to login with the requested path', async ({ page, app }) => {
  await page.goto(`${app.origin}/ai-chat`)
  await expect(page.locator('.chat-composer')).toBeVisible()
  app.state.authenticated = false
  await page.reload()
  await expect(page).toHaveURL(url => url.pathname === '/login' && url.searchParams.get('redirect') === '/ai-chat')
  await expect(page.locator('.chat-composer')).toHaveCount(0)
})
