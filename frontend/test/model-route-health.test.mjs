import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

import { canRemoveModelProvider, healthStatusClass } from '../src/utils/modelRouteHealth.js'

const routeView = readFileSync(new URL('../src/views/ModelRoutesView.vue', import.meta.url), 'utf8')
const zhLocale = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enLocale = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')

test('maps route health statuses to required colors', () => {
  assert.equal(healthStatusClass('HEALTHY'), 'is-healthy')
  assert.equal(healthStatusClass('WARNING'), 'is-warning')
  assert.equal(healthStatusClass('SLOW'), 'is-slow')
  assert.equal(healthStatusClass('FAILED'), 'is-failed')
})

test('only healthy results hide provider removal', () => {
  assert.equal(canRemoveModelProvider('HEALTHY'), false)
  assert.equal(canRemoveModelProvider('WARNING'), true)
  assert.equal(canRemoveModelProvider('SLOW'), true)
  assert.equal(canRemoveModelProvider('FAILED'), true)
})

test('capability route page warns that edits require synchronization', () => {
  assert.match(routeView, /<el-alert[^>]*t\('routes\.editSyncNotice'\)[^>]*type="warning"[^>]*:closable="false"/)
  assert.match(zhLocale, /editSyncNotice:\s*'[^']*编辑能力路由后[^']*同步[^']*生效[^']*'/)
  assert.match(enLocale, /editSyncNotice:\s*'[^']*editing a capability route[^']*Sync[^']*take effect[^']*'/)
})

test('route synchronization does not ask users to select providers', () => {
  assert.doesNotMatch(routeView, /v-model="syncProviderIds"/)
  assert.match(routeView, /http\.post\('\/models\/routes\/sync',\s*\{\s*routeId:\s*syncRoute\.value\.id\s*\}\)/)
  assert.match(routeView, /await load\(\)[\s\S]*syncRoute\.value = rows\.value\.find/)
})
