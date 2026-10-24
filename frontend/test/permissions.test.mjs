import assert from 'node:assert/strict'
import test from 'node:test'
import { hasEffectivePermission } from '../src/utils/permissions.js'

/** 创建仅包含指定权限的普通用户。 */
function user(permissions) {
  return { roles: ['USER'], permissions }
}

test('管理员、精确权限和兼容管理权限行为保持不变', () => {
  assert.equal(hasEffectivePermission({ roles: ['ADMIN'], permissions: [] }, 'system:user:delete'), true)
  assert.equal(hasEffectivePermission(user(['system:user:create']), 'system:user:create'), true)
  assert.equal(hasEffectivePermission(user(['system:user:manage']), 'system:user:update'), true)
  assert.equal(hasEffectivePermission(null, 'system:user:list'), false)
})
