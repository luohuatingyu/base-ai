import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const viewSource = readFileSync(new URL('../src/views/ApiTriggerSecurityView.vue', import.meta.url), 'utf8')
const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const menuSource = readFileSync(new URL('../src/components/MenuNode.vue', import.meta.url), 'utf8')
const zhSource = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const utilitySource = readFileSync(new URL('../src/utils/hostRules.js', import.meta.url), 'utf8')

test('接口触发安全配置注册独立路由和导航名称', () => {
  assert.match(routerSource, /automation\/api-trigger-security/)
  assert.match(routerSource, /automation:api-trigger-security:view/)
  assert.match(menuSource, /nav\.items\.apiTriggerSecurity/)
  assert.match(zhSource, /触发安全配置/)
})

test('Host 使用 API Key 风格的逐条类型编辑器', () => {
  assert.match(viewSource, /v-for="\(rule, index\) in hostRuleRows"/)
  assert.match(viewSource, /v-model="rule\.type"/)
  assert.match(viewSource, /v-model="rule\.value"/)
  assert.match(viewSource, /addHostRule/)
  assert.match(viewSource, /deleteHostRule\(index\)/)
  assert.doesNotMatch(viewSource, /type="textarea"/)
})

test('配置页面加载和保存结构化 Host 规则', () => {
  assert.match(viewSource, /http\.get\('\/automation\/api-trigger-security'\)/)
  assert.match(viewSource, /http\.put\('\/automation\/api-trigger-security'/)
  assert.match(viewSource, /hostRules,/)
  assert.match(viewSource, /allowLoopback: form\.allowLoopback/)
})

test('五种匹配类型和任意 Host 风险确认均存在', () => {
  for (const type of ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'ANY']) assert.match(utilitySource, new RegExp(type))
  assert.match(viewSource, /rule\.type === 'ANY'/)
  assert.match(viewSource, /hostRules\.some\(rule => rule\.type === 'ANY'\)/)
  assert.match(viewSource, /ElMessageBox\.confirm/)
})

test('回环和私网继续使用独立开关', () => {
  assert.match(viewSource, /allowLoopback: true, allowPrivateNetwork: false/)
  assert.match(viewSource, /v-model="form\.allowLoopback"/)
  assert.match(viewSource, /v-model="form\.allowPrivateNetwork"/)
})
