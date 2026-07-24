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

test('capability route page provides a top-level multi-route sync entry', () => {
  assert.match(routeView, /@click="open\(\)"[^>]*>[\s\S]*routes\.add/)
  assert.match(routeView, /@click="openSync\(\)"[^>]*>[\s\S]*routes\.syncRoutes/)
  assert.match(routeView, /<el-select v-model="selectedRouteIds" multiple filterable/)
  assert.match(routeView, /v-for="route in rows"[^>]*`\$\{route\.name\} \(\$\{route\.featureCode\}\)`/)
  assert.match(zhLocale, /syncRoutes:\s*'同步路由'/)
  assert.match(enLocale, /syncRoutes:\s*'Sync Routes'/)
})

test('selected capability routes render independent synchronization tabs', () => {
  assert.match(routeView, /<el-tabs[^>]*v-model="activeSyncRouteId"/)
  assert.match(routeView, /v-for="route in selectedRoutes"[^>]*:name="String\(route\.id\)"[^>]*:label="route\.featureCode"/)
  assert.match(routeView, /syncStates\[routeId\] = \{ results: \[\], completed: false, syncing: false, error: '' \}/)
  assert.match(routeView, /syncState\(route\.id\)\.results/)
  assert.match(routeView, /syncState\(route\.id\)\.error/)
})

test('selected capability routes synchronize separately and retain partial failures', () => {
  assert.match(routeView, /for \(const route of selectedRoutes\.value\)/)
  assert.match(routeView, /http\.post\('\/models\/routes\/sync', \{ routeId: route\.id \}\)/)
  assert.match(routeView, /catch \(error\)[\s\S]*failedCount \+= 1[\s\S]*state\.error =/)
  assert.match(routeView, /syncPartialCompleted/)
})

test('route synchronization does not ask users to select providers and keeps row sync compatibility', () => {
  assert.doesNotMatch(routeView, /v-model="syncProviderIds"/)
  assert.match(routeView, /@click="openSync\(scope\.row\)"/)
  assert.match(routeView, /selectedRouteIds\.value = row \? \[row\.id\] : \[\]/)
  assert.match(routeView, /http\.delete\(`\/models\/routes\/\$\{route\.id\}\/providers\/\$\{result\.providerId\}`\)/)
})
