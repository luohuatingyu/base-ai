import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/AiGenerationView.vue', import.meta.url), 'utf8')
const zhLocale = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const router = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigation = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')

/** 独立页面必须使用能力目录、素材角色和异步任务接口，不能回退到聊天页面隐藏开关。 */
test('AI 生成页面 exposes independent model-aware workspaces', () => {
  assert.match(router, /ai-generation.*AiGenerationView/)
  assert.match(navigation, /ai:generation:invoke.*aiGeneration/)
  assert.match(source, /\/ai\/generation\/models/)
  assert.match(source, /\/ai\/generation'/)
  assert.match(source, /capability\.modes/)
  assert.match(source, /modeLabel/)
  assert.match(source, /reference_video/)
})

/** 图片组图、图层和视频首尾帧参数必须从页面请求中保留。 */
test('AI 生成 page keeps image and video generation controls', () => {
  assert.match(source, /sequential_image_generation_options/)
  assert.match(source, /layer_decomposition/)
  assert.match(source, /first_frame/)
  assert.match(source, /last_frame/)
  assert.match(source, /generate_audio/)
  assert.match(source, /poll\(result\.id\)/)
})

/** 所有已声明的视频任务必须在前端生成与方舟一致的锁定参数。 */
test('AI 生成 page handles Ark locked and extended video modes', () => {
  assert.match(zhLocale, /STORYBOARD_TO_VIDEO/)
  assert.match(zhLocale, /ONE_CLICK_VIDEO/)
  assert.match(zhLocale, /VIDEO_TRANSITION/)
  assert.match(source, /if \(draftToVideo\.value\) return \{ watermark: Boolean\(options\.watermark\) \}/)
  assert.match(source, /if \(editVideo\.value\) result\.duration = -1/)
  assert.match(source, /options\.resolution = '480p'/)
  assert.match(source, /delete result\.sequential_image_generation_options/)
  assert.match(source, /generation\.editLockedDuration/)
})
