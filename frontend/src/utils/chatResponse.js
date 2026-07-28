/** 将业务数据和统一响应 traceId 转换为前端消息及其可展示元数据。 */
export function createAssistantMessage(response = {}, traceId = null) {
  return {
    role: 'assistant',
    content: response.content || '',
    model: typeof response.model === 'string' && response.model.trim() ? response.model.trim() : null,
    traceId: typeof traceId === 'string' && traceId.trim() ? traceId.trim() : null,
    inputTokens: normalizeTokenCount(response.inputTokens),
    outputTokens: normalizeTokenCount(response.outputTokens),
    totalTokens: normalizeTokenCount(response.totalTokens)
  }
}

/** 判断 AI 回复是否包含可展示的模型调用元数据。 */
export function hasChatResponseMetadata(message) {
  return Boolean(message?.model || message?.traceId || message?.inputTokens !== null || message?.outputTokens !== null || message?.totalTokens !== null)
}

/** 仅保留非负整数 Token 数，避免异常响应破坏页面展示。 */
function normalizeTokenCount(value) {
  return Number.isInteger(value) && value >= 0 ? value : null
}
