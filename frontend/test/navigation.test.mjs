import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import { buildAccessibleNavigation, findNavigationItem, getNavigablePaths, localizeMenuName } from '../src/utils/navigation.js'

const adminLayoutSource = readFileSync(new URL('../src/views/AdminLayout.vue', import.meta.url), 'utf8')
const menuNodeSource = readFileSync(new URL('../src/components/MenuNode.vue', import.meta.url), 'utf8')
const menusViewSource = readFileSync(new URL('../src/views/MenusView.vue', import.meta.url), 'utf8')

/** 创建与 vue-i18n 行为一致的简易翻译函数，用于验证真实语言资源。 */
function translator(messages) {
  return key => key.split('.').reduce((value, segment) => value?.[segment], messages) || key
}

/** 创建测试菜单，减少各场景的重复字段。 */
function menu(id, parentId, name, type, path, permission, visible = true, sortOrder = id) {
  return { id, parentId, name, type, path, permission, visible, sortOrder }
}

test('只保留有权限且存在前端路由的导航页面', () => {
  const menus = [
    menu(1, null, '系统管理', 'CATALOG', '/system', 'system:catalog'),
    menu(2, 1, '用户管理', 'MENU', '/users', 'system:user:list'),
    menu(3, 1, '无效页面', 'MENU', '/not-implemented', 'system:unknown:list'),
    menu(4, 1, '隐藏页面', 'MENU', '/users', 'system:hidden:list', false),
    menu(5, 1, '新增用户', 'BUTTON', null, 'system:user:create'),
    menu(6, null, 'AI 能力', 'CATALOG', '/ai', 'ai:catalog'),
    menu(7, 6, 'AI 对话', 'MENU', '/ai-chat', 'ai:chat:invoke'),
    menu(8, null, '空目录', 'CATALOG', '/empty', 'empty:catalog')
  ]
  const granted = new Set(['system:catalog', 'system:user:list', 'system:unknown:list', 'system:hidden:list', 'system:user:create', 'ai:chat:invoke', 'empty:catalog'])

  const navigation = buildAccessibleNavigation(menus, ['/users', '/ai-chat'], permission => granted.has(permission))

  assert.deepEqual(navigation.map(item => item.name), ['系统管理', 'AI 对话'])
  assert.deepEqual(navigation[0].children.map(item => item.name), ['用户管理'])
})

test('父目录缺失或无权限时提升有效页面，避免丢失可访问导航', () => {
  const menus = [
    menu(10, null, '未授权目录', 'CATALOG', '/reports', 'report:catalog'),
    menu(11, 10, '报表中心', 'MENU', '/reports', 'report:view'),
    menu(12, 99, '孤立页面', 'MENU', '/orphan', 'orphan:view')
  ]
  const granted = new Set(['report:view', 'orphan:view'])

  const navigation = buildAccessibleNavigation(menus, ['/reports', '/orphan'], permission => granted.has(permission))

  assert.deepEqual(navigation.map(item => item.name), ['报表中心', '孤立页面'])
})

test('只将显式标记且绑定页面组件的路由加入导航白名单', () => {
  const routes = [
    { path: '/users', meta: { navigable: true }, components: { default: {} } },
    { path: '/unfinished', meta: { navigable: true }, components: {} },
    { path: '/hidden', meta: {}, components: { default: {} } },
    { path: '/login', meta: { public: true }, components: { default: {} } }
  ]

  assert.deepEqual([...getNavigablePaths(routes)], ['/users'])
})

test('内置菜单按当前语言显示并通过权限区分相同路径', () => {
  const translateEnglish = translator(enUS)
  const translateChinese = translator(zhCN)
  const modelCatalog = menu(20, null, '模型管理', 'CATALOG', '/models', 'model:catalog')
  const modelPage = menu(21, 20, '模型配置', 'MENU', '/models', 'model:model:list')

  assert.equal(localizeMenuName(modelCatalog, translateEnglish), 'Model Management')
  assert.equal(localizeMenuName(modelPage, translateEnglish), 'Model Configuration')
  assert.equal(localizeMenuName(modelPage, translateChinese), '模型配置')
  assert.equal(localizeMenuName(menu(22, null, 'API Key 管理', 'MENU', '/api-keys', 'system:api-key:list'), translateEnglish), 'API Keys')
  assert.equal(localizeMenuName(menu(23, null, '触发安全配置', 'MENU', '/automation/api-trigger-security', 'automation:api-trigger-security:view'), translateEnglish), 'Trigger Security')
})

test('全部内置 BUTTON 权限均使用稳定权限编码翻译', () => {
  const translateEnglish = translator(enUS)
  const permissions = [
    'system:user:create', 'system:user:update', 'system:user:delete',
    'system:role:create', 'system:role:update', 'system:role:delete',
    'system:menu:create', 'system:menu:update', 'system:menu:delete',
    'system:department:create', 'system:department:update', 'system:department:delete',
    'system:position:create', 'system:position:update', 'system:position:delete',
    'system:dictionary:create', 'system:dictionary:update', 'system:dictionary:delete',
    'system:setting:create', 'system:setting:update', 'system:setting:delete',
    'system:session:terminate', 'system:task:manage',
    'system:api-key:create', 'system:api-key:update', 'system:api-key:delete', 'system:api-key:rotate',
    'mail:account:create', 'mail:account:update', 'mail:account:delete',
    'mail:route:create', 'mail:route:update', 'mail:route:delete',
    'model:provider:create', 'model:provider:update', 'model:provider:delete',
    'model:model:create', 'model:model:update', 'model:model:delete',
    'model:route:create', 'model:route:update', 'model:route:delete',
    'automation:api-trigger:create', 'automation:api-trigger:update', 'automation:api-trigger:delete',
    'automation:api-trigger:trigger', 'automation:api-trigger:logs',
    'automation:api-trigger-security:update'
  ]

  for (const [index, permission] of permissions.entries()) {
    const original = `原始按钮 ${index}`
    assert.notEqual(localizeMenuName(menu(100 + index, null, original, 'BUTTON', null, permission), translateEnglish), original, permission)
  }
})

test('顶部标题在目录与页面路径相同时优先使用页面名称', () => {
  const modelCatalog = { ...menu(20, null, '模型管理', 'CATALOG', '/models', 'model:catalog'), children: [
    menu(21, 20, '模型配置', 'MENU', '/models', 'model:model:list')
  ] }

  assert.equal(findNavigationItem([modelCatalog], '/models')?.permission, 'model:model:list')
})

test('未知和空菜单名称安全回退后台原值', () => {
  const translateEnglish = translator(enUS)

  assert.equal(localizeMenuName(menu(30, null, 'Custom Reports', 'MENU', '/custom-reports', 'custom:reports'), translateEnglish), 'Custom Reports')
  assert.equal(localizeMenuName(null, translateEnglish), '')
})

test('侧栏、顶部标题和菜单管理页复用统一菜单名称解析', () => {
  assert.match(menuNodeSource, /localizeMenuName\(props\.item,\s*t\)/)
  assert.match(adminLayoutSource, /localizeMenuName\(item\s*\|\|\s*\{\s*path:\s*route\.path/)
  assert.match(menusViewSource, /localizedName=row=>localizeMenuName\(row,t\)/)
  assert.match(menusViewSource, /:label="localizedName\(item\)"/)
  assert.doesNotMatch(menuNodeSource, /menuKeyMap/)
  assert.doesNotMatch(adminLayoutSource, /menuKeyMap/)
})
