/** 将输出记录规范为稳定的媒体类型，兼容旧任务缺少 mediaType 的情况。 */
export function outputMediaType(output, fallbackType = 'image_model') {
  const declared = String(output?.mediaType || output?.mimeType || '').toLowerCase()
  if (declared.startsWith('video')) return 'video'
  if (declared.startsWith('image')) return 'image'
  if (String(output?.output_format || '').toLowerCase() === 'mp4') return 'video'
  return fallbackType === 'video_model' && output?.kind !== 'base64' ? 'video' : 'image'
}

/** 根据媒体类型和供应商字段确定下载与 Base64 预览使用的 MIME。 */
export function outputMimeType(output, fallbackType = 'image_model') {
  const declared = String(output?.mimeType || '').toLowerCase()
  if (declared.includes('/')) return declared
  if (outputMediaType(output, fallbackType) === 'video') {
    const format = String(output?.output_format || '').toLowerCase()
    return format === 'webm' ? 'video/webm' : 'video/mp4'
  }
  return 'image/png'
}

/** 返回浏览器可直接渲染的输出地址，补全 Base64 的媒体类型。 */
export function outputSource(output, fallbackType = 'image_model') {
  if (output?.kind === 'base64') return `data:${outputMimeType(output, fallbackType)};base64,${output.value}`
  return output?.value || ''
}

/** 原始供应商地址加载失败时切换到 OSS 备份地址。 */
export function outputFallbackSource(output, fallbackType = 'image_model') {
  if (output?.ossUrl) return output.ossUrl
  return outputSource(output, fallbackType)
}

/** 为历史记录生成安全且可读的下载文件名。 */
export function outputFilename(output, index, taskId, fallbackType = 'image_model') {
  const rawName = String(output?.name || '').trim().replace(/[\\/:*?"<>|]/g, '_')
  if (rawName) return /\.[A-Za-z0-9]{1,5}$/.test(rawName) ? rawName : `${rawName}.${extension(output, fallbackType)}`
  return `generation-${taskId || 'result'}-${Number(index) + 1}.${extension(output, fallbackType)}`
}

/** 依据媒体 MIME 和输出格式生成文件扩展名。 */
function extension(output, fallbackType) {
  const mime = outputMimeType(output, fallbackType)
  return mime === 'image/jpeg' ? 'jpg' : mime === 'image/webp' ? 'webp' : mime === 'video/webm' ? 'webm' : mime.startsWith('video/') ? 'mp4' : 'png'
}
