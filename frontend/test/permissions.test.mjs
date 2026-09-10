import assert from 'node:assert/strict'
import test from 'node:test'
import { hasEffectivePermission } from '../src/utils/permissions.js'

/** 创建仅包含指定权限的普通用户。 */
function user(permissions) {
  return { roles: ['USER'], permissions }
}

test('数据源查看权限自动拥有新增编辑删除权限', () => {
  const viewer = user(['operations:data-source:list'])

  for (const permission of [
    'operations:data-source:create',
    'operations:data-source:update',
    'operations:data-source:delete'
  ]) assert.equal(hasEffectivePermission(viewer, permission), true, permission)
})

test('数据源查看权限不包含测试权限和其他资源权限', () => {
  const viewer = user(['operations:data-source:list'])

  assert.equal(hasEffectivePermission(viewer, 'operations:data-source:test'), false)
  assert.equal(hasEffectivePermission(viewer, 'operations:data-sync:create'), false)
})

test('管理员、精确权限和兼容管理权限行为保持不变', () => {
  assert.equal(hasEffectivePermission({ roles: ['ADMIN'], permissions: [] }, 'system:user:delete'), true)
  assert.equal(hasEffectivePermission(user(['system:user:create']), 'system:user:create'), true)
  assert.equal(hasEffectivePermission(user(['system:user:manage']), 'system:user:update'), true)
  assert.equal(hasEffectivePermission(null, 'system:user:list'), false)
})
