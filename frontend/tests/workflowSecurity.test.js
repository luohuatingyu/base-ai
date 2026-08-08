import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const apiKeysView = readFileSync(new URL('../src/views/ApiKeysView.vue', import.meta.url), 'utf8')
const securityView = readFileSync(new URL('../src/views/ApiTriggerSecurityView.vue', import.meta.url), 'utf8')
const en = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')
const zh = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')

test('API Key 工作流白名单按绑定用户加载且切换所有者清理旧授权', () => {
  assert.match(apiKeysView, /workflow-options/)
  assert.match(apiKeysView, /v-model="form\.workflowIds"[\s\S]*multiple/)
  assert.match(apiKeysView, /async function changeOwner\(ownerUserId\)[\s\S]*form\.workflowIds = \[\]/)
  assert.match(apiKeysView, /workflowIds: form\.workflowIds/)
})

test('历史空白名单在选择工作流接口时展示默认拒绝警告', () => {
  assert.match(apiKeysView, /workflowEndpointSelected && !form\.workflowIds\.length/)
  assert.match(apiKeysView, /workflowDefaultDeny/)
  assert.match(en, /workflowDefaultDeny:/)
  assert.match(zh, /workflowDefaultDeny:/)
})

test('工作流连接器使用独立 Host 与 CIDR 策略接口', () => {
  assert.match(securityView, /http\.get\('\/workflow\/network-security'\)/)
  assert.match(securityView, /http\.put\('\/workflow\/network-security', \{ hostRules, allowedCidrs:/)
  assert.match(securityView, /workflowCidrsText/)
  assert.match(en, /workflowImportedNotice:/)
  assert.match(zh, /workflowImportedNotice:/)
})
