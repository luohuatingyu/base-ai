import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import { CONNECTION_TYPES } from '../src/utils/workflowConnectionConfig.js'

const viewSource = readFileSync(new URL('../src/views/DataSourcesView.vue', import.meta.url), 'utf8')
const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigationSource = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')

test('数据源管理页面覆盖全部受管连接和安全占位符', () => {
  for (const type of ['MYSQL', 'POSTGRESQL', 'REDIS', 'S3', 'KAFKA', 'RABBITMQ', 'WEBHOOK', 'TAVILY', 'QDRANT', 'MILVUS', 'ELASTICSEARCH', 'PLUGIN']) {
    assert.ok(CONNECTION_TYPES.includes(type), type)
  }
  assert.match(viewSource, /\/data-sources/)
  assert.match(viewSource, /operations:data-source:create/)
  assert.match(viewSource, /operations:data-source:update/)
  assert.match(viewSource, /operations:data-source:delete/)
  assert.match(viewSource, /operations:data-source:test/)
  assert.match(zhCN.workflowConnections.maskHelp, /\*\*\*\*\*\*/)
  assert.match(enUS.workflowConnections.maskHelp, /\*\*\*\*\*\*/)
})

test('数据源与数据同步路由平级且旧连接维护入口已移除', () => {
  assert.match(routerSource, /path: 'data-sources'/)
  assert.match(routerSource, /permission: 'operations:data-source:list'/)
  assert.match(navigationSource, /'\/data-sources': 'nav\.items\.dataSources'/)
  assert.doesNotMatch(routerSource, /path: 'workflow\/connections'/)
  assert.doesNotMatch(navigationSource, /'\/workflow\/connections'/)
  assert.equal(zhCN.nav.items.dataSources, '数据源管理')
  assert.equal(enUS.nav.items.dataSources, 'Data Source Management')
})
