import assert from 'node:assert/strict'
import test from 'node:test'
import { localizeRoleName, localizeDepartmentName, localizeUserDisplayName,
         localizeRouteName, localizeLoginMessage } from '../src/utils/localization.js'

const t = key => ({
  'roles.admin': 'System Administrator',
  'departments.root': 'AI Platform',
  'users.systemAdmin': 'System Administrator',
  'routes.default': 'Default Route',
  'auth.loginSuccess': 'Login successful',
  'auth.loginFailed': 'Incorrect username or password',
  'auth.invalidCredentials': 'Incorrect username or password',
  'auth.accountDisabled': 'The account is disabled',
  'auth.userNotFound': 'The authenticated user does not exist'
}[key] || key)

test('角色名按 code 优先映射，值回退', () => {
  assert.equal(localizeRoleName({ code: 'ADMIN', name: '系统管理员' }, t), 'System Administrator')
  assert.equal(localizeRoleName({ code: 'ADMIN', name: 'Custom Admin' }, t), 'System Administrator')
  assert.equal(localizeRoleName({ code: 'CUSTOM', name: '系统管理员' }, t), 'System Administrator')
  assert.equal(localizeRoleName({ code: 'CUSTOM', name: '自定义角色' }, t), '自定义角色')
})

test('部门名按 code 优先映射，值回退', () => {
  assert.equal(localizeDepartmentName({ code: 'ROOT', name: 'AI平台' }, t), 'AI Platform')
  assert.equal(localizeDepartmentName({ code: 'ROOT', name: 'Changed Root' }, t), 'AI Platform')
  assert.equal(localizeDepartmentName({ code: 'DEPT_001', name: 'AI平台' }, t), 'AI Platform')
  assert.equal(localizeDepartmentName({ code: 'DEPT_001', name: '研发部' }, t), '研发部')
})

test('用户显示名按值映射，回退原值', () => {
  assert.equal(localizeUserDisplayName('admin', '系统管理员', t), 'System Administrator')
  assert.equal(localizeUserDisplayName('admin', 'Custom Name', t), 'Custom Name')
  assert.equal(localizeUserDisplayName('alice', null, t), 'alice')
})

test('路由名按 featureCode 优先映射，值回退', () => {
  assert.equal(localizeRouteName({ featureCode: 'DEFAULT', name: '默认能力路由' }, t), 'Default Route')
  assert.equal(localizeRouteName({ featureCode: 'DEFAULT', name: 'Changed Default' }, t), 'Default Route')
  assert.equal(localizeRouteName({ featureCode: 'chat', name: '默认能力路由' }, t), 'Default Route')
  assert.equal(localizeRouteName({ featureCode: 'chat', name: '聊天路由' }, t), '聊天路由')
})

test('登录消息兼容 key 和中文值', () => {
  // i18n key 格式直接翻译
  assert.equal(localizeLoginMessage('auth.loginSuccess', t), 'Login successful')
  assert.equal(localizeLoginMessage('auth.accountDisabled', t), 'The account is disabled')

  // 中文值映射（历史数据）
  assert.equal(localizeLoginMessage('登录成功', t), 'Login successful')
  assert.equal(localizeLoginMessage('用户名或密码错误', t), 'Incorrect username or password')
  assert.equal(localizeLoginMessage('账号或密码错误', t), 'Incorrect username or password')
  assert.equal(localizeLoginMessage('账号已停用', t), 'The account is disabled')
  assert.equal(localizeLoginMessage('登录用户不存在', t), 'The authenticated user does not exist')

  // 未知值回退
  assert.equal(localizeLoginMessage('未知错误', t), '未知错误')
  assert.equal(localizeLoginMessage(null, t), '')
})

test('空值和 null 安全回退', () => {
  assert.equal(localizeRoleName(null, t), '')
  assert.equal(localizeRoleName({ code: null, name: null }, t), '')
  assert.equal(localizeDepartmentName(null, t), '')
  assert.equal(localizeUserDisplayName(null, null, t), '')
  assert.equal(localizeRouteName(null, t), '')
})
