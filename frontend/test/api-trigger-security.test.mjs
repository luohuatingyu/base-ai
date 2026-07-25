import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const viewSource = readFileSync(new URL('../src/views/ApiTriggerSecurityView.vue', import.meta.url), 'utf8')
const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const menuSource = readFileSync(new URL('../src/components/MenuNode.vue', import.meta.url), 'utf8')
const zhSource = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')

test('接口触发安全配置注册独立路由和导航名称', () => {
  assert.match(routerSource, /automation\/api-trigger-security/)
  assert.match(routerSource, /automation:api-trigger-security:view/)
  assert.match(menuSource, /nav\.items\.apiTriggerSecurity/)
  assert.match(zhSource, /触发安全配置/)
})

test('配置页面加载和保存运行时安全配置', () => {
  assert.match(viewSource, /http\.get\('\/automation\/api-trigger-security'\)/)
  assert.match(viewSource, /http\.put\('\/automation\/api-trigger-security'/)
  assert.match(viewSource, /automation:api-trigger-security:update/)
  assert.match(viewSource, /split\(\/\[\\n,\]\//)
  assert.match(viewSource, /v-model="form\.allowLoopback"/)
  assert.match(viewSource, /allowLoopback: form\.allowLoopback/)
})

test('星号、回环和私网全部开放时展示警告并二次确认', () => {
  assert.match(viewSource, /parseAllowedHosts\(\)\.includes\('\*'\)/)
  assert.match(viewSource, /allowedHosts\.includes\('\*'\) && form\.allowLoopback && form\.allowPrivateNetwork/)
  assert.match(viewSource, /ElMessageBox\.confirm/)
  assert.match(viewSource, /wildcardWarning/)
})

test('页面默认不要求填写回环 Host 并关闭其他私网', () => {
  assert.match(viewSource, /allowedHosts: '', allowLoopback: true, allowPrivateNetwork: false/)
  assert.match(zhSource, /allowedHostsPlaceholder: 'api\.example\.com/)
  assert.doesNotMatch(zhSource, /allowedHostsPlaceholder: 'localhost/)
})
