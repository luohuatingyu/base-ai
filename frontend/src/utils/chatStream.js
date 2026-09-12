/** 读取 SSE 数据帧，支持 UTF-8 跨块、多事件和 CRLF。 */
export async function readChatStream(response, onEvent) {
  if (!response.ok) {
    const data = await response.json().catch(() => ({}))
    throw Object.assign(new Error(data.message || 'chat.callFailed'), { response: { status: response.status, data } })
  }
  if (!response.headers.get('content-type')?.includes('text/event-stream') || !response.body) throw new Error('chat.callFailed')
  const reader = response.body.getReader()
  const decoder = new TextDecoder('utf-8', { fatal: true })
  let buffer = ''
  let ended = false
  try {
    while (!ended) {
      const { value, done } = await reader.read()
      buffer += done ? decoder.decode() : decoder.decode(value, { stream: true })
      buffer = buffer.replace(/\r\n/g, '\n')
      if (buffer.length > 1024 * 1024) throw new Error('chat.callFailed')
      let boundary
      while ((boundary = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, boundary)
        buffer = buffer.slice(boundary + 2)
        const data = frame.split('\n').filter(line => line.startsWith('data:')).map(line => line.slice(5).trimStart()).join('\n')
        if (!data) continue
        const event = JSON.parse(data)
        if (!['start', 'delta', 'heartbeat', 'done', 'error'].includes(event.type)) throw new Error('chat.callFailed')
        if (event.type === 'delta' && typeof event.content !== 'string') throw new Error('chat.callFailed')
        if (event.type === 'error') throw new Error('chat.callFailed')
        onEvent(event)
        if (event.type === 'done') { ended = true; break }
      }
      if (done && !ended) throw new Error('chat.streamInterrupted')
    }
  } finally {
    await reader.cancel().catch(() => {})
    reader.releaseLock()
  }
}

/** 将数据库消息转换为聊天页展示格式，图片只接受持久化的 Data URL。 */
export function restoreChatMessage(message) {
  const parts = Array.isArray(message.content) ? message.content : []
  return {
    ...message.metadata,
    id: message.id,
    role: message.role,
    status: message.status,
    traceId: message.traceId,
    content: typeof message.content === 'string' ? message.content : parts.filter(part => part.type === 'text').map(part => part.text).join('\n'),
    images: parts.filter(part => part.type === 'image_url' && /^data:image\/(png|jpeg|webp);base64,/.test(part.image_url?.url))
      .map((part, index) => ({ name: String(index + 1), dataUrl: part.image_url.url }))
  }
}
