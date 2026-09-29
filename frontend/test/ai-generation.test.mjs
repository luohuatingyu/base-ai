import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { generationFailureReasonKey } from '../src/utils/generationFailure.js'

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
  assert.match(source, /poll\(result\.value\.id\)/)
})

/** 每种模式必须声明可接受的素材类型，文生模式不得因残留上传素材改变任务语义。 */
test('AI 生成 page restricts assets by generation mode', () => {
  assert.match(source, /const modeInputPolicy = computed\(\(\) => \{/)
  assert.match(source, /TEXT_TO_VIDEO: \{ kinds: \[\], max: 0 \}/)
  assert.match(source, /TEXT_TO_IMAGE: \{ kinds: \[\], max: 0 \}/)
  assert.match(source, /IMAGE_TO_VIDEO: \{ kinds: \['IMAGE'\], max: 1 \}/)
  assert.match(source, /FIRST_LAST_FRAME_TO_VIDEO: \{ kinds: \['IMAGE'\], max: 2 \}/)
  assert.match(source, /VIDEO_TRANSITION: \{ kinds: \['VIDEO'\], max: 3 \}/)
  assert.match(source, /:disabled="assetsDisabled"/)
  assert.match(source, /function reconcileInputsForMode\(\)/)
  assert.match(source, /if \(!assetsAllowed\.value\) \{ inputs\.value = \[\]/)
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

/** 图片生成模式必须始终可发现，能力为空时也要给出明确状态而不是静默隐藏。 */
test('AI 生成 workspace keeps image mode selection visible', () => {
  assert.match(source, /<el-form-item class="setup-field" :label="t\('generation\.mode'\)">/)
  assert.match(source, /class="mode-select" :disabled="!capability\.modes\?\.length \|\| busy"/)
  assert.match(source, /generation\.modeUnavailable/)
  assert.match(zhLocale, /modeUnavailable: '当前模型未返回可用的生成模式/)
})

/** 图片尺寸使用受控选择，图片和视频输出设置均按媒体类型分组，提交仍复用同一 options 对象。 */
test('AI 生成 workspace uses selectable sizes and grouped output settings', () => {
  assert.match(source, /const imageSizeOptions = computed\(\(\) => capability\.value\.options\?\.size\?\.enum \|\| \['1K', '2K', '4K'\]\)/)
  assert.match(source, /<el-select v-model="options\.size">/)
  assert.match(source, /class="setup-form"/)
  assert.match(source, /class="setup-field" :label="t\('generation\.model'\)"/)
  assert.match(source, /class="setup-field" :label="t\('generation\.mode'\)"/)
  assert.match(source, /class="output-setting-group/)
  assert.match(source, /class="output-setting-group output-setting-group-media"/)
  assert.match(source, /generation\.imageOutputHint/)
  assert.match(source, /generation\.videoOutputHint/)
  assert.match(zhLocale, /mediaOptions: '生成参数'/)
  assert.match(enLocale, /mediaOptions: 'Generation options'/)
  assert.match(source, /output-setting-group-common/)
  assert.match(source, /options: requestOptions\.value/)
})

/** 视频输出规格和开关必须按需求分行，避免返回尾帧与生成声音挤在规格选择中。 */
test('AI 生成 video output groups specs and toggles into separate rows', () => {
  assert.match(source, /class="output-setting-fields video-output-fields"/)
  assert.match(source, /class="video-output-row video-output-specs"[\s\S]*generation\.resolution[\s\S]*generation\.ratio[\s\S]*generation\.duration[\s\S]*<\/div>/)
  assert.match(source, /class="video-output-row video-output-toggles"[\s\S]*generation\.returnLastFrame[\s\S]*generation\.generateAudio[\s\S]*<\/div>/)
  assert.match(source, /\.video-output-specs \{ grid-template-columns: repeat\(3, minmax\(0, 1fr\)\); \}/)
  assert.match(source, /\.video-output-toggles,\n\.video-output-extra \{ grid-template-columns: repeat\(2, minmax\(0, 1fr\)\); \}/)
})

/** 模型、模式和图片/视频输出设置必须使用紧凑网格，避免窄列堆叠出大块空白。 */
test('AI 生成 workspace keeps setup and media options compact', () => {
  assert.match(source, /\.setup-form \{ display: grid; gap: 8px; \}/)
  assert.match(source, /\.setup-field \{ margin-bottom: 0 !important;/)
  assert.match(source, /\.setup-field \{ margin-bottom: 0 !important; padding: 0; \}/)
  assert.match(source, /\.setup-field :deep\(\.el-select__wrapper\) \{ min-height: 38px;/)
  assert.match(source, /\.setup-field :deep\(\.el-select__wrapper:hover\)/)
  assert.match(source, /\.setup-field :deep\(\.el-select__wrapper\.is-focused\)/)
  assert.match(source, /\.output-setting-group \{ display: grid; grid-template-columns: minmax\(126px, \.62fr\) minmax\(0, 1\.38fr\)/)
  assert.match(source, /\.output-setting-fields \{ display: grid; grid-template-columns: repeat\(auto-fit, minmax\(96px, 1fr\)/)
  assert.match(source, /\.output-setting-fields :deep\(\.el-select\),[\s\S]*\.output-setting-fields :deep\(\.el-input-number\)/)
  assert.match(source, /\.output-setting-group \{ grid-template-columns: 1fr; \}/)
})

/** 能力目录的输入数量、时长和模式素材要求必须在当前表单中可见。 */
test('AI 生成 workspace shows model limits and mode requirements', () => {
  assert.match(source, /capabilityHint/)
  assert.match(source, /modeHint/)
  assert.match(source, /generation\.imageLimit/)
  assert.match(source, /generation\.durationLimit/)
  assert.match(zhLocale, /constraints: \{ promptOnly:/)
  assert.match(enLocale, /constraints: \{ promptOnly:/)
})

/** 整页视觉需要保持主次层级、交互反馈和窄屏布局，避免样式回退为无层次的表单堆叠。 */
test('AI 生成 workspace keeps full-page visual hierarchy and responsive states', () => {
  assert.match(source, /\.generation-hero::before/)
  assert.match(source, /\.generation-layout \{ display: grid/)
  assert.match(source, /\.generation-sidebar \{ display: flex/)
  assert.match(source, /\.composer-card \{/)
  assert.match(source, /\.asset-dropzone:focus-within/)
  assert.match(source, /\.generation-history-item:focus-visible/)
  assert.match(source, /\.result-card:hover/)
  assert.match(source, /\.result-empty \{[\s\S]*border: 1px dashed/)
  assert.match(source, /@media \(max-width: 1000px\)/)
  assert.match(source, /@media \(max-width: 780px\)/)
  assert.match(source, /@media \(max-width: 560px\)/)
})

/** 历史失败任务必须直接显示失败原因，未知稳定码保留原值供排查。 */
test('AI 生成 history exposes localized failure reasons', () => {
  assert.equal(generationFailureReasonKey('PROVIDER_FAILED'), 'generation.failureReasons.PROVIDER_FAILED')
  assert.equal(generationFailureReasonKey(' GENERATION_TIMEOUT '), 'generation.failureReasons.GENERATION_TIMEOUT')
  assert.equal(generationFailureReasonKey('NEW_PROVIDER_CODE'), '')
  assert.match(source, /v-if="task\.errorMessage" class="history-item-error"/)
  assert.match(source, /failureReason\(task\.errorMessage\)/)
  assert.match(source, /failureReason\(selectedHistoryTask\.errorMessage\)/)
  assert.match(zhLocale, /failureReasons: \{[^}]*PROVIDER_FAILED: '供应商返回失败'/)
  assert.match(enLocale, /failureReasons: \{[^}]*PROVIDER_FAILED: 'Provider reported a failure'/)
})

/** 生成完成后结果必须弹窗展示，页面正文不得重新插入固定输出预览区。 */
test('AI 生成 submit opens a result dialog instead of an inline preview', () => {
  assert.doesNotMatch(source, /<section v-if="result" class="generation-result">/)
  assert.match(source, /result\.value = \(await http\.post\('\/ai\/generation', payload\)\)\.data\n\s+openResultDialog\(result\.value\)/)
  assert.match(source, /function openResultDialog\(task\) \{ dialogError\.value = ''; dialogMode\.value = 'result'; selectedHistoryTask\.value = task; historyDialogVisible\.value = true \}/)
  assert.match(source, /dialogError\.value = t\('generation\.submitFailed'\)[\s\S]*historyDialogVisible\.value = true/)
  assert.match(source, /v-else-if="dialogError" class="history-dialog-content"/)
  assert.match(source, /@click="poll\(selectedHistoryTask\.id\)"/)
})

/** 工作台布局必须将控制栏、提示词、素材/参数双栏和结果预览分成可识别的区域。 */
test('AI 生成 workspace uses a canvas layout instead of a flat form stack', () => {
  assert.match(source, /class="generation-canvas"/)
  assert.match(source, /class="generation-input-grid"/)
  assert.match(source, /class="prompt-footer"/)
  assert.match(source, /\.generation-canvas \{ display: grid/)
  assert.match(source, /\.generation-input-grid \{ display: grid; grid-template-columns: minmax\(0, 1\.18fr\) minmax\(300px, \.82fr\)/)
  assert.match(source, /\.generation-sidebar \{ position: static/)
  assert.match(source, /\.generation-actions \{ display: flex[\s\S]*border: 1px solid/)
})

/** 历史记录必须在独立弹窗中查看，且不能覆盖当前创作草稿或制造页面内滚动栏。 */
test('AI 生成 history opens a modal without nested page scrolling', () => {
  assert.match(source, /class="generation-history-dialog"/)
  assert.match(source, /v-model="historyDialogVisible"/)
  assert.match(source, /const historyOutputs = computed\(\(\) => selectedHistoryTask\.value\?\.outputJson\?\.outputs \|\| \[\]\)/)
  assert.match(source, /function selectHistory\(task\) \{ dialogError\.value = ''; dialogMode\.value = 'history'; selectedHistoryTask\.value = task; historyDialogVisible\.value = true \}/)
  assert.match(source, /function clearHistorySelection\(\) \{ selectedHistoryTask\.value = null; dialogMode\.value = 'history' \}/)
  assert.doesNotMatch(source, /function selectHistory\(task\) \{ result\.value = task \}/)
  assert.match(source, /\.generation-sidebar \{ position: static/)
  assert.match(source, /:deep\(\.generation-history-dialog \.el-dialog__body\) \{ max-height: none; overflow: visible; \}/)
})
