import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { baseCompile } from '@intlify/message-compiler'
import { createI18n } from 'vue-i18n'
import enMessages from '../src/locales/en-US.js'
import zhMessages from '../src/locales/zh-CN.js'

const viewSource = readFileSync(new URL('../src/views/ApiKeysView.vue', import.meta.url), 'utf8')
const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigationSource = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')
const zhSource = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enSource = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')

test('API Key 管理页面注册独立路由和导航权限', () => {
  assert.match(routerSource, /path: 'api-keys'/)
  assert.match(routerSource, /system:api-key:list/)
  assert.match(navigationSource, /nav\.items\.apiKeys/)
  assert.match(zhSource, /API Key 管理/)
})

test('API Key 页面支持绑定用户、接口范围、IP 和限流', () => {
  assert.match(viewSource, /ownerUserId/)
  assert.match(viewSource, /endpointCodes/)
  assert.match(viewSource, /allowedCidrs/)
  assert.match(viewSource, /rateLimitPerMinute/)
  assert.match(viewSource, /\/system\/api-keys\/endpoints/)
})

test('API Key 操作列提供充足宽度并固定在表格右侧', () => {
  assert.match(viewSource, /:label="t\('common\.operation'\)" width="320" fixed="right"/)
})

test('开放 API 名称和分组随当前语言动态翻译', () => {
  assert.match(viewSource, /translateEndpoint\(endpoint\.nameKey, endpoint\.code\)/)
  assert.match(viewSource, /translateEndpoint\(endpoint\.groupKey, endpoint\.code\)/)
  assert.match(viewSource, /te\(translationKey\)/)
  assert.match(viewSource, /:\s*endpointCode/)
  assert.match(zhSource, /endpointNames:\s*\{\s*aiChatInvoke:\s*'AI 对话调用'/)
  assert.match(zhSource, /endpointGroups:\s*\{\s*ai:\s*'AI 能力'/)
  assert.match(enSource, /endpointNames:\s*\{\s*aiChatInvoke:\s*'AI Chat Invocation'/)
  assert.match(enSource, /endpointGroups:\s*\{\s*ai:\s*'AI Capabilities'/)
})

test('API Key 页面支持永久有效和指定过期时间', () => {
  assert.match(viewSource, /v-model="form\.neverExpires"/)
  assert.match(viewSource, /v-if="!form\.neverExpires"/)
  assert.match(viewSource, /expiresAt: form\.neverExpires \? null : form\.expiresAt/)
  assert.match(viewSource, /neverExpiresConfirm/)
})

test('完整 API Key 仅在创建或轮换后展示并支持复制', () => {
  assert.match(viewSource, /showSecret\(data\.apiKey\)/)
  assert.match(viewSource, /navigator\.clipboard\.writeText/)
  assert.match(viewSource, /\/rotate`/)
  assert.match(viewSource, /http\.delete\(`/)
})

test('API Key 页面提供默认收起的双语调用指南', () => {
  assert.match(viewSource, /<el-collapse v-model="expandedUsageSections" class="api-key-usage">/)
  assert.match(viewSource, /<el-collapse-item name="usage-guide">/)
  assert.match(viewSource, /const expandedUsageSections = ref\(\[\]\)/)
  assert.match(viewSource, /apiKeyUsage\.curlExample/)
  assert.match(zhSource, /apiKeyUsage:\s*\{\s*title:\s*'使用说明'/)
  assert.match(enSource, /apiKeyUsage:\s*\{\s*title:\s*'Usage Guide'/)
})

test('API Key 调用指南说明请求头、示例接口和安全限制', () => {
  for (const source of [zhSource, enSource]) {
    assert.match(source, /X-API-Key/)
    assert.match(source, /\/api\/ai\/chat/)
    assert.match(source, /Authorization/)
  }
})

test('API Key curl 示例通过消息编译并原样渲染 JSON', () => {
  const expected = 'curl -X POST <BASE_URL>/api/ai/chat\n  -H "X-API-Key: sk-<your-api-key>"\n  -H "Content-Type: application/json"\n  -d \'{"messages":[{"role":"user","content":"hello"}]}\''
  for (const [locale, messages] of [['zh-CN', zhMessages], ['en-US', enMessages]]) {
    const source = messages.apiKeyUsage.curlExample
    assert.doesNotThrow(() => baseCompile(source, { onError: error => { throw error } }))
    const i18n = createI18n({ legacy: false, locale, warnHtmlMessage: false, messages: { [locale]: messages } })
    assert.equal(i18n.global.t('apiKeyUsage.curlExample'), expected)
  }
})
