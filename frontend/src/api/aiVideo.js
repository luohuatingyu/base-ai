import http from './http'

/** 查询 AI 视频工作台统计。 */
export function getAiVideoOverview() {
  return http.get('/ai-video/overview')
}

/** 查询指定业务资源，资产分类通过查询参数隔离。 */
export function listAiVideoResource(resource, params = {}) {
  return http.get(`/ai-video/${resource}`, { params })
}

/** 创建 AI 视频业务资源。 */
export function createAiVideoResource(resource, payload, assetType) {
  return http.post(`/ai-video/${resource}`, payload, { params: assetType ? { assetType } : {} })
}

/** 更新 AI 视频业务资源。 */
export function updateAiVideoResource(resource, id, payload) {
  return http.put(`/ai-video/${resource}/${id}`, payload)
}

/** 删除 AI 视频业务资源。 */
export function deleteAiVideoResource(resource, id) {
  return http.delete(`/ai-video/${resource}/${id}`)
}

/** 取消生成任务。 */
export function cancelAiVideoTask(id) {
  return http.post(`/ai-video/tasks/${id}/cancel`)
}

/** 重试生成任务。 */
export function retryAiVideoTask(id) {
  return http.post(`/ai-video/tasks/${id}/retry`)
}

/** 提交成片审核结果。 */
export function reviewAiVideo(videoId, payload) {
  return http.post(`/ai-video/videos/${videoId}/review`, payload)
}

/** 标记发布记录已完成。 */
export function completeAiVideoPublish(id) {
  return http.post(`/ai-video/publishes/${id}/complete`)
}
