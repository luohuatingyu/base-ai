import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigation = await readFile(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')
const initializer = await readFile(new URL('../../backend/src/main/java/com/baseai/platform/service/DataInitializer.java', import.meta.url), 'utf8')
const view = await readFile(new URL('../src/views/AiVideoView.vue', import.meta.url), 'utf8')
const api = await readFile(new URL('../src/api/aiVideo.js', import.meta.url), 'utf8')
const zhLocale = await readFile(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enLocale = await readFile(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')

const permissions = [
  'ai:video:dashboard:list', 'ai:video:project:list', 'ai:video:script:list', 'ai:video:storyboard:list',
  'ai:video:character:list', 'ai:video:scene:list', 'ai:video:item:list', 'ai:video:costume:list',
  'ai:video:motion:list', 'ai:video:style:list', 'ai:video:voice:list', 'ai:video:sound:list',
  'ai:video:task:list', 'ai:video:render:list', 'ai:video:video:list', 'ai:video:review:list',
  'ai:video:publish:list', 'ai:video:analytics:list'
]

test('AI视频菜单使用独立业务域和四级业务分组', () => {
  assert.match(initializer, /menu\(null, "AI视频", "CATALOG", "\/ai-video"/)
  for (const name of ['内容创作', '资产中心', '生成中心', '发布中心']) assert.match(initializer, new RegExp(`"${name}"`))
  assert.match(initializer, /seedCrud\(assets, "物品资产"/)
  assert.doesNotMatch(initializer, /AI视频工作台/)
})

test('AI视频页面路由和权限编码一一对应', () => {
  for (const permission of permissions) {
    assert.match(router, new RegExp(`permission: '${permission}'`), permission)
    assert.match(navigation, new RegExp(`'${permission}':`), permission)
  }
  assert.match(router, /import AiVideoView from '..\/views\/AiVideoView\.vue'/)
  assert.match(router, /path: 'ai-video\/assets\/items'/)
})

test('AI视频页面按路由展示模块说明并覆盖物品资产', () => {
  assert.match(view, /route\.path\.split\('\/'\)/)
  assert.match(view, /items: 'items'/)
  assert.match(view, /aiVideo\.currentModuleHint/)
})

test('AI视频工作台接入真实资源和任务操作', () => {
  for (const endpoint of ['listAiVideoResource', 'createAiVideoResource', 'updateAiVideoResource', 'deleteAiVideoResource']) {
    assert.match(api, new RegExp(`export function ${endpoint}`), endpoint)
  }
  for (const action of ['cancelAiVideoTask', 'retryAiVideoTask', 'reviewAiVideo', 'completeAiVideoPublish']) {
    assert.match(api, new RegExp(`export function ${action}`), action)
  }
  assert.match(view, /await createAiVideoResource/)
  assert.match(view, /await cancelAiVideoTask/)
  assert.match(view, /await retryAiVideoTask/)
  assert.match(view, /await completeAiVideoPublish/)
  assert.match(view, /<template #empty>/)
  assert.match(view, /productionPaths/)
  assert.match(view, /route\.query\.mode/)
  assert.match(view, /openCreate\(mode\)/)
})

test('AI视频页面避免请求卡住导致加载状态无法结束', () => {
  assert.match(view, /const loadTimeoutMs = 12000/)
  assert.match(view, /withLoadTimeout\(listAiVideoResource\(currentResource, currentParams\)/)
  assert.match(view, /watch\(\(\) => route\.fullPath/)
})

test('AI视频生成任务使用任务看板展示状态和进度', () => {
  assert.match(view, /isTaskModule/)
  assert.match(view, /taskMetrics/)
  assert.match(view, /taskProgress\(row\)/)
  assert.match(view, /cancelTask\(row\)/)
  assert.match(view, /retryTask\(row\)/)
  assert.match(view, /taskTypeLabel\(row\.taskType\)/)
})

test('AI视频首页提供可执行的详细制作向导', () => {
  for (const section of ['guide-preparation', 'guide-progress-card', 'guide-delivery', 'ai-video-step-details', 'ai-video-step-checks']) {
    assert.match(view, new RegExp(section), section)
  }
  for (const binding of ['guideStepStatus', 'guideCompletedCount', 'guideProgress', 'guidePreparation', 'guideDelivery']) {
    assert.match(view, new RegExp(binding), binding)
  }
  for (const key of ['goal', 'prepare', 'action', 'output', 'checks', 'cta', 'status']) assert.match(view, new RegExp(`step[.]${key}`), key)
  for (const key of ['preparationTitle', 'flowTitle', 'prepareLabel', 'actionLabel', 'outputLabel', 'checklistLabel', 'deliveryTitle']) {
    assert.match(view, new RegExp(`aiVideo[.]guide[.]${key}`), key)
  }
  assert.match(view, /path\.next/)
})

test('AI视频首页详细向导同时维护中英文内容', () => {
  for (const locale of [zhLocale, enLocale]) {
    assert.match(locale, /guide: \{ progressLabel:/)
    for (const key of ['projectGoal', 'contentAction', 'assetOutput', 'generateCheckThree', 'publishCta']) {
      assert.match(locale, new RegExp(`${key}:`), key)
    }
    for (const key of ['briefDescription', 'modelDescription', 'materialsDescription', 'deliveryDescription']) {
      assert.match(locale, new RegExp(`${key}:`), key)
    }
  }
})

test('项目创建弹窗提供专属说明和后续操作引导', () => {
  assert.match(view, /isProjectCreate/)
  assert.match(view, /project-create-guide/)
  assert.match(view, /projectCreateGuide/)
  for (const key of ['identityTitle', 'outputDescription', 'directionFields', 'nextDescription', 'codeHint', 'aspectRatioHint', 'statusHint']) {
    for (const locale of [zhLocale, enLocale]) assert.match(locale, new RegExp(`["']?${key}["']?\\s*:`), key)
  }
})

test('AI视频首页主按钮在深色横幅上保持可读性', () => {
  assert.match(view, /class="ai-video-start-button"/)
  assert.match(view, /\.ai-video-start-button[^}]*background:\s*#fff/)
  assert.match(view, /\.ai-video-start-button[^}]*color:\s*#24447d/)
  assert.match(view, /\.ai-video-start-button:hover[^}]*background:\s*#edf3ff/)
  assert.match(view, /\.ai-video-start-button:focus-visible[^}]*outline/)
})

test('资产说明与新建向导绑定分类数据，窄屏按单列展示', () => {
  assert.match(view, /v-if="assetType" class="asset-guide"/)
  assert.match(view, /v-if="showCreationGuide" class="creation-guide"/)
  for (const binding of ['creationGuide.purpose', 'creationGuide.steps', 'creationGuide.example', 'step.description']) {
    assert.ok(view.includes(binding), binding)
  }
  assert.match(view, /@media \(max-width: 760px\) \{ \.creation-guide-grid \{ grid-template-columns: 1fr;/)
  assert.doesNotMatch(view, /v-html=/)
})
