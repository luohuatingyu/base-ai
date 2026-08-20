import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { createAssistantMessage, hasChatResponseMetadata } from '../src/utils/chatResponse.js'

const chatView = readFileSync(new URL('../src/views/AiChatView.vue', import.meta.url), 'utf8')
const chatStyles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')

test('从统一响应顶层映射 Trace ID 和模型业务数据', () => {
  const message = createAssistantMessage({
    content: '你好',
    model: 'gpt-4.1',
    traceId: 'nested-trace-must-be-ignored',
    inputTokens: 12,
    outputTokens: 34,
    totalTokens: 46
  }, '  846c581da692410f85df9693b1aa926a  ')

  assert.deepEqual(message, {
    role: 'assistant',
    content: '你好',
    model: 'gpt-4.1',
    traceId: '846c581da692410f85df9693b1aa926a',
    inputTokens: 12,
    outputTokens: 34,
    totalTokens: 46
  })
  assert.equal(hasChatResponseMetadata(message), true)
})

test('零 Token 仍作为有效的调用统计展示', () => {
  const message = createAssistantMessage({ content: '空响应', inputTokens: 0, outputTokens: 0, totalTokens: 0 })

  assert.equal(message.inputTokens, 0)
  assert.equal(message.outputTokens, 0)
  assert.equal(message.totalTokens, 0)
  assert.equal(hasChatResponseMetadata(message), true)
})

test('缺失或非法元数据不会影响 AI 回复内容', () => {
  const message = createAssistantMessage({ content: '仍可显示', model: ' ', inputTokens: -1, outputTokens: '3' })

  assert.equal(message.content, '仍可显示')
  assert.equal(message.model, null)
  assert.equal(message.inputTokens, null)
  assert.equal(message.outputTokens, null)
  assert.equal(message.totalTokens, null)
  assert.equal(message.traceId, null)
  assert.equal(hasChatResponseMetadata(message), false)
})

test('Trace ID 在有任务权限时可直达链路日志', () => {
  assert.match(chatView, /v-if="item\.traceId && auth\.hasPermission\('system:task:view'\)"/)
  assert.match(chatView, /@click="openTraceLogs\(item\.traceId\)"/)
  assert.match(chatView, /router\.push\(\{ path: '\/tasks', query: \{ traceId, openLogs: 'true' \} \}\)/)
  assert.match(chatView, /<span v-else-if="item\.traceId">\{\{ t\('chat\.traceId'\) \}\}: \{\{ item\.traceId \}\}<\/span>/)
  assert.match(chatView, /const response = await http\.post\('\/ai\/chat', payload\)/)
  assert.match(chatView, /createAssistantMessage\(response\.data, response\.traceId\)/)
  assert.doesNotMatch(chatView, /lastTrace/)
  assert.equal(chatView.match(/t\('chat\.traceId'\)/g)?.length, 2)
})

test('助手回答使用内容自适应背景并缩小字体', () => {
  assert.match(chatView, /<div class="message-content">\{\{ item\.content \}\}<\/div>/)
  assert.match(chatStyles, /\.message-content\s*\{[\s\S]*?display:\s*inline-block[\s\S]*?max-width:\s*100%[\s\S]*?overflow-wrap:\s*anywhere/)
  assert.match(chatStyles, /\.message\.assistant \.message-content\s*\{\s*font-size:\s*14px;\s*\}/)
  assert.doesNotMatch(chatStyles, /\.message div\s*\{/)
})

test('消息不展示角色标签且用户问题贴齐右侧', () => {
  assert.doesNotMatch(chatView, /<small>\{\{ item\.role === 'user'/)
  assert.doesNotMatch(chatStyles, /\.message small\s*\{/)
  assert.match(chatStyles, /\.message\.user\s*\{\s*margin-left:\s*auto;\s*text-align:\s*right;\s*\}/)
  assert.match(chatStyles, /\.message\.user \.message-content\s*\{\s*color:\s*#fff;\s*background:\s*var\(--app-primary\);\s*\}/)
})
