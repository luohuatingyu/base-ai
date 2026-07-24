import assert from 'node:assert/strict'
import test from 'node:test'
import { createAssistantMessage, hasChatResponseMetadata } from '../src/utils/chatResponse.js'

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
