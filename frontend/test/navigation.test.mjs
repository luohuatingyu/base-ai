import assert from 'node:assert/strict'
import test from 'node:test'
import { buildAccessibleNavigation } from '../src/utils/navigation.js'

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
