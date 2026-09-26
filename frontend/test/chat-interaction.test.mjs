import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import zh from '../src/locales/zh-CN.js'

const source = readFileSync(new URL('../src/views/AiChatView.vue', import.meta.url), 'utf8')
const compiled = compileScript(parse(source).descriptor, { id: 'chat-interaction' })

/** 执行真实组件 setup 和响应式逻辑，只替换网络、路由及浏览器边界。 */
async function createChat(t, overrides = {}) {
  const mounts = []
  const unmounts = []
  const requests = []
  const notices = []
  const route = { query: {}, fullPath: '/ai-chat' }
  const detail = { conversation: { generating: false }, messages: [], settings: {} }
  const http = {
    async get(url) {
      requests.push(url)
      if (url === '/ai/chat/routes') return { data: [{ featureCode: 'DEFAULT', supportedModelTypes: ['text_model'] }] }
      if (url === '/ai/chat/providers' || url === '/ai/conversations/agent-tools' || url === '/workflow/chat-tools') return { data: [] }
      if (url === '/ai/chat/model-types') return { data: [{ value: 'text_model' }] }
      if (url === '/ai/conversations') return { data: { items: [], total: 0 } }
      return { data: detail }
    },
    async post() { return { data: { id: 1 } } },
    async delete() {},
    ...overrides.http
  }
  const router = { async replace(value) { route.query = value.query }, async push(value) { requests.push(value) } }
  const globals = {
    window: { matchMedia: () => ({ matches: true }) },
    fetch: overrides.fetch || (async () => streamResponse([{ type: 'start', messageId: 2 }, { type: 'delta', content: '回答' }, { type: 'done' }])),
    navigator: { clipboard: { writeText: overrides.writeText || (async () => {}) } }
  }
  const modules = {
    vue: { ...vue, onMounted: callback => mounts.push(callback), onBeforeUnmount: callback => unmounts.push(callback) },
    'element-plus': { ElMessage: Object.fromEntries(['warning', 'error', 'success'].map(type => [type, text => notices.push({ type, text })])), ElMessageBox: { confirm: overrides.confirm || (async () => {}) } },
    '@element-plus/icons-vue': {},
    '../api/http': { default: http, showHttpError() {}, chatStreamHeaders: () => ({ 'X-CSRF-Token': 'test' }) },
    'vue-i18n': { useI18n: () => ({ t: key => key.split('.').reduce((value, part) => value?.[part], zh) || key }) },
    'vue-router': { useRouter: () => router, useRoute: () => route },
    '../stores/auth': { useAuthStore: () => ({ hasPermission: overrides.hasPermission || (() => false) }) }
  }
  const bindings = { ...globals }
  for (const node of compiled.scriptSetupAst.filter(node => node.type === 'ImportDeclaration')) {
    const module = modules[node.source.value] || await import(new URL(`${node.source.value}.js`, new URL('../src/views/', import.meta.url)))
    for (const specifier of node.specifiers) bindings[specifier.local.name] = module[specifier.type === 'ImportDefaultSpecifier' ? 'default' : specifier.imported.name]
  }
  const code = compiled.content.replace(/^import .* from .*$/gm, '').replace('export default', 'return')
  const component = new Function(...Object.keys(bindings), code)(...Object.values(bindings))
  const scope = vue.effectScope()
  const state = scope.run(() => component.setup({}, { expose() {} }))
  t.after(() => { unmounts.forEach(callback => callback()); scope.stop() })
  await Promise.all(mounts.map(callback => callback()))
  return { state, requests, detail, http, notices }
}

/** 使用真实 SSE 字节流验证组件和流解析器的协作。 */
function streamResponse(events) {
  return new Response(events.map(event => `data: ${JSON.stringify(event)}\n\n`).join(''), { headers: { 'content-type': 'text/event-stream' } })
}

test('欢迎示例仅填入草稿并聚焦，不直接消耗模型调用', async t => {
  const { state, requests } = await createChat(t)
  let focused = false
  state.questionInput.value = { focus: () => { focused = true } }
  await state.useSuggestion('解释一段代码')
  assert.equal(state.prompt.value, '解释一段代码')
  assert.equal(focused, true)
  assert.equal(requests.some(url => typeof url === 'string' && url.includes('/messages')), false)
})

test('已输入的草稿不会被欢迎示例覆盖', async t => {
  const { state } = await createChat(t)
  state.prompt.value = '已有草稿'
  await state.useSuggestion('其他问题')
  assert.equal(state.prompt.value, '已有草稿')
})

test('流式成功保留回答和统计，等待期间输入的下一条草稿不会被清空', async t => {
  let release
  const response = new Promise(resolve => { release = resolve })
  let submitted
  const { state } = await createChat(t, { fetch: async (_url, options) => { submitted = JSON.parse(options.body); return response } })
  state.prompt.value = '问题'
  const sending = state.send()
  await new Promise(resolve => setImmediate(resolve))
  state.prompt.value = '下一条草稿'
  release(streamResponse([{ type: 'start', messageId: 2 }, { type: 'delta', content: '结果' }, { type: 'done', totalTokens: 5 }]))
  await sending
  assert.equal(submitted.content, '问题')
  assert.equal(state.prompt.value, '下一条草稿')
  assert.equal(state.messages.value.at(-1).content, '结果')
  assert.equal(state.messages.value.at(-1).totalTokens, 5)
  assert.equal(state.generating.value, false)
})

for (const draft of ['', '新草稿']) {
  test(`请求被拒绝时恢复原草稿，保留后来输入的内容：${draft || '空输入框'}`, async t => {
    let reject
    const failure = new Promise((_resolve, fail) => { reject = fail })
    const { state } = await createChat(t, { fetch: () => failure })
    state.prompt.value = '原问题'
    const sending = state.send()
    await new Promise(resolve => setImmediate(resolve))
    state.prompt.value = draft
    reject(new Error('network'))
    await sending
    assert.equal(state.prompt.value, draft || '原问题')
    assert.ok(state.pageError.value)
    assert.equal(state.messages.value.length, 0)
    assert.equal(state.loading.value, false)
  })
}

test('主动停止中断网络读取，保留部分回答且不显示调用失败', async t => {
  let controller
  const { state } = await createChat(t, { fetch: async (_url, options) => new Response(new ReadableStream({
    start(value) {
      controller = value
      value.enqueue(new TextEncoder().encode('data: {"type":"start","messageId":2}\n\ndata: {"type":"delta","content":"部分回答"}\n\n'))
      options.signal.addEventListener('abort', () => value.error(new DOMException('Stopped', 'AbortError')))
    }
  }), { headers: { 'content-type': 'text/event-stream' } }) })
  state.prompt.value = '问题'
  const sending = state.send()
  await new Promise(resolve => setImmediate(resolve))
  assert.ok(controller)
  state.stopGeneration()
  await sending
  assert.equal(state.messages.value.at(-1).content, '部分回答')
  assert.equal(state.messages.value.at(-1).status, 'INTERRUPTED')
  assert.equal(state.pageError.value, '')
  assert.equal(state.generating.value, false)
})

test('意外流中断保留部分内容并提供可理解的错误状态', async t => {
  const { state } = await createChat(t, { fetch: async () => streamResponse([{ type: 'start', messageId: 2 }, { type: 'delta', content: '部分内容' }]) })
  state.prompt.value = '问题'
  await state.send()
  assert.equal(state.messages.value.at(-1).content, '部分内容')
  assert.equal(state.messages.value.at(-1).status, 'INTERRUPTED')
  assert.ok(state.pageError.value)
})

test('配置加载失败可以重试，恢复前禁止发送', async t => {
  const { state, http } = await createChat(t, { http: { get: async () => { throw new Error('offline') } } })
  state.prompt.value = '问题'
  assert.equal(state.canSend.value, false)
  assert.ok(state.pageError.value)
  http.get = async url => ({ data: url === '/ai/conversations' ? { items: [], total: 0 } : url === '/ai/chat/routes' ? [{ featureCode: 'DEFAULT', supportedModelTypes: ['text_model'] }] : [] })
  await state.loadOptions()
  assert.equal(state.optionsReady.value, true)
  assert.equal(state.pageError.value, '')
})

test('无工具权限时仍能加载模型并发送普通对话', async t => {
  const { state } = await createChat(t, { http: { get: async url => {
    if (url === '/workflow/chat-tools' || url === '/ai/conversations/agent-tools') throw new Error('forbidden')
    return { data: url === '/ai/conversations' ? { items: [], total: 0 } : url === '/ai/chat/routes' ? [{ featureCode: 'DEFAULT', supportedModelTypes: ['text_model'] }] : [] }
  } } })
  state.prompt.value = '问题'
  assert.equal(state.canSend.value, true)
  assert.equal(state.agentTools.value.length, 0)
})

test('401 响应仍然跳转登录，空白、重复提交与无效模型均不会发请求', async t => {
  let calls = 0
  const { state, requests } = await createChat(t, { fetch: async () => { calls++; return new Response('{}', { status: 401 }) } })
  state.prompt.value = '  '
  await state.send()
  state.prompt.value = '问题'
  state.mode.value = 'single'
  await state.send()
  assert.equal(calls, 0)
  state.mode.value = 'multi'
  await state.send()
  assert.equal(calls, 1)
  assert.equal(requests.find(value => value?.path === '/login')?.path, '/login')
})

test('手动上滚阅读时不自动跟随，回到底部后恢复跟随', async t => {
  const { state } = await createChat(t)
  const scroll = { scrollHeight: 1000, clientHeight: 300, scrollTop: 100 }
  state.messagesElement.value = scroll
  state.onMessagesScroll()
  state.messages.value.push({ role: 'assistant', content: '新内容' })
  await vue.nextTick()
  await vue.nextTick()
  assert.equal(scroll.scrollTop, 100)
  assert.equal(state.followLatest.value, false)
  await state.scrollToLatest()
  assert.equal(scroll.scrollTop, 1000)
  assert.equal(state.followLatest.value, true)
})

test('切换会话失败不会丢失当前消息和草稿', async t => {
  const { state, http } = await createChat(t)
  state.prompt.value = '保留草稿'
  state.messages.value = [{ role: 'assistant', content: '当前回答' }]
  http.get = async () => { throw new Error('not found') }
  await state.selectConversation(99)
  assert.equal(state.prompt.value, '保留草稿')
  assert.equal(state.messages.value[0].content, '当前回答')
  assert.ok(state.pageError.value)
})

test('工具运行态不会误报失败，辅助详情默认折叠且输入保留键盘快捷键', () => {
  assert.match(source, /tool\.success === null/)
  assert.match(source, /<details v-if="item\.role === 'assistant'.*class="message-details"/)
  assert.match(source, /@keydown\.meta\.enter\.prevent=/)
  assert.match(source, /@keydown\.ctrl\.enter\.prevent=/)
  assert.match(source, /aria-live="polite"/)
  assert.match(source, /:disabled="!canSend"/)
})

test('模型来源切换显示上下文选项并清理不适用配置', async t => {
  const { state } = await createChat(t)
  state.providerId.value = 12
  state.modelId.value = 34
  state.setMode('single')
  state.featureCode.value = 'chat'
  state.setMode('multi')
  assert.equal(state.providerId.value, null)
  assert.equal(state.modelId.value, null)
  assert.equal(state.featureCode.value, 'chat')
  state.setMode('single')
  assert.equal(state.featureCode.value, '')
  assert.match(source, /class="model-basic-fields" :class="mode === 'single' \? 'single-model' : 'multi-model'"/)
  assert.match(source, /v-if="mode === 'single' && providerId"/)
  assert.match(source, /selectProviderFirst/)
  assert.match(source, /function setMode\(nextMode\)/)
})

test('模型配置焦点态和移动端布局使用柔和高亮', () => {
  assert.match(source, /\.mode-option:focus-visible \{ outline: none; box-shadow: 0 0 0 3px var\(--el-color-primary-light-9\)/)
  assert.match(source, /el-select__wrapper\.is-focused[\s\S]*box-shadow: 0 0 0 3px var\(--el-color-primary-light-9\)/)
  assert.match(source, /\.model-basic-fields\.multi-model, \.model-basic-fields\.single-model, \.model-capability-grid \{ grid-template-columns: minmax\(0, 1fr\); \}/)
})

for (const mode of ['multi', 'single']) {
  test(`设置面板开关保留 ${mode} 模式配置、草稿、消息和阅读位置，发送沿用配置`, async t => {
    let submitted
    const { state, requests } = await createChat(t, { fetch: async (_url, options) => {
      submitted = JSON.parse(options.body)
      return streamResponse([{ type: 'start', messageId: 2 }, { type: 'delta', content: '回答' }, { type: 'done' }])
    } })
    assert.equal(state.settingsOpen.value, false)
    assert.match(source, /@click="settingsOpen = true"/)
    assert.match(source, /<el-drawer[\s\S]*?v-model="settingsOpen"/)
    state.prompt.value = '未发送的草稿'
    state.messages.value = [{ role: 'assistant', content: '已有回答' }]
    const scroll = { scrollTop: 120, scrollHeight: 1000, clientHeight: 300 }
    state.messagesElement.value = scroll
    state.onMessagesScroll()
    const requestCount = requests.length
    state.settingsOpen.value = true
    state.setMode(mode)
    if (mode === 'single') {
      state.providerId.value = 12
      state.modelId.value = 34
    }
    state.enableThinking.value = true
    state.thinkingLevel.value = 'HIGH'
    state.agentToolCodes.value = ['HTTP_TRIGGER_7']
    state.workflowToolCodes.value = ['WORKFLOW_1']
    state.settingsOpen.value = false
    await vue.nextTick()
    state.settingsOpen.value = true
    await vue.nextTick()
    assert.equal(state.mode.value, mode)
    assert.equal(state.enableThinking.value, true)
    assert.equal(state.thinkingLevel.value, 'HIGH')
    assert.equal(state.prompt.value, '未发送的草稿')
    assert.deepEqual(state.messages.value, [{ role: 'assistant', content: '已有回答' }])
    assert.equal(scroll.scrollTop, 120)
    assert.equal(state.followLatest.value, false)
    assert.equal(requests.length, requestCount)
    state.settingsOpen.value = false
    await state.send()
    assert.equal(submitted.content, '未发送的草稿')
    assert.equal(submitted.settings.modelId, mode === 'single' ? 34 : null)
    assert.equal(submitted.settings.enableThinking, true)
    assert.equal(submitted.settings.thinkingLevel, 'HIGH')
    assert.deepEqual(submitted.settings.agentToolCodes, ['HTTP_TRIGGER_7'])
    assert.deepEqual(submitted.settings.workflowToolCodes, ['WORKFLOW_1'])
  })
}

test('Agent 工具和工作流工具使用独立目录与请求字段', () => {
  assert.match(source, /v-model="agentToolCodes"/)
  assert.match(source, /v-model="workflowToolCodes"/)
  assert.match(source, /\/ai\/conversations\/agent-tools/)
  assert.match(source, /\/workflow\/chat-tools/)
  assert.match(source, /agentToolCodes: \[\.\.\.agentToolCodes\.value\]/)
  assert.match(source, /workflowToolCodes: \[\.\.\.workflowToolCodes\.value\]/)
})

test('从会话工具端点加载已配置的 Agent 工具，地址错误不能被空列表掩盖', async t => {
  const option = { code: 'HTTP_TRIGGER_7', name: '查询服务', description: '查询订单', inputSchema: { type: 'object' } }
  const { state, http, requests } = await createChat(t)
  const originalGet = http.get
  http.get = async url => {
    if (url === '/ai/conversations/agent-tools') { requests.push(url); return { data: [option] } }
    if (url.endsWith('/agent-tools')) throw new Error('404 Not Found')
    return originalGet(url)
  }
  await state.loadOptions()
  assert.deepEqual(state.agentTools.value, [option])
  assert.ok(requests.includes('/ai/conversations/agent-tools'))
})

test('工具按名称、用途和编码搜索，选中及移除后提交独立字段', async t => {
  let submitted
  const { state } = await createChat(t, { hasPermission: () => true, fetch: async (_url, options) => {
    submitted = JSON.parse(options.body)
    return streamResponse([{ type: 'start', messageId: 2 }, { type: 'done' }])
  } })
  state.agentTools.value = [{ code: 'HTTP_TRIGGER_7', name: '订单', description: '查询库存' }]
  state.workflowTools.value = [{ code: 'WORKFLOW_1', name: '周报', description: '汇总数据' }]
  for (const query of ['订单', '库存', 'http_trigger_7']) {
    state.toolSearch.value = query
    assert.deepEqual(state.toolGroups.value[0].options.map(tool => tool.code), ['HTTP_TRIGGER_7'])
    assert.equal(state.toolGroups.value[1].options.length, 0)
  }
  state.toolSearch.value = '无匹配'
  assert.equal(state.toolGroups.value[0].options.length, 0)
  state.toggleTool('agent', 'HTTP_TRIGGER_7', true)
  state.toggleTool('agent', 'HTTP_TRIGGER_7', true)
  state.toggleTool('workflow', 'WORKFLOW_1', true)
  assert.equal(state.selectedTools.value.length, 2)
  state.removeTool('workflow', 'WORKFLOW_1')
  state.prompt.value = '查订单'
  await state.send()
  assert.deepEqual(submitted.settings.agentToolCodes, ['HTTP_TRIGGER_7'])
  assert.deepEqual(submitted.settings.workflowToolCodes, [])
  assert.deepEqual(submitted.settings.toolCodes, [])
})

for (const status of [403, 500]) {
  test(`工具目录 ${status} 与空目录区分，独立重试不丢失草稿和已选工具`, async t => {
    const { state, http } = await createChat(t, { hasPermission: () => true })
    state.prompt.value = '保留草稿'
    state.agentToolCodes.value = ['HTTP_TRIGGER_7']
    http.get = async () => { throw { response: { status } } }
    await state.loadTools('agent')
    assert.equal(state.toolStates.value.agent, status === 403 ? 'forbidden' : 'error')
    assert.equal(state.canSend.value, true)
    assert.equal(state.selectedTools.value[0].name, 'HTTP_TRIGGER_7')
    http.get = async () => ({ data: [] })
    await state.loadTools('agent')
    assert.equal(state.toolStates.value.agent, 'ready')
    assert.equal(state.toolGroups.value[0].options.length, 0)
    assert.equal(state.prompt.value, '保留草稿')
    assert.deepEqual(state.agentToolCodes.value, ['HTTP_TRIGGER_7'])
  })
}

test('工具选择锁定覆盖发送和服务端同步，拒绝未知及无权限工具', async t => {
  const { state } = await createChat(t)
  state.agentTools.value = [{ code: 'HTTP_TRIGGER_7', name: '订单' }]
  state.toggleTool('agent', 'HTTP_TRIGGER_7', true)
  assert.deepEqual(state.agentToolCodes.value, [])
  state.workflowTools.value = [{ code: 'WORKFLOW_1', name: '周报' }]
  state.toggleTool('workflow', 'unknown', true)
  assert.deepEqual(state.workflowToolCodes.value, [])
  state.workflowToolCodes.value = ['WORKFLOW_1']
  for (const flag of ['loading', 'serverGenerating']) {
    state[flag].value = true
    state.removeTool('workflow', 'WORKFLOW_1')
    state.toggleTool('workflow', 'WORKFLOW_1', false)
    assert.deepEqual(state.workflowToolCodes.value, ['WORKFLOW_1'])
    state[flag].value = false
  }
  state.removeTool('workflow', 'WORKFLOW_1')
  assert.deepEqual(state.workflowToolCodes.value, [])
})

for (const success of [true, false, null]) {
  test(`工具事件在回复旁显示终态 ${success}，中断后不再显示运行中`, async t => {
    const events = [{ type: 'start', messageId: 2 }, { type: 'agent_tool_start', toolCallId: 'call-1', toolName: '查询' }]
    if (success !== null) events.push({ type: 'agent_tool_done', toolCallId: 'call-1', toolName: '查询', success })
    if (success === true) events.push({ type: 'done' })
    const { state } = await createChat(t, { fetch: async () => streamResponse(events) })
    state.prompt.value = '查询'
    await state.send()
    const message = state.messages.value.at(-1)
    assert.equal(message.toolCalls.length, 1)
    assert.equal(state.toolStatus(message.toolCalls[0], message), success === null ? 'interrupted' : success ? 'succeeded' : 'failed')
    assert.equal(state.toolStatus({ success: null }, { status: 'GENERATING' }), 'running')
    assert.ok(source.indexOf('class="message-tools"') < source.indexOf('class="message-details"'))
  })
}

for (const failed of [false, true]) {
  test(`复制回答${failed ? '失败' : '成功'}有明确反馈，按原文复制`, async t => {
    let copied
    const { state, notices } = await createChat(t, { writeText: async text => {
      copied = text
      if (failed) throw new Error('denied')
    } })
    await state.copyAnswer({ content: '<script>示例</script>\n代码' })
    assert.equal(copied, '<script>示例</script>\n代码')
    assert.equal(notices.at(-1).type, failed ? 'error' : 'success')
  })
}

for (const decision of ['empty', 'confirm', 'cancel', 'changed']) {
  test(`恢复原问题仅填草稿，已有内容处理：${decision}`, async t => {
    let confirmations = 0
    let fetches = 0
    const { state } = await createChat(t, { confirm: async () => {
      confirmations++
      if (decision === 'cancel') throw new Error('cancel')
      if (decision === 'changed') state.prompt.value = '确认期间的新草稿'
    }, fetch: async () => { fetches++; throw new Error('must not send') } })
    if (decision !== 'empty') state.prompt.value = '已有草稿'
    await state.restoreQuestion({ role: 'user', content: '原问题' })
    assert.equal(state.prompt.value, decision === 'cancel' ? '已有草稿' : decision === 'changed' ? '确认期间的新草稿' : '原问题')
    assert.equal(confirmations, decision === 'empty' ? 0 : 1)
    assert.equal(fetches, 0)
  })
}

test('恢复图片问题要求视觉模型，并保留图片附件和输入焦点', async t => {
  const { state, notices } = await createChat(t)
  const original = { role: 'user', content: '解释图片', images: [{ name: '图', dataUrl: 'data:image/png;base64,AAAA' }] }
  await state.restoreQuestion(original)
  assert.equal(state.prompt.value, '')
  assert.equal(notices.at(-1).type, 'warning')
  state.modelType.value = 'vision_model'
  let focused = false
  state.questionInput.value = { focus() { focused = true } }
  await state.restoreQuestion(original)
  assert.deepEqual(state.pendingImages.value, original.images)
  assert.equal(focused, true)
})

test('较早工具目录请求晚返回不会覆盖最新重试结果', async t => {
  const { state, http } = await createChat(t, { hasPermission: () => true })
  let release
  http.get = () => new Promise(resolve => { release = resolve })
  const earlier = state.loadTools('agent')
  assert.equal(state.toolStates.value.agent, 'loading')
  http.get = async () => ({ data: [{ code: 'HTTP_TRIGGER_8', name: '新工具' }] })
  await state.loadTools('agent')
  release({ data: [{ code: 'HTTP_TRIGGER_7', name: '旧工具' }] })
  await earlier
  assert.deepEqual(state.agentTools.value.map(tool => tool.code), ['HTTP_TRIGGER_8'])
})

test('两类工具都可取消选择，目录失败时不能新增选择，管理入口遵守权限', async t => {
  const { state, requests } = await createChat(t, { hasPermission: permission => permission === 'automation:api-trigger:trigger' })
  state.agentTools.value = [{ code: 'HTTP_TRIGGER_7' }]
  state.toggleTool('agent', 'HTTP_TRIGGER_7', true)
  state.toggleTool('agent', 'HTTP_TRIGGER_7', false)
  assert.deepEqual(state.agentToolCodes.value, [])
  state.workflowTools.value = [{ code: 'WORKFLOW_1' }]
  state.toggleTool('workflow', 'WORKFLOW_1', true)
  state.toggleTool('workflow', 'WORKFLOW_1', false)
  assert.deepEqual(state.workflowToolCodes.value, [])
  state.toolStates.value.agent = 'error'
  state.toggleTool('agent', 'HTTP_TRIGGER_7', true)
  assert.deepEqual(state.agentToolCodes.value, [])
  assert.equal(state.toolGroups.value.some(group => group.canManage), false)
  state.toolsOpen.value = true
  state.openToolManagement('/automation/api-triggers')
  assert.equal(state.toolsOpen.value, false)
  assert.equal(requests.at(-1), '/automation/api-triggers')
})

test('同步服务端终态保留已收到的工具事件，刷新历史可恢复已完成工具', async t => {
  const { state, detail } = await createChat(t)
  state.conversationId.value = 1
  state.serverGenerating.value = true
  state.messages.value = [{ id: 2, role: 'assistant', content: '部分回答', status: 'INTERRUPTED', toolCalls: [{ toolCallId: 'c', success: null }] }]
  detail.messages = [{ id: 2, role: 'assistant', content: '部分回答', status: 'INTERRUPTED', metadata: {} }]
  await state.syncGeneration(1)
  assert.equal(state.messages.value[0].toolCalls.length, 1)
  assert.equal(state.toolStatus(state.messages.value[0].toolCalls[0], state.messages.value[0]), 'interrupted')
  detail.messages[0].metadata.toolCalls = [{ toolCallId: 'c', success: true }]
  detail.messages[0].status = 'COMPLETED'
  await state.selectConversation(1)
  assert.equal(state.toolStatus(state.messages.value[0].toolCalls[0], state.messages.value[0]), 'succeeded')
})

for (const condition of ['busy', 'assistant', 'switched', 'imageDraft']) {
  test(`恢复问题保护当前会话及附件：${condition}`, async t => {
    let confirmations = 0
    const { state } = await createChat(t, { confirm: async () => {
      confirmations++
      if (condition === 'switched') state.conversationId.value = 9
      if (condition === 'imageDraft') throw new Error('cancel')
    } })
    if (condition === 'busy') state.serverGenerating.value = true
    if (condition === 'switched') state.prompt.value = '当前草稿'
    if (condition === 'imageDraft') state.pendingImages.value = [{ name: '未发送图片', dataUrl: 'data:image/png;base64,AAAA' }]
    const draft = state.prompt.value
    const images = [...state.pendingImages.value]
    await state.restoreQuestion({ role: condition === 'assistant' ? 'assistant' : 'user', content: '原问题' })
    assert.equal(state.prompt.value, draft)
    assert.deepEqual(state.pendingImages.value, images)
    assert.equal(confirmations, ['switched', 'imageDraft'].includes(condition) ? 1 : 0)
  })
}
