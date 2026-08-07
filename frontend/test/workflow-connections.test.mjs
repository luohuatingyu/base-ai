import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const viewSource = readFileSync(new URL('../src/views/WorkflowConnectionsView.vue', import.meta.url), 'utf8')
const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigationSource = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')

test('连接管理页面覆盖七类受管连接和安全占位符', () => {
  for (const type of ['MYSQL', 'POSTGRESQL', 'REDIS', 'S3', 'KAFKA', 'RABBITMQ', 'WEBHOOK']) {
    assert.match(viewSource, new RegExp(`\\b${type}\\b`), type)
  }
  assert.match(viewSource, /\/workflow\/connections/)
  assert.match(viewSource, /workflow:connection:create/)
  assert.match(viewSource, /workflow:connection:update/)
  assert.match(viewSource, /workflow:connection:delete/)
  assert.match(zhCN.workflowConnections.maskHelp, /\*\*\*\*\*\*/)
  assert.match(enUS.workflowConnections.maskHelp, /\*\*\*\*\*\*/)
})

test('连接管理路由、导航和双语资源保持一致', () => {
  assert.match(routerSource, /path: 'workflow\/connections'/)
  assert.match(routerSource, /permission: 'workflow:connection:list'/)
  assert.match(navigationSource, /'\/workflow\/connections': 'nav\.items\.workflowConnections'/)
  assert.equal(zhCN.nav.items.workflowConnections, '连接配置')
  assert.equal(enUS.nav.items.workflowConnections, 'Connections')
})
