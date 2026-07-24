/** 判断文件扩展名是否为受支持的提示词文本格式。 */
export function isPromptFile(file) {
  const fileName = String(file?.name || '').toLowerCase()
  return fileName.endsWith('.txt') || fileName.endsWith('.md')
}

/** 以 UTF-8 文本方式读取用户选择的提示词文件。 */
export async function readPromptFile(file) {
  const content = await file.text()
  if (!content.trim()) throw new Error('Prompt file is empty')
  return content
}

/** 将非空系统提示词插入对话消息前，保持原有消息顺序不变。 */
export function withSystemPrompt(messages, systemPrompt) {
  return systemPrompt.trim()
    ? [{ role: 'system', content: systemPrompt }, ...messages]
    : messages
}
