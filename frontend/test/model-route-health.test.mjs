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

test('capability route actions remain direct children of the section header', () => {
  assert.match(routeView, /<div class="section-head">\s*<div>[\s\S]*?<\/div>\s*<el-button[^>]*model:route:create/)
  assert.match(routeView, /<\/el-button>\s*<el-button[^>]*model:route:update[^>]*@click="openSync\(\)"/)
  assert.doesNotMatch(routeView, /<div class="route-actions">/)
})

test('selected capability routes render independent synchronization tabs', () => {
  assert.match(routeView, /<el-tabs[^>]*v-model="activeSyncRouteId"/)
  assert.match(routeView, /v-for="route in selectedRoutes"[^>]*:name="String\(route\.id\)"[^>]*:label="route\.featureCode"/)
  assert.match(routeView, /syncStates\[routeId\] = \{ results: \[\], completed: false, syncing: false, error: '' \}/)
  assert.match(routeView, /syncState\(route\.id\)\.results/)
  assert.match(routeView, /syncState\(route\.id\)\.error/)
})

test('selected capability routes use one batch request and distribute route results', () => {
  assert.match(routeView, /http\.post\('\/models\/routes\/sync\/batch', \{ routeIds: selectedRouteIds\.value \}\)/)
  assert.match(routeView, /response\.data\.find\(item => item\.routeId === route\.id\)/)
  assert.doesNotMatch(routeView, /for \(const route of selectedRoutes\.value\)[\s\S]*http\.post\('\/models\/routes\/sync'/)
})

test('route synchronization does not ask users to select providers and keeps row sync compatibility', () => {
  assert.doesNotMatch(routeView, /v-model="syncProviderIds"/)
  assert.match(routeView, /@click="openSync\(scope\.row\)"/)
  assert.match(routeView, /selectedRouteIds\.value = row \? \[row\.id\] : \[\]/)
  assert.match(routeView, /http\.delete\(`\/models\/routes\/\$\{route\.id\}\/providers\/\$\{result\.providerId\}`\)/)
})
