import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const paginatedViews = [
  ['UsersView.vue', 1],
  ['RolesView.vue', 1],
  ['PositionsView.vue', 1],
  ['DictionariesView.vue', 2],
  ['OnlineUsersView.vue', 1],
  ['OperationLogsView.vue', 1],
  ['LoginLogsView.vue', 1]
]

test('系统管理分页默认每页显示五条', () => {
  for (const [view, expectedPaginationCount] of paginatedViews) {
    const source = readFileSync(new URL(`../src/views/${view}`, import.meta.url), 'utf8')
    const configuredSizes = [...source.matchAll(/page:\s*1\s*,\s*size:\s*(\d+)/g)].map(match => Number(match[1]))

    assert.equal(configuredSizes.length, expectedPaginationCount, `${view} 的分页配置数量不符`)
    assert.deepEqual(configuredSizes, Array(expectedPaginationCount).fill(5), `${view} 的默认分页大小不是 5`)
  }
})

test('任务调度支持按 Trace ID 检索和重置', () => {
  const source = readFileSync(new URL('../src/views/TasksView.vue', import.meta.url), 'utf8')

  assert.match(source, /v-model="query\.traceId"/)
  assert.match(source, /if \(query\.traceId\) params\.traceId = query\.traceId/)
  assert.match(source, /traceId:\s*''/)
  assert.match(source, /Object\.assign\(query,\s*\{[\s\S]*?traceId:\s*''/)
})

test('系统参数页面支持分页、模糊搜索和托管参数保护', () => {
  const source = readFileSync(new URL('../src/views/SettingsView.vue', import.meta.url), 'utf8')

  assert.match(source, /query = reactive\(\{ page: 1, size: 10, configKey: '' \}\)/)
  assert.match(source, /http\.get\('\/system\/settings', \{ params: query \}\)/)
  assert.match(source, /settings\.keySearchPlaceholder/)
  assert.match(source, /!scope\.row\.systemManaged/)
  assert.match(source, /form\.systemManaged/)
  assert.match(source, /shortKey\(row\)/)
})
