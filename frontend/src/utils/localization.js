/**
 * 共享本地化工具
 *
 * 为内置种子数据提供前端本地化映射，未知值回退到原始值。
 */

/** 按已知中文种子值映射角色名，未知值回退原值。 */
export function localizeRoleName(role, translate) {
  const nameMap = { '系统管理员': 'roles.admin' }
  const key = role?.code === 'ADMIN' ? 'roles.admin' : nameMap[role?.name]
  return key ? translate(key) : (role?.name || '')
}

/** 按已知中文种子值映射部门名，未知值回退原值。 */
export function localizeDepartmentName(department, translate) {
  const nameMap = { 'AI平台': 'departments.root' }
  const key = department?.code === 'ROOT' ? 'departments.root' : nameMap[department?.name]
  return key ? translate(key) : (department?.name || '')
}

/** 按已知中文种子值映射用户显示名，未知值回退原值。 */
export function localizeUserDisplayName(username, displayName, translate) {
  const nameMap = { '系统管理员': 'users.systemAdmin' }
  const key = nameMap[displayName]
  return key ? translate(key) : (displayName || username || '')
}

/** 按已知中文种子值映射路由名，未知值回退原值。 */
export function localizeRouteName(route, translate) {
  const nameMap = { '默认能力路由': 'routes.default' }
  const key = route?.featureCode === 'DEFAULT' ? 'routes.default' : nameMap[route?.name]
  return key ? translate(key) : (route?.name || '')
}

/** 按已知中文消息映射登录日志，未知值回退原值。 */
export function localizeLoginMessage(message, translate) {
  // 如果是 i18n key 格式（auth.xxx），直接翻译
  if (message?.startsWith('auth.')) return translate(message)

  // 否则按中文值映射（兼容历史数据）
  const messageMap = {
    '登录成功': 'auth.loginSuccess',
    '用户名或密码错误': 'auth.loginFailed',
    '账号或密码错误': 'auth.invalidCredentials',
    '账号已停用': 'auth.accountDisabled',
    '登录用户不存在': 'auth.userNotFound'
  }
  const key = messageMap[message]
  return key ? translate(key) : (message || '')
}
