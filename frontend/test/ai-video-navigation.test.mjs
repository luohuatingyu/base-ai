import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigation = await readFile(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')
const initializer = await readFile(new URL('../../backend/src/main/java/com/baseai/platform/service/DataInitializer.java', import.meta.url), 'utf8')
const view = await readFile(new URL('../src/views/AiVideoView.vue', import.meta.url), 'utf8')
const api = await readFile(new URL('../src/api/aiVideo.js', import.meta.url), 'utf8')

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
  assert.match(view, /el-empty v-if/)
})
