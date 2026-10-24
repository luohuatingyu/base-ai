const MEDIA_MODES = new Set([
  'TEXT_TO_IMAGE', 'IMAGE_TEXT_TO_IMAGE', 'TEXT_TO_GROUP', 'IMAGE_TO_GROUP',
  'MULTI_IMAGE_FUSION', 'MULTI_IMAGE_TO_GROUP', 'LAYER_DECOMPOSITION',
  'INTERACTIVE_EDIT', 'TRANSPARENT_IMAGE', 'TEXT_TO_VIDEO', 'IMAGE_TO_VIDEO',
  'FIRST_LAST_FRAME_TO_VIDEO', 'REFERENCE_IMAGE_TO_VIDEO', 'VIDEO_TO_VIDEO',
  'IMAGE_VIDEO_AUDIO_TO_VIDEO', 'STORYBOARD_TO_VIDEO', 'ONE_CLICK_VIDEO',
  'VIDEO_TRANSITION', 'EDIT_VIDEO', 'EXTEND_VIDEO', 'DRAFT_VIDEO', 'DRAFT_TO_VIDEO'
])

/** 将 AI 视频入口传来的查询参数收敛为生成页可接受的创作意图。 */
export function generationIntent(query = {}) {
  const type = query.type === 'video_model' ? 'video_model' : query.type === 'image_model' ? 'image_model' : ''
  const mode = MEDIA_MODES.has(String(query.mode || '').toUpperCase()) ? String(query.mode).toUpperCase() : ''
  const projectId = Number(query.projectId)
  const storyboardId = Number(query.storyboardId)
  const scriptId = Number(query.scriptId)
  const result = {
    type,
    mode,
    prompt: typeof query.prompt === 'string' ? query.prompt.slice(0, 100000) : '',
    projectId: Number.isInteger(projectId) && projectId > 0 ? projectId : null,
    storyboardId: Number.isInteger(storyboardId) && storyboardId > 0 ? storyboardId : null,
    source: typeof query.source === 'string' ? query.source.slice(0, 40) : ''
  }
  if (Number.isInteger(scriptId) && scriptId > 0) result.scriptId = scriptId
  return result
}

/** 只有可持久化的 HTTP(S) 地址才能写入视频工作台，避免保存 Base64 或危险协议。 */
export function persistableMediaUrl(value) {
  if (typeof value !== 'string' || value.length > 500 || /[\\\s]/.test(value)) return ''
  try {
    const url = new URL(value)
    return ['http:', 'https:'].includes(url.protocol) ? value : ''
  } catch {
    return ''
  }
}

/** 把生成输出转换为工作台的视频草稿或可复用图片资产。 */
export function projectOutputPayload(output, task, projectId, index = 0, context = {}) {
  const mediaUrl = persistableMediaUrl(output?.ossUrl) || persistableMediaUrl(output?.value)
  if (!mediaUrl || !Number.isInteger(Number(projectId)) || Number(projectId) <= 0) return null
  const video = task?.generationType === 'video_model'
  const name = output?.name || `${video ? 'Generated video' : 'Generated image'} ${index + 1}`
  if (video) {
    const scriptReference = Number.isInteger(Number(context?.scriptId)) && Number(context.scriptId) > 0
      ? `Script #${Number(context.scriptId)}\n` : ''
    return {
      resource: 'videos',
      payload: { projectId: Number(projectId), title: name, version: 1, status: 'DRAFT', mediaUrl, durationMs: 0, description: `${scriptReference}${task?.prompt || ''}` }
    }
  }
  return {
    resource: 'assets',
    assetType: 'SCENE',
    payload: { projectId: Number(projectId), name, description: task?.prompt || '', previewUrl: mediaUrl, resourceUrl: mediaUrl, metadataJson: JSON.stringify({ source: 'AI_GENERATION', taskId: task?.id || null }) }
  }
}
