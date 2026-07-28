import assert from 'node:assert/strict'
import test from 'node:test'
import { unwrapApiResponse } from '../src/utils/apiResponse.js'
import { createAssistantMessage } from '../src/utils/chatResponse.js'

test('统一响应解包后 AI 消息回显根级 Trace ID', () => {
  const response = unwrapApiResponse({
    headers: { 'x-trace-id': 'header-trace' },
    data: {
      success: true,
      code: 200,
      message: 'Operation successful',
      traceId: 'root-trace',
      data: {
        content: '你好',
        model: 'test-model',
        traceId: 'nested-trace-must-be-ignored',
        inputTokens: 1,
        outputTokens: 2,
        totalTokens: 3
      }
    }
  })

  const message = createAssistantMessage(response.data, response.traceId)

  assert.equal(response.traceId, 'root-trace')
  assert.equal(response.api.traceId, 'root-trace')
  assert.equal(message.traceId, 'root-trace')
  assert.equal(message.content, '你好')
})

test('响应体缺少 Trace ID 时使用响应头兜底', () => {
  const response = unwrapApiResponse({
    headers: { 'x-trace-id': 'header-trace' },
    data: { success: true, code: 200, message: 'ok', data: { content: 'fallback' } }
  })

  assert.equal(response.traceId, 'header-trace')
  assert.deepEqual(response.data, { content: 'fallback' })
})

test('普通非统一响应保持业务数据结构不变', () => {
  const response = unwrapApiResponse({ headers: {}, data: { status: 'UP' } })

  assert.deepEqual(response.data, { status: 'UP' })
  assert.equal(response.traceId, null)
})
