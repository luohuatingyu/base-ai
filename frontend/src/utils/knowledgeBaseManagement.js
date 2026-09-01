export const MAX_UPLOAD_FILES = 20
export const MAX_UPLOAD_BYTES = 10 * 1024 * 1024

const DOCUMENT_ERROR_KEYS = new Map([
  ['knowledge.disabled', 'disabled'],
  ['knowledge.vectorConnectionUnverified', 'vectorConnectionUnverified'],
  ['knowledge.vectorStoreUnsupported', 'vectorStoreUnsupported'],
  ['knowledge.vectorStoreUnavailable', 'vectorStoreUnavailable'],
  ['knowledge.vectorResourceInvalid', 'vectorResourceInvalid'],
  ['knowledge.vectorDimensionInvalid', 'vectorDimensionInvalid'],
  ['knowledge.vectorDimensionMismatch', 'vectorDimensionMismatch'],
  ['knowledge.vectorMetricMismatch', 'vectorMetricMismatch'],
  ['knowledge.vectorWriteFailed', 'vectorWriteFailed'],
  ['knowledge.resourceNameInvalid', 'resourceNameInvalid'],
  ['knowledge.documentEmpty', 'documentEmpty'],
  ['knowledge.documentTooLarge', 'documentTooLarge'],
  ['knowledge.documentInvalid', 'documentInvalid'],
  ['knowledge.documentExists', 'documentExists'],
  ['knowledge.documentNotFound', 'documentNotFound'],
  ['knowledge.indexFailed', 'indexFailed'],
  ['knowledge.embeddingInputInvalid', 'embeddingInputInvalid'],
  ['knowledge.embeddingResponseInvalid', 'embeddingResponseInvalid']
])

/** 创建有数量与大小边界的稳定上传队列。 */
export function createUploadQueue(files, maximumFiles = MAX_UPLOAD_FILES, maximumBytes = MAX_UPLOAD_BYTES) {
  return Array.from(files || []).map((file, index) => {
    const rejection = index >= maximumFiles ? 'TOO_MANY' : file.size <= 0 ? 'EMPTY' : file.size > maximumBytes ? 'TOO_LARGE' : ''
    return { id: `${index}-${file.name}-${file.size}-${file.lastModified || 0}`, file, name: file.name, size: file.size,
      status: rejection ? 'REJECTED' : 'PENDING', rejection, error: '' }
  })
}

/** 顺序执行待处理或失败队列，确保单文件失败不会中止整批。 */
export async function runUploadQueue(queue, uploader, onChange = () => {}) {
  for (const item of queue) {
    if (!['PENDING', 'FAILED'].includes(item.status) || item.rejection) continue
    item.status = 'UPLOADING'; item.error = ''; onChange([...queue])
    try { await uploader(item); item.status = 'SUCCESS' }
    catch (error) { item.status = 'FAILED'; item.error = error }
    onChange([...queue])
  }
  return uploadQueueSummary(queue)
}

/** 汇总上传队列状态，供进度与结果文案共用。 */
export function uploadQueueSummary(queue) {
  const summary = { total: queue.length, pending: 0, uploading: 0, success: 0, failed: 0, rejected: 0, completed: 0, percentage: 0 }
  for (const item of queue) {
    const key = String(item.status || 'PENDING').toLowerCase()
    if (Object.hasOwn(summary, key)) summary[key] += 1
  }
  summary.completed = summary.success + summary.failed + summary.rejected
  summary.percentage = summary.total ? Math.round(summary.completed * 100 / summary.total) : 0
  return summary
}

/** 返回文档索引状态对应的标签类型。 */
export function documentStatusType(status) {
  return status === 'READY' ? 'success' : status === 'FAILED' ? 'danger' : 'warning'
}

/** 返回上传队列状态对应的标签类型。 */
export function uploadStatusType(status) {
  return status === 'SUCCESS' ? 'success' : ['FAILED', 'REJECTED'].includes(status) ? 'danger' : status === 'UPLOADING' ? 'warning' : 'info'
}

/** 将持久化的后端错误键映射为受控前端文案，未知内容不直接展示。 */
export function documentErrorTranslationKey(messageKey) {
  return `knowledgeBases.documentErrors.${DOCUMENT_ERROR_KEYS.get(String(messageKey || '')) || 'unknown'}`
}

/** 使用紧凑单位展示文件大小。 */
export function formatFileSize(bytes) {
  if (!Number.isFinite(bytes) || bytes < 0) return '-'
  if (bytes < 1024) return `${bytes} B`
  if (bytes < 1024 * 1024) return `${(bytes / 1024).toFixed(1)} KB`
  return `${(bytes / 1024 / 1024).toFixed(1)} MB`
}
