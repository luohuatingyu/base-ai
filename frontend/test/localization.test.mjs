import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  localizeDepartmentName,
  localizeLoginMessage,
  localizeModelType,
  localizeRoleName,
  localizeRouteName,
  localizeTaskType,
  localizeUserDisplayName
} from '../src/utils/localization.js'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const viewSources = Object.fromEntries(['UsersView', 'RolesView', 'MenusView', 'DepartmentsView', 'LoginLogsView', 'TasksView', 'ModelsView', 'ModelRoutesView']
  .map(name => [name, readFileSync(new URL(`../src/views/${name}.vue`, import.meta.url), 'utf8')]))

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

test('八个问题页面全部接入共享本地化解析器', () => {
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
})

test('空值和 null 安全回退', () => {
  assert.equal(localizeRoleName(null, en), '')
  assert.equal(localizeRoleName({ code: null, name: null }, en), '')
  assert.equal(localizeDepartmentName(null, en), '')
  assert.equal(localizeUserDisplayName(null, [], en), '')
  assert.equal(localizeRouteName(null, en), '')
})
