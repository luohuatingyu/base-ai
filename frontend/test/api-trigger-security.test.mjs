import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const viewSource = readFileSync(new URL('../src/views/ApiTriggerSecurityView.vue', import.meta.url), 'utf8')
const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigationSource = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')
const zhSource = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enSource = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')
const utilitySource = readFileSync(new URL('../src/utils/hostRules.js', import.meta.url), 'utf8')

test('接口触发安全配置注册独立路由和导航名称', () => {
  assert.match(routerSource, /automation\/api-trigger-security/)
  assert.match(routerSource, /automation:api-trigger-security:view/)
  assert.match(navigationSource, /nav\.items\.apiTriggerSecurity/)
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

test('配置页面加载并自动应用结构化 Host 规则', () => {
  assert.match(viewSource, /http\.get\('\/automation\/api-trigger-security'\)/)
  assert.match(viewSource, /http\.put\('\/automation\/api-trigger-security', configuration\)/)
  assert.match(viewSource, /hostRules: normalizeHostRules\(configuration\?\.hostRules\)/)
  assert.match(viewSource, /allowLoopback: form\.allowLoopback/)
  assert.match(viewSource, /createLatestAutoSaver/)
  assert.match(viewSource, /@input="scheduleAutomaticSave\(\)"/)
  assert.match(viewSource, /@change="scheduleAutomaticSave\(true\)"/)
  assert.doesNotMatch(viewSource, /@click="save"/)
  assert.doesNotMatch(viewSource, /t\('common\.save'\)/)
})

test('配置接口返回前不展示本地默认状态', () => {
  assert.match(viewSource, /const loading = ref\(true\)/)
  assert.match(viewSource, /const loaded = ref\(false\)/)
  assert.match(viewSource, /v-loading="loading"/)
  assert.match(viewSource, /<template v-if="loaded">/)
  assert.match(viewSource, /effectiveConfiguration\.value = normalizeConfiguration\(data\)[\s\S]*replaceEditor\(effectiveConfiguration\.value\)[\s\S]*loaded\.value = true/)
})

test('五种匹配类型和任意 Host 风险确认均存在', () => {
  for (const type of ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'ANY']) assert.match(utilitySource, new RegExp(type))
  assert.match(viewSource, /rule\.type === 'ANY'/)
  assert.match(viewSource, /hostRules\.some\(rule => rule\.type === 'ANY'\)/)
  assert.match(viewSource, /ElMessageBox\.confirm/)
  assert.match(viewSource, /autoSaver\.clear\(\)/)
  assert.match(viewSource, /replaceEditor\(effectiveConfiguration\.value\)/)
})

test('回环和私网继续使用独立开关', () => {
  assert.match(viewSource, /allowLoopback: true, allowPrivateNetwork: false/)
  assert.match(viewSource, /v-model="form\.allowLoopback"/)
  assert.match(viewSource, /v-model="form\.allowPrivateNetwork"/)
})

test('无更新权限时编辑器只读且不会调度保存', () => {
  assert.match(viewSource, /const canUpdate = computed\(\(\) => auth\.hasPermission\('automation:api-trigger-security:update'\)\)/)
  assert.match(viewSource, /<el-form[^>]*:disabled="!canUpdate"/)
  assert.match(viewSource, /if \(!loaded\.value \|\| !canUpdate\.value\) return/)
})

test('自动保存展示应用状态并在失败时恢复最近生效配置', () => {
  assert.match(viewSource, /saveStatus\.value = 'saving'/)
  assert.match(viewSource, /saveStatus\.value = 'saved'/)
  assert.match(viewSource, /saveStatus\.value = 'error'/)
  assert.match(viewSource, /ElMessage\.error\(t\('apiTriggerSecurity\.autoSaveFailed'\)\)/)
  for (const source of [zhSource, enSource]) {
    assert.match(source, /autoSaveFailed:/)
    assert.match(source, /autoSaveStatus: \{ saved:/)
  }
})
