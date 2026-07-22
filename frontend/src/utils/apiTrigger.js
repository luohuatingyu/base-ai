const JOB_ID_KEYS = ['jobId', 'job_id']
const MAX_JOB_ID_LENGTH = 64

/**
 * 将接口响应中的候选任务编号规范为可查询文本。
 */
function normalizeJobId(value) {
  if (typeof value !== 'string' && typeof value !== 'number') return ''
  const normalized = String(value).trim()
  return normalized && normalized.length <= MAX_JOB_ID_LENGTH ? normalized : ''
}

/**
 * 从平台统一响应或直接响应中提取任务编号。
 */
export function extractJobId(responseBody) {
  if (responseBody == null || responseBody === '') return ''
  let parsed = responseBody
  if (typeof responseBody === 'string') {
    try {
      parsed = JSON.parse(responseBody)
    } catch {
      return ''
    }
  }
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) return ''
  const nested = parsed.data && typeof parsed.data === 'object' && !Array.isArray(parsed.data)
    ? parsed.data
    : {}
  for (const source of [nested, parsed]) {
    for (const key of JOB_ID_KEYS) {
      const jobId = normalizeJobId(source[key])
      if (jobId) return jobId
    }
  }
  return ''
}

/**
 * 关闭认证时将已隐藏的令牌页安全切回业务接口页。
 */
export function resolveActiveTab(authEnabled, activeTab) {
  return !authEnabled && activeTab === 'auth' ? 'business' : activeTab
}

/**
 * 并行查询平台任务详情和统一任务日志。
 */
export async function fetchTaskProgress(http, jobId) {
  const normalized = normalizeJobId(jobId)
  if (!normalized) throw new Error('任务编号不能为空')
  const encoded = encodeURIComponent(normalized)
  const [detailResponse, logsResponse] = await Promise.all([
    http.get('/system/tasks/' + encoded),
    http.get('/system/tasks/' + encoded + '/logs')
  ])
  return {
    detail: detailResponse.data || {},
    logs: Array.isArray(logsResponse.data) ? logsResponse.data : []
  }
}
