import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { createAssistantMessage, hasChatResponseMetadata } from '../src/utils/chatResponse.js'

const chatView = readFileSync(new URL('../src/views/AiChatView.vue', import.meta.url), 'utf8')
const chatStyles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')

test('将模型响应映射为带模型和 Token 用量的 AI 消息', () => {
  const message = createAssistantMessage({
    content: '你好',
    model: 'gpt-4.1',
    traceId: '  846c581da692410f85df9693b1aa926a  ',
    inputTokens: 12,
    outputTokens: 34,
    totalTokens: 46
  })

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

test('Trace ID 仅在助手消息元数据中展示', () => {
  assert.match(chatView, /<span v-if="item\.traceId">\{\{ t\('chat\.traceId'\) \}\}: \{\{ item\.traceId \}\}<\/span>/)
  assert.doesNotMatch(chatView, /lastTrace/)
  assert.equal(chatView.match(/t\('chat\.traceId'\)/g)?.length, 1)
})

test('助手回答使用内容自适应背景并缩小字体', () => {
  assert.match(chatView, /<div class="message-content">\{\{ item\.content \}\}<\/div>/)
  assert.match(chatStyles, /\.message-content\s*\{[\s\S]*?display:\s*inline-block[\s\S]*?max-width:\s*100%[\s\S]*?overflow-wrap:\s*anywhere/)
  assert.match(chatStyles, /\.message\.assistant \.message-content\s*\{\s*font-size:\s*14px;\s*\}/)
  assert.doesNotMatch(chatStyles, /\.message div\s*\{/)
})

test('用户消息继续使用原有主题背景', () => {
  assert.match(chatStyles, /\.message\.user \.message-content\s*\{\s*color:\s*#fff;\s*background:\s*var\(--app-primary\);\s*\}/)
})
