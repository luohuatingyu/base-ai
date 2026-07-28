import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import {
  buildCurlExample,
  buildDebugRequest,
  buildEndpointPath,
  formatDebugResponse,
  formatJsonRequestBody
} from '../src/utils/openPlatform.js'

const chatEndpoint = {
  method: 'POST', path: '/api/ai/chat', pathParameters: [],
  requestFields: [{ name: 'messages' }],
  requestExample: '{"messages":[{"role":"user","content":"hello"}]}'
}

const triggerEndpoint = {
  method: 'POST', path: '/api/automation/api-triggers/{id}/trigger',
  pathParameters: [{ name: 'id', required: true, example: '1' }], requestFields: [], requestExample: ''
}

test('path parameters are required and URL encoded', () => {
  assert.equal(buildEndpointPath(triggerEndpoint, { id: 'a/b' }), '/api/automation/api-triggers/a%2Fb/trigger')
  assert.throws(() => buildEndpointPath(triggerEndpoint, { id: '' }), /PATH_PARAMETER_REQUIRED:id/)
})

test('chat debugger sends only API Key credentials and parsed JSON', () => {
  const request = buildDebugRequest(chatEndpoint, {
    apiKey: ' sk-secret ', pathValues: {}, requestBody: chatEndpoint.requestExample, locale: 'zh-CN'
  })
  assert.equal(request.headers['X-API-Key'], 'sk-secret')
  assert.equal(request.headers.Authorization, undefined)
  assert.equal(request.headers['Content-Type'], 'application/json')
  assert.deepEqual(request.data.messages, [{ role: 'user', content: 'hello' }])
})

test('trigger debugger replaces path and omits an empty request body', () => {
  const request = buildDebugRequest(triggerEndpoint, {
    apiKey: 'sk-secret', pathValues: { id: '42' }, requestBody: '', locale: 'en-US'
  })
  assert.equal(request.url, '/api/automation/api-triggers/42/trigger')
  assert.equal(request.data, undefined)
  assert.equal(request.headers['Content-Type'], undefined)
})

test('debugger rejects missing credentials, bodies, and invalid JSON', () => {
  assert.throws(() => buildDebugRequest(chatEndpoint, { apiKey: '', requestBody: '{}', locale: 'en-US' }), /API_KEY_REQUIRED/)
  assert.throws(() => buildDebugRequest(chatEndpoint, { apiKey: 'sk-x', requestBody: '', locale: 'en-US' }), /REQUEST_BODY_REQUIRED/)
  assert.throws(() => buildDebugRequest(chatEndpoint, { apiKey: 'sk-x', requestBody: '{', locale: 'en-US' }), /INVALID_JSON/)
})

test('curl and response formatting follow endpoint metadata', () => {
  const curl = buildCurlExample(triggerEndpoint)
  assert.match(curl, /POST '<BASE_URL>\/api\/automation\/api-triggers\/1\/trigger'/)
  assert.match(curl, /X-API-Key/)
  assert.doesNotMatch(curl, /Content-Type/)
  assert.equal(formatDebugResponse({ success: true }), '{\n  "success": true\n}')
})

test('request body formatting produces readable JSON and rejects invalid input', () => {
  assert.equal(formatJsonRequestBody('{"messages":[]}'), '{\n  "messages": []\n}')
  assert.throws(() => formatJsonRequestBody(''), /REQUEST_BODY_REQUIRED/)
  assert.throws(() => formatJsonRequestBody('{'), /INVALID_JSON/)
})

test('public route and view enforce guest access and credential safety contracts', async () => {
  const [router, view, login, styles, zh, en] = await Promise.all([
    readFile(new URL('../src/router/index.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/views/OpenPlatformView.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8'),
    readFile(new URL('../src/styles.css', import.meta.url), 'utf8'),
    readFile(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')
  ])
  assert.match(router, /path: '\/open-platform'.*meta: \{ public: true \}/)
  assert.match(router, /if \(to\.meta\.guestOnly\)/)
  assert.match(view, /ElMessageBox\.confirm/)
  assert.match(view, /onBeforeUnmount/)
  assert.match(view, /<el-tabs[^>]*:key="locale"[^>]*v-model="activeTab"/)
  assert.match(view, /name="documentation"/)
  assert.match(view, /name="debugger"/)
  assert.match(view, /class="open-platform-endpoint-summary"/)
  assert.match(view, /class="open-platform-endpoint-description"/)
  assert.match(view, /@keydown\.(?:ctrl|meta)\.enter="sendRequest"/)
  assert.match(view, /formatRequestBody/)
  assert.match(view, /resetRequestBody/)
  assert.match(view, /copyText\(curlExample/)
  assert.match(view, /copyText\(debugResult\.body/)
  assert.match(styles, /\.open-platform-debug-result pre\s*\{[^}]*overflow:\s*auto[^}]*max-height:\s*620px[^}]*white-space:\s*pre-wrap[^}]*word-break:\s*break-word[^}]*overflow-wrap:\s*anywhere[^}]*\}/)
  assert.doesNotMatch(view, /localStorage\.setItem\(['"]apiKey/)
  assert.doesNotMatch(view, /sessionStorage/)
  assert.match(login, /to="\/open-platform"/)
  assert.match(zh, /endpointDescriptions/)
  assert.match(en, /endpointDescriptions/)
  assert.match(en, /endpoints:\s*'API Endpoints'/)
  assert.doesNotMatch(en, /endpoints:\s*'API Categories'/)
  assert.match(styles, /\.open-platform-endpoint-heading\s*\{[^}]*display:\s*grid[^}]*grid-template-columns:\s*minmax\(0,\s*1fr\)\s+auto[^}]*grid-template-areas:\s*"summary path"\s*"description description"[^}]*\}/)
  assert.match(styles, /\.open-platform-endpoint-summary\s*\{[^}]*grid-area:\s*summary[^}]*\}/)
  assert.match(styles, /\.open-platform-endpoint-description\s*\{[^}]*grid-area:\s*description[^}]*\}/)
  assert.match(styles, /\.open-platform-path\s*\{[^}]*grid-area:\s*path[^}]*align-self:\s*center[^}]*justify-self:\s*end[^}]*max-width:\s*100%[^}]*\}/)
  assert.match(styles, /@media \(max-width:\s*1000px\)[\s\S]*?\.open-platform-endpoint-heading\s*\{[^}]*grid-template-areas:\s*"summary"\s*"path"\s*"description"[^}]*\}/)
})
