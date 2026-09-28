import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import * as Vue from 'vue'
import { compileScript, compileTemplate, compileStyle, parse } from '@vue/compiler-sfc'
import { renderToString } from '@vue/server-renderer'
import { createI18n } from 'vue-i18n'
import * as icons from '@element-plus/icons-vue'
import * as ElementPlus from 'element-plus'
import { ID_INJECTION_KEY, ZINDEX_INJECTION_KEY } from 'element-plus'
import * as metrics from '../src/utils/dashboardMetrics.js'
import { hasEffectivePermission } from '../src/utils/permissions.js'
import zh from '../src/locales/zh-CN.js'
import en from '../src/locales/en-US.js'

const panelSource = readFileSync(new URL('../src/components/ModelUsagePanel.vue', import.meta.url), 'utf8')
const dashboardSource = readFileSync(new URL('../src/views/DashboardView.vue', import.meta.url), 'utf8')

/** 编译真实单文件组件，注入 HTTP 边界与生命周期调度，保留 Vue 响应式和模板逻辑。 */
function component(source = panelSource, { user = { roles: ['ADMIN'] }, get = async () => ({ data: {} }), language = 'zh-CN' } = {}) {
  const hooks = { mounted: [], unmounted: [], interval: null, cleared: null }
  const i18n = createI18n({ legacy: false, locale: language, messages: { 'zh-CN': zh, 'en-US': en } })
  const auth = { isAdmin: user?.roles?.includes('ADMIN') || false, hasPermission: permission => hasEffectivePermission(user, permission) }
  const dependencies = {
    vue: { ...Vue, onMounted: callback => hooks.mounted.push(callback), onUnmounted: callback => hooks.unmounted.push(callback) },
    'vue-i18n': { useI18n: () => i18n.global },
    '@element-plus/icons-vue': icons,
    '../api/http': { get },
    '../stores/auth': { useAuthStore: () => auth },
    '../utils/dashboardMetrics': metrics,
    '../components/ModelUsagePanel.vue': { render: () => null }
  }
  const { descriptor } = parse(source)
  const script = compileScript(descriptor, { id: 'dashboard-test' })
  const template = compileTemplate({ source: descriptor.template.content, id: 'dashboard-test', filename: 'DashboardTest.vue', compilerOptions: { bindingMetadata: script.bindings } })
  assert.deepEqual(template.errors, [])
  // 将模块引用绑定到真实库或外部依赖替身，不改写组件内部业务逻辑。
  const imports = code => code.replace(/import\s+([\s\S]*?)\s+from\s+['"]([^'"]+)['"]/g, (_, names, path) => {
    const bindings = names.trim().replace(/\bas\b/g, ':')
    return `const ${bindings} = dependencies[${JSON.stringify(path)}]`
  })
  const window = { setInterval: callback => { hooks.interval = callback; return 1 }, clearInterval: id => { hooks.cleared = id } }
  const definition = new Function('dependencies', 'window', imports(script.content).replace('export default', 'return'))(dependencies, window)
  const state = definition.setup({}, { expose() {} })
  const render = new Function('dependencies', `${imports(template.code).replace('export function render', 'function render')}\nreturn render`)(dependencies)
  return {
    state, hooks, i18n,
    async html() {
      const app = Vue.createSSRApp({ render: () => render({}, [], {}, Vue.proxyRefs(state), {}, {}) })
      app.provide(ID_INJECTION_KEY, { prefix: 100, current: 0 })
      app.provide(ZINDEX_INJECTION_KEY, { current: 0 })
      // Element Plus 表格依赖浏览器挂载完成后才渲染行；SSR 使用等价的行插槽适配器。
      const table = { props: ['data'], setup(props, { slots }) { Vue.provide('testRows', props.data); return () => Vue.h('table', slots.default?.()) } }
      const column = { props: ['prop'], setup(props, { slots }) { const rows = Vue.inject('testRows'); return () => Vue.h('tbody', rows.map(row => Vue.h('tr', slots.default ? slots.default({ row }) : String(row[props.prop] ?? '')))) } }
      for (const [name, entry] of Object.entries(ElementPlus)) {
        if (name.startsWith('El') && name !== 'ElTable' && name !== 'ElTableColumn') app.component(name, entry)
      }
      app.component('ElTable', table)
      app.component('ElTableColumn', column)
      app.directive('loading', { getSSRProps: () => ({}) })
      return renderToString(app)
    }
  }
}

/** 构造包含实际时间边界的接口数据，方便验证汇总与明细口径一致。 */
function summary(overrides = {}) {
  return { data: { from: '2026-09-26T00:00:00Z', to: '2026-09-27T00:00:00Z', overview: { calls: 10, success: 8, running: 1, totalTokens: 12345, averageFirstTokenLatencyMs: null, averageDurationMs: 1200 }, models: [], errors: [], ...overrides } }
}

for (const value of [null, undefined, '', '   ', false, true, -1, Infinity, NaN, 'invalid', [], {}]) {
  test(`未知或非法指标使用占位符：${String(value)}`, () => {
    assert.equal(metrics.formatCount(value, 'en-US'), '—')
    assert.equal(metrics.formatMilliseconds(value, 'en-US'), '—')
    assert.equal(metrics.formatSuccessRate(value, 10, 'en-US'), '—')
    assert.equal(metrics.formatSuccessRate(1, value, 'en-US'), '—')
    assert.equal(component().state.milliseconds(value), '—')
  })
}
for (const [value, expected] of [[0, '0'], ['0', '0'], [' 1200 ', '1,200'], [1234.6, '1,235'], [9999999999, '9,999,999,999']]) {
  test(`合法指标按语言格式化并保留零值：${value}`, () => {
    assert.equal(metrics.formatCount(value, 'en-US'), expected)
    assert.equal(metrics.formatMilliseconds(value, 'en-US'), `${expected} ms`)
  })
}
test('成功率覆盖无调用、全部失败、全部成功及不一致汇总', () => {
  for (const [success, calls, expected] of [[0, 0, '—'], [0, 10, '0.0%'], [10, 10, '100.0%'], [8, 10, '80.0%'], [11, 10, '—'], [1, -1, '—']]) {
    assert.equal(metrics.formatSuccessRate(success, calls, 'en-US'), expected)
  }
  assert.equal(metrics.formatCount(1234, 'de-DE'), '1.234')
})

test('模型使用面板展示标题并为无数据区域提供明确空状态', async () => {
  const c = component()
  await c.state.load()
  const html = await c.html()
  assert.match(html, /模型使用监控/)
  assert.match(html, /当前范围没有使用记录/)
  assert.match(html, /当前范围没有错误/)
  assert.doesNotMatch(html, /el-table__body/)
  assert.equal(c.state.metrics.value[1].value, '—')
})

test('模型使用面板不会把无效或负延迟渲染成误导性数值', () => {
  const { state } = component()
  for (const value of [-2, 'invalid', null]) assert.equal(state.milliseconds(value), '—')
  assert.equal(state.milliseconds(1234.5), '1,235 ms')
})

test('模型使用筛选和双列区域在窄屏下能够收缩换行', () => {
  assert.match(panelSource, /grid-template-columns: repeat\(2, minmax\(0, 1fr\)/)
  assert.match(panelSource, /\.model-usage-columns > div \{ min-width: 0; \}/)
  assert.match(panelSource, /@media \(max-width: 600px\)[\s\S]*?\.model-usage-filters \.el-date-editor, \.model-usage-filters \.el-input, \.model-usage-filters \.el-select \{ flex-basis: 100%; min-width: 100%; \}/)
  assert.match(panelSource, /\.model-usage-metrics \{ grid-template-columns: repeat\(3, minmax\(0, 1fr\)\)/)
  assert.match(panelSource, /show-overflow-tooltip/)
  assert.match(dashboardSource, /@media \(max-width: 380px\)/)
})

for (const user of [null, { roles: ['USER'], permissions: [] }, { roles: ['USER'], permissions: ['operations:dashboard:view'] }]) {
  test(`权限控制：${JSON.stringify(user)}`, async () => {
    const calls = []
    const options = { user, get: async path => { calls.push(path); return { data: { tasks24h: 3 } } } }
    const panel = component(panelSource, options)
    for (const hook of panel.hooks.mounted) hook()
    await panel.state.load()
    await panel.state.refresh()
    await panel.state.loadRecords()
    assert.equal(calls.length, 0)
    assert.equal(panel.hooks.interval, null)
    assert.doesNotMatch(await panel.html(), /模型使用监控/)
    const dashboard = component(dashboardSource, options)
    await dashboard.state.load()
    assert.equal(calls.length, user?.permissions?.length ? 1 : 0)
    assert.equal(dashboard.state.canView.value, Boolean(user?.permissions?.length))
    if (!user?.permissions?.length) assert.match(await dashboard.html(), /没有任务概览查看权限/)
  })
}

test('任务概览显示真实值与告警，失败清空且允许刷新恢复', async () => {
  let fail = false
  const c = component(dashboardSource, { get: async () => {
    if (fail) throw new Error('timeout')
    return { data: { tasks24h: 2000, success24h: 1990, failed24h: 10, running: 0, alerts: 2 } }
  } })
  assert.equal(c.state.stats.tasks24h, null)
  await c.state.load()
  assert.equal(c.state.stats.alerts, 2)
  assert.match(await c.html(), /2,000/)
  assert.match(await c.html(), /has-alerts/)
  assert.ok(c.state.updatedAt.value)
  fail = true
  await c.state.load()
  assert.equal(c.state.stats.tasks24h, null)
  assert.equal(c.state.updatedAt.value, '')
  assert.match(await c.html(), /任务概览加载失败/)
  fail = false
  await c.state.load()
  assert.equal(c.state.error.value, false)
  assert.equal(c.state.stats.tasks24h, 2000)
})

test('汇总与明细共享日期边界，分页和刷新不应用尚未提交的筛选', async () => {
  const calls = []
  const c = component(panelSource, { get: async (url, config) => {
    calls.push([url, config.params])
    return url.endsWith('summary') ? summary() : { data: { total: 42, records: [{ modelName: 'model-a', status: 'SUCCESS', source: 'CHAT', inputTokens: 10, outputTokens: 20, durationMs: 100 }] } }
  } })
  await c.state.load()
  assert.equal(calls[1][1].from, summary().data.from)
  assert.equal(c.state.metrics.value[1].value, '80.0%')
  assert.equal(c.state.metrics.value[3].value, '—')
  c.state.filters.modelName = '  next-model  '
  c.state.pagination.page = 2
  await c.state.loadRecords()
  assert.equal(calls.at(-1)[1].page, 2)
  assert.equal(calls.at(-1)[1].modelName, undefined)
  await c.state.refresh()
  assert.equal(c.state.pagination.page, 2)
  assert.equal(calls.at(-2)[1].modelName, undefined)
  await c.state.load()
  assert.equal(c.state.pagination.page, 1)
  assert.equal(calls.at(-2)[1].modelName, 'next-model')
  c.state.pagination.pageSize = 50
  await c.state.loadRecords()
  assert.equal(calls.at(-1)[1].pageSize, 50)
  assert.equal(c.state.records.value[0].modelName, 'model-a')
  const html = await c.html()
  assert.match(html, /model-a/)
  assert.match(html, /成功/)
  assert.match(html, /对话/)
  await c.state.reset()
  assert.equal(c.state.filters.modelName, '')
  assert.deepEqual(calls.at(-2)[1], {})
})

for (const range of [[], null, ['2026-09-01', '2026-09-02'], ['2026-01-01', '2026-04-01'], ['bad', 'date'], ['2026-09-01'], ['2026-09-01', '2026-09-01'], ['2026-09-02', '2026-09-01'], ['2026-01-01', '2026-04-02']]) {
  test(`日期边界验证：${JSON.stringify(range)}`, async () => {
    const calls = []
    const c = component(panelSource, { get: async url => { calls.push(url); return summary() } })
    c.state.dateRange.value = range
    await c.state.load()
    const valid = !range?.length || (range.length === 2 && new Date(range[1]) > new Date(range[0]) && new Date(range[1]) - new Date(range[0]) <= 90 * 86400000)
    assert.equal(c.state.rangeError.value, !valid)
    assert.equal(calls.length, valid ? 2 : 0)
    if (valid && range?.length) assert.equal(c.state.appliedParams.value.from, new Date(range[0]).toISOString())
    if (!valid) assert.match(await c.html(), /不超过 90 天/)
  })
}

for (const failure of ['summary', 'records']) {
  test(`接口失败不会展示成功空状态，支持重试：${failure}`, async () => {
    let fail = false
    const c = component(panelSource, { get: async url => {
      if (fail && url.endsWith(failure)) throw new Error('timeout')
      return url.endsWith('summary') ? summary() : { data: { records: [{ modelName: 'before' }], total: 1 } }
    } })
    await c.state.load()
    fail = true
    await c.state.refresh()
    assert.equal(c.state.records.value.length, 0)
    assert.equal(c.state.error.value, failure === 'summary')
    assert.equal(c.state.recordsError.value, failure === 'records')
    const html = await c.html()
    assert.match(html, /加载失败/)
    if (failure === 'summary') {
      assert.equal(c.state.metrics.value[0].value, '—')
      assert.doesNotMatch(html, /当前范围没有错误/)
    } else assert.equal(c.state.metrics.value[0].value, '10')
    fail = false
    await c.state.refresh()
    assert.equal(c.state.records.value[0].modelName, 'before')
    assert.equal(c.state.error.value, false)
    assert.equal(c.state.recordsError.value, false)
  })
}

test('重复刷新被合并，离开页面清理定时器并忽略未完成请求', async () => {
  let finish
  let calls = 0
  const c = component(panelSource, { get: () => { calls++; return new Promise(resolve => { finish = resolve }) } })
  for (const hook of c.hooks.mounted) hook()
  assert.equal(c.state.loading.value, true)
  await c.state.load()
  await c.hooks.interval()
  assert.equal(calls, 1)
  for (const hook of c.hooks.unmounted) hook()
  assert.equal(c.hooks.cleared, 1)
  finish(summary())
  await new Promise(resolve => setImmediate(resolve))
  assert.deepEqual(c.state.overview.value, {})
  assert.equal(calls, 1)
  await c.state.refresh()
  assert.equal(calls, 1)
})

test('中英文标签、状态颜色和未知值兼容，服务端文本按普通文本转义', async () => {
  const c = component(panelSource, { language: 'en-US', get: async url => url.endsWith('summary') ? summary() : { data: { total: 1, records: [{ modelName: '<img src=x onerror=alert(1)>', source: 'FUTURE', status: 'NEW', durationMs: -1 }] } } })
  for (const [status, expected] of [['SUCCESS', 'success'], ['FAILED', 'danger'], ['INTERRUPTED', 'warning'], ['CANCELLED', 'info'], ['RUNNING', 'primary'], ['NEW', 'info']]) assert.equal(c.state.statusType(status), expected)
  assert.equal(c.state.sourceLabel(null), '—')
  assert.equal(c.state.statusLabel(null), '—')
  assert.equal(c.state.sourceLabel('CHAT'), 'Chat')
  assert.equal(c.state.sourceLabel('CHAT_STREAM'), 'Public API stream')
  assert.equal(c.state.statusLabel('SUCCESS'), 'Success')
  await c.state.load()
  const html = await c.html()
  assert.match(html, /Model usage monitoring/)
  assert.match(html, /&lt;img/)
  assert.doesNotMatch(html, /<img src=x/)
  assert.match(html, /FUTURE/)
  assert.match(html, /NEW/)
  assert.doesNotMatch(html, /modelUsage\./)
  c.i18n.global.locale.value = 'zh-CN'
  assert.equal(c.state.sourceLabel('CHAT'), '对话')
  assert.equal(c.state.sourceLabel('CHAT_STREAM'), '公开 API 流式对话')
  assert.match(await c.html(), /模型使用监控/)
})

// 日期组件内部节点没有页面 scope 属性，必须穿透作用域才能约束实际输入框宽度。
test('日期范围内部输入框应用容器宽度，避免手机端被裁切', () => {
  const { descriptor } = parse(panelSource)
  const compiled = compileStyle({ source: descriptor.styles[0].content, id: 'data-v-dashboard-test', scoped: true })
  assert.deepEqual(compiled.errors, [])
  assert.match(compiled.code, /\.model-usage-filters\[data-v-dashboard-test\] \.el-date-editor[^{}]*\{[^}]*width: 100%;[^}]*min-width: 0;[^}]*max-width: 100%;/)
})
