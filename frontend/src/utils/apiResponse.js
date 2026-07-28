/** 解包统一响应并保留当前请求的标准化 traceId。 */
export function unwrapApiResponse(response = {}) {
  const envelope = response.data
  const bodyTraceId = normalizeTraceId(envelope?.traceId)
  const headerTraceId = normalizeTraceId(readHeader(response.headers, 'x-trace-id'))
  response.traceId = bodyTraceId || headerTraceId
  if (envelope && typeof envelope.success === 'boolean' && 'data' in envelope) {
    response.api = envelope
    response.data = envelope.data
  }
  return response
}

/** 兼容 AxiosHeaders 和普通响应头对象读取指定字段。 */
function readHeader(headers, name) {
  if (typeof headers?.get === 'function') return headers.get(name)
  return headers?.[name] ?? headers?.[name.toUpperCase()] ?? null
}

/** 规范化后端响应 traceId，过滤空值和异常超长内容。 */
function normalizeTraceId(value) {
  if (typeof value !== 'string') return null
  const normalized = value.trim()
  return normalized && normalized.length <= 64 ? normalized : null
}
