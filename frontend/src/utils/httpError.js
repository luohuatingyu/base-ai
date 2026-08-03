const NOTIFIED_FLAG = '__httpErrorNotified'
const SILENT_FLAG = '__httpErrorSilent'

/** 判断异常是否为客户端主动取消的请求。 */
export function isCancelledRequest(error) {
  return error?.code === 'ERR_CANCELED' || error?.name === 'CanceledError'
}

/** 标记异常为静默处理，供认证跳转和后台轮询避免弹窗。 */
export function silenceHttpError(error) {
  if (error && typeof error === 'object') error[SILENT_FLAG] = true
  return error
}

/** 标记异常已经展示，避免全局拦截器与页面逻辑重复提示。 */
export function markHttpErrorNotified(error) {
  if (error && typeof error === 'object') error[NOTIFIED_FLAG] = true
  return error
}

/** 将 Blob 错误响应中的统一 JSON 信封转换为普通对象。 */
export async function normalizeHttpErrorResponse(error) {
  const data = error?.response?.data
  if (!(data instanceof Blob)) return error
  try {
    const parsed = JSON.parse(await data.text())
    error.response.data = parsed
  } catch { /* 非 JSON 下载错误保留 Blob，由状态码提供兜底提示。 */ }
  return error
}

/** 按 HTTP 状态、网络状态和后端消息生成统一的用户提示。 */
export function classifyHttpError(error, t, fallbackKey = 'common.failed') {
  if (error?.[SILENT_FLAG] || isCancelledRequest(error)) return { silent: true }
  const status = error?.response?.status
  const backendMessage = readableMessage(error?.response?.data?.message)
  if (!error?.response) {
    const timeout = error?.code === 'ECONNABORTED' || error?.code === 'ETIMEDOUT'
    if (timeout) return { silent: false, type: 'error', message: t('httpErrors.timeout') }
    if (error?.isAxiosError || error?.request || error?.code === 'ERR_NETWORK') {
      return { silent: false, type: 'error', message: t('httpErrors.network') }
    }
    return { silent: false, type: 'error', message: readableMessage(error?.message) || t(fallbackKey) }
  }
  const type = [400, 401, 404, 409, 422, 429].includes(status) ? 'warning' : 'error'
  return {
    silent: false,
    type,
    message: backendMessage || t(statusKey(status) || fallbackKey)
  }
}

/** 判断当前异常是否已经由统一提示处理。 */
export function isHttpErrorNotified(error) {
  return Boolean(error?.[NOTIFIED_FLAG])
}

/** 返回各状态码无后端消息时使用的本地化键。 */
function statusKey(status) {
  if (status === 400) return 'httpErrors.badRequest'
  if (status === 401) return 'httpErrors.unauthorized'
  if (status === 403) return 'httpErrors.forbidden'
  if (status === 404) return 'httpErrors.notFound'
  if (status === 409) return 'httpErrors.conflict'
  if (status === 422) return 'httpErrors.unprocessable'
  if (status === 429) return 'httpErrors.tooManyRequests'
  if (status === 502) return 'httpErrors.badGateway'
  if (status === 503) return 'httpErrors.serviceUnavailable'
  if (status === 504) return 'httpErrors.gatewayTimeout'
  if (status >= 500) return 'httpErrors.server'
  if (status >= 400) return 'httpErrors.client'
  return null
}

/** 过滤空白或非字符串后端消息，避免展示无意义内容。 */
function readableMessage(value) {
  return typeof value === 'string' && value.trim() ? value.trim() : ''
}
