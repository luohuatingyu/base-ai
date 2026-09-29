import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/AiGenerationView.vue', import.meta.url), 'utf8')
const zhLocale = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enLocale = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')
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

/** 仅配置视频模型时页面必须自动进入可用类型，并为完全空目录提供管理入口和使用说明。 */
test('AI 生成 page guides setup and selects an available type', () => {
  assert.match(source, /firstAvailableType = \['image_model', 'video_model'\]/)
  assert.match(source, /models\.value\.some\(item => item\.modelType === value\)/)
  assert.match(source, /goModelManagement\(\)/)
  assert.match(source, /generation\.guideTitle/)
  assert.match(source, /generation\.openModels/)
  assert.match(zhLocale, /guideStepModel/)
  assert.match(zhLocale, /guideStepAssets/)
})

/** 切换模型和清空表单必须重置只对当前能力有效的开关。 */
test('AI 生成 page clears capability-specific toggles when selection changes', () => {
  assert.match(source, /groupOutput\.value = false/)
  assert.match(source, /transparent\.value = false/)
  assert.match(source, /function reset\(\).*groupOutput\.value = false.*transparent\.value = false/s)
})

/** 创作台必须保留图片和视频的清晰分区，并提供统一的三步流程提示。 */
test('AI 生成 workspace presents media modes and workflow hierarchy', () => {
  assert.match(source, /generation-type-switch/)
  assert.match(source, /generation-workflow/)
  assert.match(source, /workflowPrompt/)
  assert.match(source, /workflowAssets/)
  assert.match(source, /workflowResult/)
  assert.match(zhLocale, /kicker: '创作工作台'/)
  assert.match(enLocale, /kicker: 'Creation workspace'/)
})

/** 输入区和结果区必须具备可发现的拖拽入口、媒体卡片和异步状态提示。 */
test('AI 生成 workspace exposes asset dropzone and result states', () => {
  assert.match(source, /asset-dropzone/)
  assert.match(source, /@drop\.prevent="onDrop"/)
  assert.match(source, /result-card/)
  assert.match(source, /resultWaiting/)
  assert.match(source, /function statusType\(status\)/)
  assert.match(source, /function outputSource\(output\)/)
  assert.match(zhLocale, /dropAssets: '拖拽素材到这里'/)
  assert.match(enLocale, /dropAssets: 'Drop assets here'/)
})
