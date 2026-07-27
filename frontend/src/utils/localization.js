/**
 * 共享本地化工具
 *
 * 为内置种子数据提供前端本地化映射，未知值回退到原始值。
 */

/** 按内置角色编码映射角色名，未知角色回退原值。 */
export function localizeRoleName(role, translate) {
  const key = role?.code === 'ADMIN' ? 'roles.admin' : null
  return key ? translate(key) : (role?.name || '')
}

/** 按内置部门编码映射部门名，未知部门回退原值。 */
export function localizeDepartmentName(department, translate) {
  const key = department?.code === 'ROOT' ? 'departments.root' : null
  return key ? translate(key) : (department?.name || '')
}

/** 仅本地化仍使用默认名称的内置管理员，避免误翻译同名普通用户。 */
export function localizeUserDisplayName(user, roles, translate) {
  const adminRoleId = roles?.find(role => role?.code === 'ADMIN')?.id
  const isBuiltInAdmin = adminRoleId != null && user?.roleIds?.includes(adminRoleId)
    && ['系统管理员', 'System Administrator'].includes(user?.displayName)
  return isBuiltInAdmin ? translate('users.systemAdmin') : (user?.displayName || user?.username || '')
}

/** 按内置功能编码映射路由名，未知路由回退原值。 */
export function localizeRouteName(route, translate) {
  const key = route?.featureCode === 'DEFAULT' ? 'routes.default' : null
  return key ? translate(key) : (route?.name || '')
}

const loginMessageKeys = Object.freeze({
  'auth.loginSuccess': 'auth.loginSuccess',
  'auth.loginFailed': 'auth.loginFailed',
  'auth.invalidCredentials': 'auth.invalidCredentials',
  'auth.accountDisabled': 'auth.accountDisabled',
  'auth.userNotFound': 'auth.userNotFound',
  '登录成功': 'auth.loginSuccess',
  '登录失败': 'auth.loginFailed',
  '用户名或密码错误': 'auth.invalidCredentials',
  '账号或密码错误': 'auth.invalidCredentials',
  '账号已停用': 'auth.accountDisabled',
  '登录用户不存在': 'auth.userNotFound'
})

/** 按稳定消息键或历史中文消息映射登录日志，未知值回退原值。 */
export function localizeLoginMessage(message, translate) {
  const key = loginMessageKeys[message]
  return key ? translate(key) : (message || '')
}

const taskTypeKeys = Object.freeze({
  AI_CHAT: 'tasks.types.aiChat',
  API_TRIGGER_CREATE: 'tasks.types.createTrigger',
  API_TRIGGER_UPDATE: 'tasks.types.updateTrigger',
  API_TRIGGER_DISABLE: 'tasks.types.disableTrigger',
  API_TRIGGER_VOID: 'tasks.types.voidTrigger',
  API_TRIGGER_EXECUTE: 'tasks.types.executeTrigger',
  API_TRIGGER_CRON: 'tasks.types.cronTrigger',
  API_TRIGGER_TEST: 'tasks.types.testTrigger',
  API_TRIGGER_SECURITY_UPDATE: 'tasks.types.updateTriggerSecurity',
  'tasks.types.aiChat': 'tasks.types.aiChat',
  'tasks.types.createTrigger': 'tasks.types.createTrigger',
  'tasks.types.updateTrigger': 'tasks.types.updateTrigger',
  'tasks.types.disableTrigger': 'tasks.types.disableTrigger',
  'tasks.types.voidTrigger': 'tasks.types.voidTrigger',
  'tasks.types.executeTrigger': 'tasks.types.executeTrigger',
  'tasks.types.cronTrigger': 'tasks.types.cronTrigger',
  'tasks.types.testTrigger': 'tasks.types.testTrigger',
  'tasks.types.updateTriggerSecurity': 'tasks.types.updateTriggerSecurity',
  'AI 对话': 'tasks.types.aiChat',
  '新增接口触发配置': 'tasks.types.createTrigger',
  '更新接口触发配置': 'tasks.types.updateTrigger',
  '停用接口触发配置': 'tasks.types.disableTrigger',
  '作废接口触发配置': 'tasks.types.voidTrigger',
  '手动执行接口触发': 'tasks.types.executeTrigger',
  '接口定时触发': 'tasks.types.cronTrigger',
  '临时测试接口调用': 'tasks.types.testTrigger',
  '更新接口触发安全配置': 'tasks.types.updateTriggerSecurity'
})

/** 本地化新旧任务类型，同时保留未知类型的原始诊断信息。 */
export function localizeTaskType(taskType, translate) {
  const key = taskTypeKeys[taskType]
  return key ? translate(key) : (taskType || '')
}

const modelTypeKeys = Object.freeze({
  text_model: 'models.types.text_model',
  vision_model: 'models.types.vision_model'
})

/** 本地化内置模型类型，自定义类型优先回退管理员维护的字典标签。 */
export function localizeModelType(value, options, translate) {
  const key = modelTypeKeys[value]
  if (key) return translate(key)
  return options?.find(option => option?.value === value)?.label || value || ''
}
