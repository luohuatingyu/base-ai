import { test, expect } from './fixtures.mjs'

/** 选择真实 Vue Flow 画布并打开执行对话框。 */
async function openRun(page, app) {
  await page.goto(`${app.origin}/workflow/canvases`)
  await page.getByRole('button', { name: /Regression workflow/ }).click()
  await expect(page.locator('.vue-flow__node')).toHaveCount(2)
  await page.getByRole('button', { name: 'Run', exact: true }).click()
  return page.getByRole('dialog', { name: 'Run', exact: true })
}

for (const [status, label] of [['SUCCESS', 'Success'], ['FAILED', 'Failed']]) {
  // 使用浏览器虚拟时钟推进轮询，验证中间状态和终态后不再请求。
  test(`workflow progresses through running and waiting to ${status}`, async ({ page, app }) => {
    app.state.runStatuses = ['RUNNING', 'WAITING', status]
    const dialog = await openRun(page, app)
    await page.clock.install()
    await dialog.locator('textarea').fill('{"question":"meaning"}')
    await dialog.getByRole('button', { name: 'Start Run' }).click()
    await expect(dialog.locator('.el-alert')).toContainText('Queued')
    expect(app.state.runInput).toEqual({ question: 'meaning' })
    for (const next of ['Running', 'Waiting', label]) {
      await page.clock.runFor(1000)
      await expect(dialog.locator('.el-alert')).toContainText(next)
    }
    if (status === 'SUCCESS') await expect(dialog.locator('pre')).toContainText('42')
    else await expect(dialog.locator('.el-alert')).toHaveClass(/el-alert--error/)
    await expect(dialog.getByRole('button', { name: 'Cancel Run' })).toHaveCount(0)
    const polls = app.state.polls
    await page.clock.runFor(3000)
    expect(app.state.polls).toBe(polls)
  })
}

// 格式错误应在浏览器提示，不能向后端提交无效输入。
test('invalid JSON does not create a workflow run', async ({ page, app }) => {
  const dialog = await openRun(page, app)
  await dialog.locator('textarea').fill('{broken')
  await dialog.getByRole('button', { name: 'Start Run' }).click()
  await expect(page.locator('.el-message')).toContainText('Enter valid JSON')
  expect(app.state.runInput).toBeNull()
  expect(app.state.requests.filter(request => request.method === 'POST')).toEqual([])
})

// 空输入按已有默认行为发送空对象；取消后清理轮询。
test('empty input defaults to an object and cancellation stops polling', async ({ page, app }) => {
  const dialog = await openRun(page, app)
  await page.clock.install()
  await dialog.locator('textarea').fill('')
  await dialog.getByRole('button', { name: 'Start Run' }).click()
  await expect(dialog.locator('.el-alert')).toContainText('Queued')
  expect(app.state.runInput).toEqual({})
  await dialog.getByRole('button', { name: 'Cancel Run' }).click()
  await expect(dialog.locator('.el-alert')).toContainText('Cancelled')
  expect(app.state.requests.filter(request => request.path.endsWith('/cancel'))).toHaveLength(1)
  await page.clock.runFor(3000)
  expect(app.state.polls).toBe(0)
})

// 查看权限不隐含执行权限，隐藏入口同时保证没有执行请求。
test('view-only user cannot start a workflow', async ({ page, app }) => {
  app.state.user.permissions = ['automation:workflow:canvas:list']
  await page.goto(`${app.origin}/workflow/canvases`)
  await page.getByRole('button', { name: /Regression workflow/ }).click()
  await expect(page.locator('.vue-flow__node')).toHaveCount(2)
  await expect(page.getByRole('button', { name: 'Run', exact: true })).toHaveCount(0)
  expect(app.state.requests.filter(request => request.method === 'POST')).toEqual([])
})
