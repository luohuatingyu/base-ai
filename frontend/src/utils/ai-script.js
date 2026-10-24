const MAX_SCRIPT_TEXT = 100000

/** 将文本限制在页面和后端允许的范围内，避免模型返回超大内容拖慢编辑器。 */
function text(value, fallback = '') {
  const normalized = typeof value === 'string' ? value.trim() : ''
  return (normalized || fallback).slice(0, MAX_SCRIPT_TEXT)
}

/** 将模型返回的场景时长收敛为工作台接受的毫秒整数。 */
function duration(value) {
  const parsed = Number(value)
  return Number.isFinite(parsed) ? Math.min(3600000, Math.max(100, Math.round(parsed))) : 5000
}

/** 规范化单个场景，保证保存分镜时字段完整且顺序稳定。 */
function normalizeScene(scene, index) {
  const value = scene && typeof scene === 'object' ? scene : {}
  return {
    title: text(value.title, `场景 ${index + 1}`).slice(0, 200),
    sceneDescription: text(value.sceneDescription || value.description).slice(0, 2000),
    dialogue: text(value.dialogue).slice(0, 5000),
    narration: text(value.narration).slice(0, 5000),
    camera: text(value.camera, '中景').slice(0, 120),
    durationMs: duration(value.durationMs)
  }
}

/** 从普通文本、代码围栏或前后带说明的回答中提取 JSON 对象。 */
function parseJsonObject(content) {
  const source = String(content || '').trim()
  const fenced = source.match(/```(?:json)?\s*([\s\S]*?)```/i)
  const candidate = fenced?.[1]?.trim() || source
  const start = candidate.indexOf('{')
  const end = candidate.lastIndexOf('}')
  if (start < 0 || end <= start) return null
  try { return JSON.parse(candidate.slice(start, end + 1)) } catch { return null }
}

/** 规范化 AI 剧本响应；解析失败时保留原文，避免用户丢失模型产出。 */
export function parseScriptResponse(content) {
  const parsed = parseJsonObject(content)
  const source = parsed && typeof parsed === 'object' ? parsed : {}
  const scenes = Array.isArray(source.scenes) ? source.scenes : []
  const fallback = text(content, '暂无剧本内容')
  return {
    title: text(source.title, '未命名剧本').slice(0, 200),
    logline: text(source.logline).slice(0, 1000),
    synopsis: text(source.synopsis, fallback).slice(0, 10000),
    scenes: (scenes.length ? scenes : [{ title: '完整故事', sceneDescription: fallback, durationMs: 5000 }])
      .slice(0, 100).map(normalizeScene)
  }
}

/** 构造发给后端的创作约束，明确要求模型只生成可解析的剧本 JSON。 */
export function buildScriptRequest(form) {
  return {
    brief: text(form?.brief),
    genre: text(form?.genre),
    tone: text(form?.tone),
    language: text(form?.language, '简体中文'),
    durationSeconds: Math.min(3600, Math.max(1, Number(form?.durationSeconds) || 60)),
    sceneCount: Math.min(24, Math.max(1, Number(form?.sceneCount) || 6))
  }
}

/** 将结构化剧本转换为可读正文，供现有剧本管理页面继续编辑。 */
export function scriptContent(script) {
  const scenes = (script?.scenes || []).map((scene, index) => [
    `## ${index + 1}. ${scene.title}`,
    `画面：${scene.sceneDescription || '暂无'}`,
    `对白：${scene.dialogue || '无'}`,
    `旁白：${scene.narration || '无'}`,
    `镜头：${scene.camera || '中景'}`,
    `时长：${duration(scene.durationMs)}ms`
  ].join('\n')).join('\n\n')
  return [`# ${text(script?.title, '未命名剧本')}`, `一句话梗概：${text(script?.logline, '暂无')}`, `故事梗概：${text(script?.synopsis, '暂无')}`, scenes].join('\n\n').slice(0, MAX_SCRIPT_TEXT)
}

/** 生成写入 AI 视频分镜表的请求，使用同一剧本 ID 保持上下文关联。 */
export function storyboardPayloads(script, projectId, scriptId) {
  const project = Number(projectId)
  const scriptValue = Number(scriptId)
  if (!Number.isInteger(project) || project <= 0 || !Number.isInteger(scriptValue) || scriptValue <= 0) return []
  return (script?.scenes || []).map((scene, index) => ({
    projectId: project,
    scriptId: scriptValue,
    shotNo: index + 1,
    title: text(scene.title, `场景 ${index + 1}`).slice(0, 200),
    sceneDescription: text(scene.sceneDescription).slice(0, 2000),
    dialogue: text(scene.dialogue).slice(0, 5000),
    narration: text(scene.narration).slice(0, 5000),
    camera: text(scene.camera, '中景').slice(0, 120),
    durationMs: duration(scene.durationMs),
    status: 'READY'
  }))
}

/** 将剧本摘要压缩成视频模型可直接使用的提示词，并保留镜头顺序。 */
export function scriptVideoPrompt(script) {
  const shots = (script?.scenes || []).map((scene, index) => `${index + 1}. ${scene.title}：${scene.sceneDescription}；${scene.camera}；${scene.dialogue || ''}；${scene.narration || ''}`).join('\n')
  return [`剧本：${text(script?.title, '未命名剧本')}`, `梗概：${text(script?.synopsis, '')}`, '按以下镜头顺序生成连贯视频：', shots].join('\n').slice(0, MAX_SCRIPT_TEXT)
}
