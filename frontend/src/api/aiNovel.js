import http from './http'

/** 查询当前用户的 AI 小说作品。 */
export function listAiNovels() { return http.get('/ai/novels') }

/** 查询作品详情、章节和写作参考资料。 */
export function getAiNovel(id) { return http.get(`/ai/novels/${id}`) }

/** 创建 AI 小说作品。 */
export function createAiNovel(payload) { return http.post('/ai/novels', payload) }

/** 更新作品元数据或大纲。 */
export function updateAiNovel(id, payload) { return http.put(`/ai/novels/${id}`, payload) }

/** 删除作品。 */
export function deleteAiNovel(id) { return http.delete(`/ai/novels/${id}`) }

/** 新增章节。 */
export function createAiNovelChapter(novelId, payload) { return http.post(`/ai/novels/${novelId}/chapters`, payload) }

/** 更新章节正文。 */
export function updateAiNovelChapter(novelId, chapterId, payload) { return http.put(`/ai/novels/${novelId}/chapters/${chapterId}`, payload) }

/** 删除章节。 */
export function deleteAiNovelChapter(novelId, chapterId) { return http.delete(`/ai/novels/${novelId}/chapters/${chapterId}`) }

/** 调用 AI 执行大纲、写作、续写、改写、润色或摘要。 */
export function runAiNovelWriter(novelId, payload) { return http.post(`/ai/novels/${novelId}/ai/write`, payload) }

/** 新增人物、世界观或文风参考资料。 */
export function createAiNovelReference(novelId, payload) { return http.post(`/ai/novels/${novelId}/references`, payload) }

/** 更新人物、世界观、文风或研究资料。 */
export function updateAiNovelReference(novelId, referenceId, payload) { return http.put(`/ai/novels/${novelId}/references/${referenceId}`, payload) }

/** 删除一条写作参考资料。 */
export function deleteAiNovelReference(novelId, referenceId) { return http.delete(`/ai/novels/${novelId}/references/${referenceId}`) }

/** 从原著章节提取视频改编剧本。 */
export function extractAiNovelToVideo(novelId, payload) { return http.post(`/ai/novels/${novelId}/extract-to-video`, payload) }
