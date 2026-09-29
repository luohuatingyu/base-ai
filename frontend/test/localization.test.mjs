import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

test('图片与视频生成模型分别显示中英文标签，视觉理解标签保持兼容', () => {
  for (const [type, chinese, english] of [
    ['image_model', '图片生成模型', 'Image Generation Model'],
    ['video_model', '视频生成模型', 'Video Generation Model'],
    ['vision_model', '视觉模型', 'Vision Model']
  ]) {
    assert.equal(localizeModelType(type, [], zh), chinese)
    assert.equal(localizeModelType(type, [], en), english)
  }
})
import {
  localizeDepartmentName,
  localizeDictionaryDataLabel,
  localizeDictionaryTypeName,
  localizeAuthenticatedUserDisplayName,
  localizeLoginMessage,
  localizeModelType,
  localizeRoleName,
  localizeRouteName,
  localizeTaskType,
  localizeUserDisplayName
} from '../src/utils/localization.js'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const viewSources = Object.fromEntries(['UsersView', 'RolesView', 'MenusView', 'DepartmentsView', 'LoginLogsView', 'TasksView', 'ModelsView', 'ModelRoutesView', 'DictionariesView', 'AiChatView']
  .map(name => [name, readFileSync(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8')]))
const adminLayoutSource = readFileSync(new URL('../src/views/AdminLayout.vue', import.meta.url), 'utf8')

/** 使用真实语言资源创建测试翻译器，缺失词条时与 vue-i18n 一样回退 key。 */
function translator(messages) {
  return key => key.split('.').reduce((value, segment) => value?.[segment], messages) || key
}

const en = translator(enUS)
const zh = translator(zhCN)

test('角色名仅按稳定 code 映射，未知角色回退原值', () => {
  assert.equal(localizeRoleName({ code: 'ADMIN', name: '系统管理员' }, en), 'System Administrator')
  assert.equal(localizeRoleName({ code: 'ADMIN', name: 'Custom Admin' }, zh), '系统管理员')
  assert.equal(localizeRoleName({ code: 'CUSTOM', name: '系统管理员' }, en), '系统管理员')
  assert.equal(localizeRoleName({ code: 'CUSTOM', name: '自定义角色' }, en), '自定义角色')
})

test('部门名仅按稳定 code 映射，未知部门回退原值', () => {
  assert.equal(localizeDepartmentName({ code: 'ROOT', name: 'AI平台' }, en), 'AI Platform')
  assert.equal(localizeDepartmentName({ code: 'ROOT', name: 'Changed Root' }, zh), 'AI平台')
  assert.equal(localizeDepartmentName({ code: 'DEPT_001', name: 'AI平台' }, en), 'AI平台')
  assert.equal(localizeDepartmentName({ code: 'DEPT_001', name: '研发部' }, en), '研发部')
})

test('用户显示名同时校验 ADMIN 角色和默认名称，避免同名误翻译', () => {
  const roles = [{ id: 1, code: 'ADMIN' }, { id: 2, code: 'USER' }]
  assert.equal(localizeUserDisplayName({ username: 'admin', displayName: '系统管理员', roleIds: [1] }, roles, en), 'System Administrator')
  assert.equal(localizeUserDisplayName({ username: 'admin', displayName: 'Custom Name', roleIds: [1] }, roles, en), 'Custom Name')
  assert.equal(localizeUserDisplayName({ username: 'alice', displayName: '系统管理员', roleIds: [2] }, roles, en), '系统管理员')
  assert.equal(localizeUserDisplayName({ username: 'alice', displayName: null, roleIds: [] }, roles, en), 'alice')
})

test('顶部栏仅本地化仍使用默认名称的当前管理员', () => {
  assert.equal(localizeAuthenticatedUserDisplayName({ username: 'admin', displayName: '系统管理员', roles: ['ADMIN'] }, en), 'System Administrator')
  assert.equal(localizeAuthenticatedUserDisplayName({ username: 'admin', displayName: 'System Administrator', roles: ['ADMIN'] }, zh), '系统管理员')
  assert.equal(localizeAuthenticatedUserDisplayName({ username: 'admin', displayName: 'Platform Owner', roles: ['ADMIN'] }, zh), 'Platform Owner')
  assert.equal(localizeAuthenticatedUserDisplayName({ username: 'alice', displayName: '系统管理员', roles: ['USER'] }, en), '系统管理员')
  assert.equal(localizeAuthenticatedUserDisplayName({ username: 'alice', displayName: null, roles: [] }, en), 'alice')
  assert.match(adminLayoutSource, /localizeAuthenticatedUserDisplayName\(auth\.user,\s*t\)/)
})

test('路由名仅按稳定 featureCode 映射，未知路由回退原值', () => {
  assert.equal(localizeRouteName({ featureCode: 'DEFAULT', name: '默认能力路由' }, en), 'Default Route')
  assert.equal(localizeRouteName({ featureCode: 'DEFAULT', name: 'Changed Default' }, zh), '默认能力路由')
  assert.equal(localizeRouteName({ featureCode: 'chat', name: '默认能力路由' }, en), '默认能力路由')
  assert.equal(localizeRouteName({ featureCode: 'chat', name: '聊天路由' }, en), '聊天路由')
})

test('登录消息兼容稳定 key 和历史中文值，未知消息回退原值', () => {
  assert.equal(localizeLoginMessage('auth.loginSuccess', en), 'Login successful')
  assert.equal(localizeLoginMessage('auth.accountDisabled', en), 'The account is disabled')
  assert.equal(localizeLoginMessage('登录成功', en), 'Login successful')
  assert.equal(localizeLoginMessage('用户名或密码错误', en), 'Incorrect username or password')
  assert.equal(localizeLoginMessage('账号或密码错误', zh), '账号或密码错误')
  assert.equal(localizeLoginMessage('账号已停用', en), 'The account is disabled')
  assert.equal(localizeLoginMessage('登录用户不存在', en), 'The authenticated user does not exist')
  assert.equal(localizeLoginMessage('auth.unknown', en), 'auth.unknown')
  assert.equal(localizeLoginMessage('未知错误', en), '未知错误')
  assert.equal(localizeLoginMessage(null, en), '')
})

test('任务类型兼容稳定代码、旧 key 和历史中文值，未知值保持原样', () => {
  assert.equal(localizeTaskType('AI_CHAT', en), 'AI Chat')
  assert.equal(localizeTaskType('tasks.types.aiChat', zh), 'AI 对话')
  assert.equal(localizeTaskType('AI 对话', en), 'AI Chat')
  assert.equal(localizeTaskType('更新接口触发安全配置', en), 'Update Trigger Security')
  assert.equal(localizeTaskType('CustomController.run', en), 'CustomController.run')
  assert.equal(localizeTaskType(null, en), '')
})

test('模型类型按稳定 value 翻译，自定义类型回退字典 label', () => {
  const options = [{ value: 'text_model', label: '文本模型' }, { value: 'audio_model', label: '音频模型' }]
  assert.equal(localizeModelType('text_model', options, en), 'Text Model')
  assert.equal(localizeModelType('vision_model', options, zh), '视觉模型')
  assert.equal(localizeModelType('audio_model', options, en), '音频模型')
  assert.equal(localizeModelType('unknown_model', options, en), 'unknown_model')
  assert.equal(localizeModelType(null, options, en), '')
})

test('字典页面按稳定 code 和 value 翻译内置名称及标签', () => {
  assert.equal(localizeDictionaryTypeName({ code: 'llm_model_type', name: '模型类型' }, en), 'Model Type')
  assert.equal(localizeDictionaryTypeName({ code: 'llm_model_type', name: 'Custom Name' }, zh), '模型类型')
  assert.equal(localizeDictionaryDataLabel({ typeCode: 'llm_model_type', dictValue: 'text_model', label: '文本模型' }, en), 'Text Model')
  assert.equal(localizeDictionaryDataLabel({ typeCode: 'llm_model_type', dictValue: 'vision_model', label: 'Vision' }, zh), '视觉模型')
})

test('自定义字典名称和标签保持管理员维护的原值', () => {
  assert.equal(localizeDictionaryTypeName({ code: 'custom_type', name: '自定义类型' }, en), '自定义类型')
  assert.equal(localizeDictionaryDataLabel({ typeCode: 'custom_type', dictValue: 'custom', label: '自定义标签' }, en), '自定义标签')
  assert.equal(localizeDictionaryDataLabel({ typeCode: 'llm_model_type', dictValue: 'audio_model', label: '音频模型' }, en), '音频模型')
})

test('十个问题页面全部接入共享本地化解析器', () => {
  assert.match(viewSources.UsersView, /localizeUserDisplayName\(s\.row, roles, t\)/)
  assert.match(viewSources.UsersView, /:label="localizeRoleName\(role, t\)"/)
  assert.match(viewSources.UsersView, /:label="localizeDepartmentName\(item, t\)"/)
  assert.match(viewSources.RolesView, /localizeRoleName\(s\.row, t\)/)
  assert.match(viewSources.RolesView, /localizeMenuName\(menu, t\)/)
  assert.match(viewSources.MenusView, /localizedName=row=>localizeMenuName\(row,t\)/)
  assert.match(viewSources.DepartmentsView, /localizeDepartmentName\(s\.row, t\)/)
  assert.match(viewSources.LoginLogsView, /localizeLoginMessage\(s\.row\.message, t\)/)
  assert.match(viewSources.TasksView, /:label="localizeTaskType\(item, t\)" :value="item"/)
  assert.match(viewSources.TasksView, /localizeTaskType\(scope\.row\.task_type, t\)/)
  assert.match(viewSources.ModelsView, /localizeModelType\(type, modelTypes, t\)/)
  assert.match(viewSources.ModelsView, /localizeModelType\(type\.value, modelTypes, t\)/)
  assert.match(viewSources.ModelRoutesView, /localizeRouteName\(scope\.row, t\)/)
  assert.match(viewSources.ModelRoutesView, /localizeRouteName\(route, t\)/)
  assert.match(viewSources.DictionariesView, /localizeDictionaryTypeName\(s\.row,t\)/)
  assert.match(viewSources.DictionariesView, /current\?localizeDictionaryTypeName\(current,t\):t\('dictionaries\.selectType'\)/)
  assert.match(viewSources.DictionariesView, /localizeDictionaryDataLabel\(s\.row,t\)/)
  assert.match(viewSources.DictionariesView, /name:localizeDictionaryTypeName\(row,t\)/)
  assert.match(viewSources.DictionariesView, /name:localizeDictionaryDataLabel\(row,t\)/)
  assert.match(viewSources.AiChatView, /localizeModelType\(type\.value, modelTypes, t\)/)
})

test('空值和 null 安全回退', () => {
  assert.equal(localizeRoleName(null, en), '')
  assert.equal(localizeRoleName({ code: null, name: null }, en), '')
  assert.equal(localizeDepartmentName(null, en), '')
  assert.equal(localizeAuthenticatedUserDisplayName(null, en), '')
  assert.equal(localizeUserDisplayName(null, [], en), '')
  assert.equal(localizeRouteName(null, en), '')
  assert.equal(localizeDictionaryTypeName(null, en), '')
  assert.equal(localizeDictionaryDataLabel(null, en), '')
})

// 使用真实 Vue setup 检查模型选择约束，仅隔离 HTTP 和界面通知。
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as thinking from '../src/utils/thinkingLevels.js'

/** 加载真实模型配置组件，保留状态和分支逻辑。 */
function modelEditor() {
  const requests = []
  const warnings = []
  const source = compileScript(parse(viewSources.ModelsView).descriptor, { id: 'model-types-test' })
  const modules = {
    vue: { ...vue, onMounted() {} },
    'element-plus': { ElMessage: { warning: message => warnings.push(message), success() {} } },
    'vue-i18n': { useI18n: () => ({ t: zh }) },
    '../api/http': { default: { post: async (...args) => { requests.push(args); return { data: { durationMs: 1 } } } } },
    '../utils/localization': { localizeModelType },
    '../utils/thinkingLevels': thinking
  }
  const bindings = {}
  for (const node of source.scriptSetupAst.filter(node => node.type === 'ImportDeclaration')) {
    for (const spec of node.specifiers) bindings[spec.local.name] = modules[node.source.value][spec.type === 'ImportDefaultSpecifier' ? 'default' : spec.imported.name]
  }
  const code = source.content.replace(/^import[\s\S]*?from ['"][^'"]+['"];?$/gm, '').replace('export default', 'return')
  const component = new Function(...Object.keys(bindings), code)(...Object.values(bindings))
  return { state: component.setup({}, { expose() {} }), requests, warnings }
}

for (const type of ['image_model', 'video_model']) {
  test(type + ': 可与另一种生成类型组合但不能与聊天类型组合', () => {
    const { state } = modelEditor()
    state.form.supportedModelTypes = []
    assert.equal(state.modelTypeDisabled(type), false)
    state.form.supportedModelTypes = [type]
    for (const candidate of ['image_model', 'video_model', 'text_model', 'vision_model']) {
      assert.equal(state.modelTypeDisabled(candidate), ['text_model', 'vision_model'].includes(candidate))
    }
    state.open({ id: 1, supportedModelTypes: ['image_model', 'video_model'] })
    assert.equal(state.modelTypeDisabled('image_model'), false)
    assert.equal(state.modelTypeDisabled('video_model'), false)
    assert.equal(state.modelTypeDisabled('text_model'), true)
  })
  test(type + ': 模型测试显示未接入提示且不发送聊天请求', () => {
    const { state, requests, warnings } = modelEditor()
    state.startTest({ id: 1, supportedModelTypes: [type] })
    assert.deepEqual(requests, [])
    assert.deepEqual(warnings, [zh('models.generationUnavailable')])
  })
}

test('模型配置不再要求选择生成能力画像', () => {
  assert.doesNotMatch(viewSources.ModelsView, /generationProfile/)
  assert.doesNotMatch(viewSources.ModelsView, /ai\/generation\/profiles/)
})

test('文本与视觉理解仍可组合选择并调用既有模型测试接口', async () => {
  const { state, requests } = modelEditor()
  state.form.supportedModelTypes = ['text_model']
  assert.equal(state.modelTypeDisabled('vision_model'), false)
  assert.equal(state.modelTypeDisabled('image_model'), true)
  assert.equal(state.modelTypeDisabled('video_model'), true)
  state.startTest({ id: 7, supportedModelTypes: ['text_model'], thinkingLevels: '' })
  await Promise.resolve()
  assert.equal(requests[0][0], '/models/7/test')
})

test('混合生成与文本能力可使用文本模型测试接口', async () => {
  const { state, requests } = modelEditor()
  state.startTest({ id: 8, supportedModelTypes: ['image_model', 'text_model'], thinkingLevels: '' })
  await Promise.resolve()
  assert.equal(requests[0][0], '/models/8/test')
})
