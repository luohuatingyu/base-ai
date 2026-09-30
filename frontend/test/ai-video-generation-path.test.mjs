import assert from 'node:assert/strict'
import test from 'node:test'
import { readFile } from 'node:fs/promises'
import { generationIntent, persistableMediaUrl, projectOutputPayload } from '../src/utils/generationWorkflow.js'

test('AI 视频入口只接受白名单模式并保留有效项目上下文', () => {
  assert.deepEqual(generationIntent({ type: 'video_model', mode: 'IMAGE_TO_VIDEO', projectId: '12', storyboardId: '9', source: 'asset' }), {
    type: 'video_model', mode: 'IMAGE_TO_VIDEO', prompt: '', projectId: 12, storyboardId: 9, source: 'asset'
  })
  assert.equal(generationIntent({ type: 'video_model', mode: 'DROP_TABLE', projectId: '-1' }).mode, '')
  assert.equal(generationIntent({ type: 'other', mode: 'TEXT_TO_VIDEO' }).type, '')
})

test('项目回流拒绝 Base64、危险协议和过长地址', () => {
  assert.equal(persistableMediaUrl('data:video/mp4;base64,AAAA'), '')
  assert.equal(persistableMediaUrl('javascript:alert(1)'), '')
  assert.equal(persistableMediaUrl(`https://cdn.example.com/${'a'.repeat(501)}`), '')
  assert.equal(persistableMediaUrl('https://cdn.example.com/video.mp4'), 'https://cdn.example.com/video.mp4')
})

test('视频输出保存为成片草稿，图片输出保存为场景资产', () => {
  const task = { id: 7, generationType: 'video_model', prompt: '雨夜街道' }
  assert.deepEqual(projectOutputPayload({ value: 'https://cdn.example.com/video.mp4' }, task, 3, 0), {
    resource: 'videos',
    payload: { projectId: 3, title: 'Generated video 1', version: 1, status: 'DRAFT', mediaUrl: 'https://cdn.example.com/video.mp4', durationMs: 0, description: '雨夜街道' }
  })
  assert.equal(projectOutputPayload({ value: 'https://cdn.example.com/image.png' }, { id: 8, generationType: 'image_model', prompt: '古城' }, 3, 1).assetType, 'SCENE')
  assert.equal(projectOutputPayload({ value: 'https://cdn.example.com/video.mp4' }, task, null), null)
})


test('AI 视频快捷路径进入媒体生成页并提供项目回流动作', async () => {
  const videoView = await readFile(new URL('../src/views/AiVideoView.vue', import.meta.url), 'utf8')
  const generationView = await readFile(new URL('../src/views/AiGenerationView.vue', import.meta.url), 'utf8')
  assert.match(videoView, /route: 'ai-generation'.*generationMode: 'TEXT_TO_VIDEO'/)
  assert.match(videoView, /generationMode: 'REFERENCE_IMAGE_TO_VIDEO'/)
  assert.match(generationView, /generationIntent\(route\.query\)/)
  assert.match(generationView, /createAiVideoResource\(payload\.resource/)
})
