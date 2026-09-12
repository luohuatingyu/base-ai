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
