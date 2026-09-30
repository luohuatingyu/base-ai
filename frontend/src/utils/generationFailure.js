const FAILURE_REASON_KEYS = Object.freeze({
  PROVIDER_REQUEST_FAILED: 'generation.failureReasons.PROVIDER_REQUEST_FAILED',
  PROVIDER_RESPONSE_INVALID: 'generation.failureReasons.PROVIDER_RESPONSE_INVALID',
  PROVIDER_FAILED: 'generation.failureReasons.PROVIDER_FAILED',
  GENERATION_TIMEOUT: 'generation.failureReasons.GENERATION_TIMEOUT',
  MODEL_CONFIG_CHANGED: 'generation.failureReasons.MODEL_CONFIG_CHANGED',
  CANCELLED: 'generation.failureReasons.CANCELLED'
})

/** 解析后端保存的结构化供应商错误，同时兼容历史纯错误码。 */
export function parseGenerationFailure(value) {
  const text = String(value ?? '').trim()
  const parts = text.split('|')
  if (parts.length >= 4 && parts[0] === 'PROVIDER_REQUEST_FAILED') {
    return { code: parts[0], status: parts[1], providerCode: parts[2], detail: parts.slice(3).join('|') }
  }
  return { code: text, status: '', providerCode: '', detail: '' }
}

/** 将媒体生成任务的稳定失败码映射为本地化文案键，未知码由调用方保留原值。 */
export function generationFailureReasonKey(code) {
  return FAILURE_REASON_KEYS[String(code ?? '').trim()] || ''
}
