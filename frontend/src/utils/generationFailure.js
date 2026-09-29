const FAILURE_REASON_KEYS = Object.freeze({
  PROVIDER_REQUEST_FAILED: 'generation.failureReasons.PROVIDER_REQUEST_FAILED',
  PROVIDER_RESPONSE_INVALID: 'generation.failureReasons.PROVIDER_RESPONSE_INVALID',
  PROVIDER_FAILED: 'generation.failureReasons.PROVIDER_FAILED',
  GENERATION_TIMEOUT: 'generation.failureReasons.GENERATION_TIMEOUT',
  MODEL_CONFIG_CHANGED: 'generation.failureReasons.MODEL_CONFIG_CHANGED',
  CANCELLED: 'generation.failureReasons.CANCELLED'
})

/** 将媒体生成任务的稳定失败码映射为本地化文案键，未知码由调用方保留原值。 */
export function generationFailureReasonKey(code) {
  return FAILURE_REASON_KEYS[String(code ?? '').trim()] || ''
}
