import assert from 'node:assert/strict'
import test from 'node:test'
import { readChatStream, restoreChatMessage } from '../src/utils/chatStream.js'

/** 构造真实 ReadableStream，按指定大小切分网络字节。 */
function response(text, size = 1) {
  const bytes = new TextEncoder().encode(text)
  return new Response(new ReadableStream({
    start(controller) {
      for (let index = 0; index < bytes.length; index += size) controller.enqueue(bytes.slice(index, index + size))
      controller.close()
    }
  }), { headers: { 'content-type': 'text/event-stream' } })
}

for (const size of [1, 2, 10000]) {
  test(`SSE 中文跨块、CRLF 和合并事件：${size}`, async () => {
    const events = []
    await readChatStream(response(': comment\r\n\r\ndata: {"type":"start"}\r\n\r\ndata: {"type":"delta","content":"你好"}\r\n\r\ndata: {"type":"done","totalTokens":3}\r\n\r\n', size), event => events.push(event))
    assert.equal(events[1].content, '你好')
    assert.equal(events[2].totalTokens, 3)
  })
}

for (const data of ['', 'data: nope\n\n', 'data: {"type":"unknown"}\n\n',
  'data: {"type":"delta","content":3}\n\n', 'data: {"type":"error"}\n\n',
  'data: {"type":"delta","content":"partial"}\n\n', 'x'.repeat(1024 * 1024 + 1)]) {
  test(`畸形、错误或不完整响应必须失败：${data.slice(0, 60)}`, async () => {
    await assert.rejects(readChatStream(response(data, 65536), () => {}))
  })
}

test('HTTP 鉴权失败保留状态及业务错误', async () => {
  await assert.rejects(readChatStream(new Response('{"message":"login"}', { status: 401 }), () => {}),
    error => error.response.status === 401 && error.message === 'login')
  await assert.rejects(readChatStream(new Response('invalid', { status: 403 }), () => {}),
    error => error.response.status === 403)
  await assert.rejects(readChatStream(new Response('{}'), () => {}))
})

test('Agent 工具状态事件按顺序传递给聊天页', async () => {
  const events = []
  await readChatStream(response('data: {"type":"start"}\n\ndata: {"type":"agent_tool_start","toolName":"订单查询"}\n\ndata: {"type":"agent_tool_done","toolName":"订单查询","success":true}\n\ndata: {"type":"delta","content":"完成"}\n\ndata: {"type":"done","toolCalls":[{"toolName":"订单查询","success":true}]}\n\n'), event => events.push(event))
  assert.deepEqual(events.map(event => event.type), ['start', 'agent_tool_start', 'agent_tool_done', 'delta', 'done'])
  assert.equal(events[2].success, true)
})

test('历史恢复保留统计、状态和文本且拒绝外部图片地址', () => {
  const restored = restoreChatMessage({ id: 1, role: 'user', status: 'COMPLETED', traceId: 'trace',
    metadata: { totalTokens: 3 }, content: [{ type: 'text', text: '说明' },
      { type: 'image_url', image_url: { url: 'data:image/png;base64,YQ==' } },
      { type: 'image_url', image_url: { url: 'https://outside.example' } }] })
  assert.equal(restored.content, '说明')
  assert.equal(restored.images.length, 1)
  assert.equal(restored.totalTokens, 3)
  assert.equal(restored.traceId, 'trace')
  assert.equal(restoreChatMessage({ content: '部分回答', status: 'INTERRUPTED' }).content, '部分回答')
  assert.deepEqual(restoreChatMessage({}).images, [])
})

for (const code of ['llm.defaultPoolUnavailable', 'llm.selectedPoolUnavailable']) {
  test(`路由错误保留安全提示：${code}`, async () => {
    const message = '请检查能力路由并执行同步'
    await assert.rejects(readChatStream(response(`data: ${JSON.stringify({ type: 'error', code, message })}\n\n`), () => {}),
      error => error.message === message && error.streamMessage === message)
  })
}

test('未知错误码及非文本提示不得暴露服务端原始异常', async () => {
  for (const event of [{ code: 'internal', message: 'upstream-secret' },
    { code: 'llm.defaultPoolUnavailable', message: {} }, { message: 'upstream-secret' }]) {
    await assert.rejects(readChatStream(response(`data: ${JSON.stringify({ type: 'error', ...event })}\n\n`), () => {}),
      error => error.message === 'chat.callFailed' && !error.streamMessage)
  }
})
