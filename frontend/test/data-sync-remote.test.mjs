import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import { computed, reactive, ref } from 'vue'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const viewSource = readFileSync(new URL('../src/views/DataSyncView.vue', import.meta.url), 'utf8')

test('数据同步计划必须选择启用的执行服务器', () => {
  assert.match(viewSource, /v-model="form\.serverId"/)
  assert.match(viewSource, /http\.get\('\/data-sync\/servers'\)/)
  assert.match(viewSource, /:disabled="!item\.enabled"/)
  assert.match(viewSource, /!form\.serverId \|\| !form\.sourceConnectionId/)
  assert.equal(zhCN.dataSync.server, '执行服务器')
  assert.equal(enUS.dataSync.server, 'Execution Server')
})

test('表查询和预检均携带所选服务器', () => {
  assert.match(viewSource, /params: \{ serverId: form\.serverId \}/)
  assert.match(viewSource, /serverId: form\.serverId, strategy: form\.strategy/)
  assert.match(viewSource, /form = reactive\(\{ id: null, name: '', serverId: null/)
})

test('计划和运行明细展示实际服务器并兼容历史计划', () => {
  assert.match(viewSource, /plan\.serverName \|\| t\('dataSync\.platformLocal'\)/)
  assert.match(viewSource, /selectedPlan\.serverName \|\| t\('dataSync\.platformLocal'\)/)
  assert.match(viewSource, /runDetail\.serverName \|\| t\('dataSync\.platformLocal'\)/)
  assert.match(viewSource, /serverId: row\.serverId/)
  assert.match(zhCN.dataSync.platformLocal, /历史计划/)
  assert.match(enUS.dataSync.platformLocal, /legacy plan/)
})

// 执行页面中的实际状态格式化方法，校验所有展示分支及语言回退。
function statusFormatter(locale) {
  const source = ['statusType', 'statusLabel'].map(name => viewSource.match(new RegExp(`function ${name}\\(status\\) \\{[\\s\\S]*?\\n\\}`))[0]).join('\n')
  return runInNewContext(`${source}\n({ statusType, statusLabel })`, {
    t: key => key.split('.').reduce((value, segment) => value?.[segment], locale),
  })
}

for (const [status, color] of [['PENDING', 'info'], ['RUNNING', 'primary'], ['SUCCEEDED', 'success'], ['SUCCESS', 'success'], ['FAILED', 'danger'], ['CANCEL_REQUESTED', 'warning'], ['CANCELLED', 'info'], ['SKIPPED', 'info']]) {
  test(`同步状态 ${status} 在中英文中有文案及语义颜色`, () => {
    for (const locale of [zhCN, enUS]) {
      const formatter = statusFormatter(locale)
      assert.equal(formatter.statusType(status), color)
      assert.equal(formatter.statusLabel(status), locale.dataSync.statuses[status])
      assert.ok(formatter.statusLabel(status))
      assert.notEqual(formatter.statusLabel(status), status)
    }
  })
}

test('空状态和后端扩展状态不产生缺失翻译', () => {
  for (const locale of [zhCN, enUS]) {
    const formatter = statusFormatter(locale)
    for (const status of [null, undefined, '']) {
      assert.equal(formatter.statusLabel(status), locale.dataSync.notRun)
      assert.equal(formatter.statusType(status), 'info')
    }
    for (const status of ['FUTURE_STATUS', '__proto__', 'toString', '<script>alert(1)</script>']) {
      assert.equal(formatter.statusLabel(status), status)
      assert.equal(formatter.statusType(status), 'info')
    }
  }
})

test('重排配置区保留权限、全量替换确认及表选择绑定', () => {
  assert.match(viewSource, /auth\.hasPermission\(form\.id \? 'operations:data-sync:update' : 'operations:data-sync:create'\)/)
  for (const permission of ['preview', 'update', 'run', 'cancel', 'logs', 'delete']) {
    assert.ok(viewSource.includes(`auth.hasPermission('operations:data-sync:${permission}')`))
  }
  assert.match(viewSource, /v-if="form\.strategy === 'FULL_REPLACE'"[\s\S]*?v-model="form\.confirmDestructive"/)
  assert.match(viewSource, /v-if="sourceTables\.length" v-model="selectedTables"/)
  assert.match(viewSource, /v-else class="sync-empty-tables"/)
  assert.match(viewSource, /:disabled="running\(selectedPlan\)"/)
  assert.match(viewSource, /:disabled="!running\(selectedPlan\)"/)
})

// 使用 Vue 真实响应式执行页面脚本，仅隔离网络与消息组件。
function workspace(overrides = {}) {
  const script = viewSource.match(/<script setup>([\s\S]*?)<\/script>/)[1].replace(/^import .*$/gm, '')
  return runInNewContext(`${script}\n;({ form, plans, connections, sourceTables, selectedTables, tableSearch, planSearch, statusFilter, filteredPlans, filteredTables, overviewMetrics, selectedPlan, selectedPlanId, editorVisible, saving, createPlan, edit, loadTables, save, closeEditor, resetFilters, connectionName })`, {
    computed, reactive, ref, onMounted: () => {},
    useI18n: () => ({ t: (key, params) => key === 'dataSync.unavailableConnection' ? `Unavailable #${params.id}` : key }),
    useAuthStore: () => ({ hasPermission: () => true }),
    Connection: 'connection', CircleCheck: 'check', Refresh: 'refresh', Warning: 'warning',
    http: { get: async () => ({ data: [] }), post: async () => ({}), put: async () => ({}) },
    ElMessage: { warning: () => {}, success: () => {} }, showHttpError: () => {}, ...overrides,
  })
}

for (const [keyword, status, expected] of [
  ['', 'ALL', [1, 2, 3, 4]], ['  ORDERS ', 'ALL', [1]], ['warehouse', 'ALL', [1, 2]],
  ['worker-a', 'FAILED', [2]], ['', 'SUCCEEDED', [3, 4]], ['', 'NOT_RUN', [1]],
  ['missing', 'ALL', []], ['<script>', 'ALL', []], ['', 'RUNNING', []],
]) {
  test(`同步任务联合筛选 ${keyword}/${status}`, () => {
    const page = workspace()
    page.connections.value = [{ id: 9, name: 'Warehouse' }]
    page.plans.value = [
      { id: 1, name: 'Orders', sourceConnectionId: 9, lastRunStatus: null },
      { id: 2, name: 'Daily', targetConnectionId: 9, serverName: 'worker-a', lastRunStatus: 'FAILED' },
      { id: 3, name: 'Legacy', lastRunStatus: 'SUCCESS' }, { id: 4, name: 'Current', lastRunStatus: 'SUCCEEDED' },
    ]
    page.planSearch.value = keyword
    page.statusFilter.value = status
    assert.deepEqual(Array.from(page.filteredPlans.value, plan => plan.id), expected)
  })
}

test('统计独立于筛选，选中摘要随筛选、删除和刷新同步', () => {
  const page = workspace()
  assert.equal(page.selectedPlan.value, null)
  assert.deepEqual(Array.from(page.overviewMetrics.value, metric => metric.value), [0, 0, 0, 0])
  page.plans.value = [
    { id: 1, name: 'One', enabled: true, lastRunStatus: 'RUNNING' },
    { id: 2, name: 'Two', enabled: false, lastRunStatus: 'CANCEL_REQUESTED' },
    { id: 3, name: 'Three', enabled: true, lastRunStatus: 'FAILED' },
  ]
  page.selectedPlanId.value = 2
  assert.equal(page.selectedPlan.value.id, 2)
  page.statusFilter.value = 'FAILED'
  assert.equal(page.selectedPlan.value.id, 3)
  assert.deepEqual(Array.from(page.overviewMetrics.value, metric => metric.value), [3, 2, 2, 1])
  page.planSearch.value = 'nothing'
  assert.equal(page.selectedPlan.value, null)
  page.resetFilters()
  assert.equal(page.selectedPlan.value.id, 2)
  page.plans.value = [{ id: 1, name: 'Updated' }]
  assert.equal(page.selectedPlan.value.name, 'Updated')
  assert.equal(page.connectionName(42), 'Unavailable #42')
  assert.equal(page.connectionName(null), 'Unavailable #—')
})

test('表搜索兼容 Schema 和大小写，隐藏所选表不丢失选择', () => {
  const page = workspace()
  page.sourceTables.value = [{ name: 'orders', schema: 'PUBLIC' }, { name: 'users' }]
  page.selectedTables.value = ['orders']
  for (const [keyword, expected] of [[' public ', ['orders']], ['USER', ['users']], ['missing', []], ['', ['orders', 'users']]]) {
    page.tableSearch.value = keyword
    assert.deepEqual(Array.from(page.filteredTables.value, table => table.name), expected)
    assert.deepEqual(Array.from(page.selectedTables.value), ['orders'])
  }
})

test('编辑回填、新建重置及源连接切换保持正确请求和选择', async () => {
  const calls = []
  const page = workspace({ http: { get: async (...args) => { calls.push(args); return { data: [{ name: 'orders' }] } } } })
  await page.edit({ id: 7, name: 'Daily', serverId: 3, sourceConnectionId: 4, targetConnectionId: 5, strategy: 'APPEND', enabled: true, tables: [{ sourceTable: 'orders' }] })
  assert.equal(page.editorVisible.value, true)
  assert.equal(page.form.id, 7)
  assert.equal(page.form.serverId, 3)
  assert.equal(page.form.targetConnectionId, 5)
  assert.equal(page.form.scheduleCron, '')
  assert.equal(calls[0][0], '/data-sync/connections/4/tables')
  assert.equal(calls[0][1].params.serverId, 3)
  assert.deepEqual(Array.from(page.selectedTables.value), ['orders'])
  page.tableSearch.value = 'orders'
  page.form.sourceConnectionId = null
  await page.loadTables()
  assert.equal(page.sourceTables.value.length, 0)
  assert.equal(page.selectedTables.value.length, 0)
  assert.equal(page.tableSearch.value, '')
  page.createPlan()
  assert.equal(page.form.id, null)
  assert.equal(page.form.name, '')
  assert.equal(page.form.serverId, null)
  assert.equal(page.form.targetConnectionId, null)
  assert.equal(page.form.strategy, 'UPSERT')
  assert.equal(page.form.confirmDestructive, false)
  assert.equal(page.editorVisible.value, true)
})

test('保存失败保留抽屉与输入，成功后关闭，保存期间不可关闭', async () => {
  let fail = true
  const requests = [], errors = []
  const page = workspace({ http: {
    get: async () => ({ data: [] }),
    post: async (url, body) => { requests.push({ url, body }); if (fail) throw new Error('unavailable'); return {} },
  }, showHttpError: error => errors.push(error.message) })
  page.createPlan()
  await page.save()
  assert.equal(requests.length, 0)
  Object.assign(page.form, { name: 'Daily', serverId: 3, sourceConnectionId: 4, targetConnectionId: 5 })
  page.selectedTables.value = ['orders']
  await page.save()
  assert.equal(page.editorVisible.value, true)
  assert.equal(page.form.name, 'Daily')
  assert.deepEqual(errors, ['unavailable'])
  assert.equal(page.saving.value, false)
  fail = false
  await page.save()
  assert.equal(page.editorVisible.value, false)
  assert.equal(requests[1].url, '/data-sync/plans')
  assert.equal(requests[1].body.tables[0].sourceTable, 'orders')
  let closed = 0
  page.saving.value = true
  page.closeEditor(() => closed++)
  assert.equal(closed, 0)
  page.saving.value = false
  page.closeEditor(() => closed++)
  assert.equal(closed, 1)
})
