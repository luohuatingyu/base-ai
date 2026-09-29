import assert from 'node:assert/strict'
import test from 'node:test'
import { outputFilename, outputMediaType, outputMimeType, outputSource } from '../src/utils/generationOutput.js'

/** 历史输出缺少 mediaType 时按任务类型恢复正确的图片或视频展示。 */
test('generation output infers media type from task fallback', () => {
  assert.equal(outputMediaType({ kind: 'url', value: 'https://cdn.example/a' }, 'video_model'), 'video')
  assert.equal(outputMediaType({ kind: 'base64', value: 'abc' }, 'video_model'), 'image')
  assert.equal(outputMediaType({ mediaType: 'video' }, 'image_model'), 'video')
})

/** Base64 结果必须带正确 MIME，不能把所有输出固定标成 PNG。 */
test('generation output preserves MIME for preview and download', () => {
  const output = { kind: 'base64', value: 'YWJj', mimeType: 'image/jpeg' }
  assert.equal(outputMimeType(output), 'image/jpeg')
  assert.equal(outputSource(output), 'data:image/jpeg;base64,YWJj')
  assert.equal(outputFilename(output, 0, 12), 'generation-12-1.jpg')
})

/** 输出名称需清理路径字符，缺少名称时生成稳定可读的下载名。 */
test('generation output creates safe download names', () => {
  assert.equal(outputFilename({ name: '../cover image' }, 1, 3), '.._cover image.png')
  assert.equal(outputFilename({ name: 'clip.webm', mediaType: 'video' }, 0, 3, 'video_model'), 'clip.webm')
})
