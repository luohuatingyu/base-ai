const DATA_SOURCE_LIST_PERMISSION = 'operations:data-source:list'
const DATA_SOURCE_MAINTENANCE_PERMISSIONS = new Set([
  'operations:data-source:create',
  'operations:data-source:update',
  'operations:data-source:delete'
])

/** 根据管理员、精确、兼容管理和数据源联动规则判断有效权限。 */
export function hasEffectivePermission(user, permission) {
  if (!permission || user?.roles?.includes('ADMIN') || user?.permissions?.includes(permission)) return true
  if (DATA_SOURCE_MAINTENANCE_PERMISSIONS.has(permission)
    && user?.permissions?.includes(DATA_SOURCE_LIST_PERMISSION)) return true
  const separator = permission.lastIndexOf(':')
  return separator > 0 && user?.permissions?.includes(`${permission.slice(0, separator)}:manage`) || false
}
