/** 根据管理员、精确和兼容管理规则判断有效权限。 */
export function hasEffectivePermission(user, permission) {
  if (!permission || user?.roles?.includes('ADMIN') || user?.permissions?.includes(permission)) return true
  const separator = permission.lastIndexOf(':')
  return separator > 0 && user?.permissions?.includes(`${permission.slice(0, separator)}:manage`) || false
}
