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
    FileReader: overrides.FileReader,
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

test('模型池不可用时显示同步建议且保留会话和已收到内容', async t => {
  const message = '默认模型池没有支持所选类型的可用模型，请检查能力路由并执行同步'
  const { state } = await createChat(t, { fetch: async () => streamResponse([
    { type: 'start', messageId: 2 }, { type: 'delta', content: '保留内容' },
    { type: 'error', code: 'llm.defaultPoolUnavailable', message }
  ]) })
  state.prompt.value = '1111'
  await state.send()
  assert.equal(state.pageError.value, message)
  assert.equal(state.messages.value.at(-1).content, '保留内容')
  assert.equal(state.messages.value.at(-1).status, 'INTERRUPTED')
  assert.equal(state.loading.value, false)
})

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

test('聊天页面不再加载或提交媒体生成接口', async t => {
  const { state, requests } = await createChat(t)
  state.prompt.value = 'hello'
  await state.send()
  assert.doesNotMatch(source, /generationMode|generationModel|onGenerationInputSelected|sendGeneration/)
  assert.doesNotMatch(requests.join('\n'), /\/ai\/chat\/generation/)
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
