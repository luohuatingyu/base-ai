import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
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
  assert.match(viewSource, /scope\.row\.serverName \|\| t\('dataSync\.platformLocal'\)/)
  assert.match(viewSource, /runDetail\.serverName \|\| t\('dataSync\.platformLocal'\)/)
  assert.match(viewSource, /serverId: row\.serverId/)
  assert.match(zhCN.dataSync.platformLocal, /历史计划/)
  assert.match(enUS.dataSync.platformLocal, /legacy plan/)
})
