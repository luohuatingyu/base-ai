import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  buildRolePermissionTree,
  normalizeRolePermissionIds,
  updateRolePermissionSelection
} from '../src/utils/rolePermissions.js'

const rolesViewSource = readFileSync(new URL('../src/views/RolesView.vue', import.meta.url), 'utf8')

/** 创建目录、页面和按钮组成的标准权限树测试数据。 */
function permissions() {
  return [
    { id: 1, parentId: null, name: '系统管理', type: 'CATALOG', permission: 'system:catalog', sortOrder: 10 },
    { id: 2, parentId: 1, name: '用户管理', type: 'MENU', permission: 'system:user:list', sortOrder: 20 },
    { id: 3, parentId: 2, name: '新增用户', type: 'BUTTON', permission: 'system:user:create', sortOrder: 21 },
    { id: 4, parentId: 2, name: '编辑用户', type: 'BUTTON', permission: 'system:user:update', sortOrder: 22 }
  ]
}

/** 创建数据源查看和维护操作组成的权限树测试数据。 */
function dataSourcePermissions() {
  return [
    { id: 10, parentId: null, name: '运维管理', type: 'CATALOG', permission: 'operations:catalog', sortOrder: 10 },
    { id: 11, parentId: 10, name: '数据源管理', type: 'MENU', permission: 'operations:data-source:list', sortOrder: 11 },
    { id: 12, parentId: 11, name: '新增数据源', type: 'BUTTON', permission: 'operations:data-source:create', sortOrder: 111 },
    { id: 13, parentId: 11, name: '编辑数据源', type: 'BUTTON', permission: 'operations:data-source:update', sortOrder: 112 },
    { id: 14, parentId: 11, name: '删除数据源', type: 'BUTTON', permission: 'operations:data-source:delete', sortOrder: 113 },
    { id: 15, parentId: 11, name: '测试数据源', type: 'BUTTON', permission: 'operations:data-source:test', sortOrder: 115 }
  ]
}

test('角色权限按目录、页面和按钮构建有序树', () => {
  const tree = buildRolePermissionTree(permissions(), item => `本地化:${item.name}`)

  assert.equal(tree[0].label, '本地化:系统管理')
  assert.equal(tree[0].children[0].type, 'MENU')
  assert.deepEqual(tree[0].children[0].children.map(item => item.id), [3, 4])
})

test('勾选按钮只补齐所属页面和上级目录，不授予同级按钮', () => {
  const selected = updateRolePermissionSelection(permissions(), [], 3, true)

  assert.deepEqual(selected, [1, 2, 3])
  assert.equal(selected.includes(4), false)
})

test('勾选页面不会自动授予其按钮', () => {
  assert.deepEqual(updateRolePermissionSelection(permissions(), [], 2, true), [1, 2])
})

test('勾选数据源查看权限自动授予新增编辑删除但不授予测试权限', () => {
  assert.deepEqual(updateRolePermissionSelection(dataSourcePermissions(), [], 11, true), [10, 11, 12, 13, 14])
})

test('历史角色的数据源查看权限回显时自动补齐维护权限', () => {
  assert.deepEqual(normalizeRolePermissionIds(dataSourcePermissions(), [11]), [10, 11, 12, 13, 14])
})

test('保留数据源查看权限时不能单独取消固定维护权限', () => {
  assert.deepEqual(updateRolePermissionSelection(dataSourcePermissions(), [10, 11, 12, 13, 14], 12, false),
    [10, 11, 12, 13, 14])
})

test('取消页面会清除其按钮并保留仍可独立存在的目录', () => {
  const selected = updateRolePermissionSelection(permissions(), [1, 2, 3, 4], 2, false)

  assert.deepEqual(selected, [1])
})

test('编辑角色时规范化历史按钮权限并补齐页面层级', () => {
  assert.deepEqual(normalizeRolePermissionIds(permissions(), [3]), [1, 2, 3])
})

test('没有所属页面的异常按钮保持不可配置', () => {
  const malformed = [
    ...permissions(),
    { id: 5, parentId: 1, name: '孤立按钮', type: 'BUTTON', permission: 'system:orphan', sortOrder: 30 }
  ]

  assert.deepEqual(updateRolePermissionSelection(malformed, [], 5, true), [])
  assert.deepEqual(normalizeRolePermissionIds(malformed, [5]), [])
})

test('取消目录会清除其全部页面与按钮权限', () => {
  assert.deepEqual(updateRolePermissionSelection(permissions(), [1, 2, 3], 1, false), [])
})

test('角色弹窗使用树组件配置并从树中读取权限', () => {
  assert.match(rolesViewSource, /<el-tree/)
  assert.match(rolesViewSource, /show-checkbox/)
  assert.match(rolesViewSource, /check-strictly/)
  assert.match(rolesViewSource, /getCheckedKeys\(\)/)
})
