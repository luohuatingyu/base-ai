const menuKeysByPermission = Object.freeze({
  'ai:catalog': 'nav.items.ai',
  'ai:chat:invoke': 'nav.items.aiChat',
  'system:catalog': 'nav.items.system',
  'system:user:list': 'nav.items.users',
  'system:user:create': 'menus.buttons.createUser',
  'system:user:update': 'menus.buttons.updateUser',
  'system:user:delete': 'menus.buttons.deleteUser',
  'system:user:manage': 'menus.buttons.manageUser',
  'system:role:list': 'nav.items.roles',
  'system:role:create': 'menus.buttons.createRole',
  'system:role:update': 'menus.buttons.updateRole',
  'system:role:delete': 'menus.buttons.deleteRole',
  'system:role:manage': 'menus.buttons.manageRole',
  'system:menu:list': 'nav.items.menus',
  'system:menu:create': 'menus.buttons.createMenu',
  'system:menu:update': 'menus.buttons.updateMenu',
  'system:menu:delete': 'menus.buttons.deleteMenu',
  'system:menu:manage': 'menus.buttons.manageMenu',
  'system:department:list': 'nav.items.departments',
  'system:department:create': 'menus.buttons.createDepartment',
  'system:department:update': 'menus.buttons.updateDepartment',
  'system:department:delete': 'menus.buttons.deleteDepartment',
  'system:position:list': 'nav.items.positions',
  'system:position:create': 'menus.buttons.createPosition',
  'system:position:update': 'menus.buttons.updatePosition',
  'system:position:delete': 'menus.buttons.deletePosition',
  'system:dictionary:list': 'nav.items.dictionaries',
  'system:dictionary:create': 'menus.buttons.createDictionary',
  'system:dictionary:update': 'menus.buttons.updateDictionary',
  'system:dictionary:delete': 'menus.buttons.deleteDictionary',
  'system:setting:list': 'nav.items.settings',
  'system:setting:create': 'menus.buttons.createSetting',
  'system:setting:update': 'menus.buttons.updateSetting',
  'system:setting:delete': 'menus.buttons.deleteSetting',
  'system:session:list': 'nav.items.onlineUsers',
  'system:session:terminate': 'menus.buttons.forceLogout',
  'system:audit:operation:list': 'nav.items.operationLogs',
  'system:audit:login:list': 'nav.items.loginLogs',
  'system:task:view': 'nav.items.tasks',
  'system:task:manage': 'menus.buttons.manageTask',
  'system:api-key:list': 'nav.items.apiKeys',
  'system:api-key:create': 'menus.buttons.createApiKey',
  'system:api-key:update': 'menus.buttons.updateApiKey',
  'system:api-key:delete': 'menus.buttons.revokeApiKey',
  'system:api-key:rotate': 'menus.buttons.rotateApiKey',
  'mail:catalog': 'nav.items.mail',
  'mail:account:list': 'nav.items.mailAccounts',
  'mail:account:create': 'mailAccounts.add',
  'mail:account:update': 'mailAccounts.edit',
  'mail:account:delete': 'common.delete',
  'mail:route:list': 'nav.items.mailRoutes',
  'mail:route:create': 'mailRoutes.add',
  'mail:route:update': 'mailRoutes.edit',
  'mail:route:delete': 'common.delete',
  'model:catalog': 'nav.items.models',
  'model:provider:list': 'nav.items.providers',
  'model:provider:create': 'menus.buttons.createProvider',
  'model:provider:update': 'menus.buttons.updateProvider',
  'model:provider:delete': 'menus.buttons.deleteProvider',
  'model:model:list': 'nav.items.modelConfig',
  'model:model:create': 'menus.buttons.createModel',
  'model:model:update': 'menus.buttons.updateModel',
  'model:model:delete': 'menus.buttons.deleteModel',
  'model:route:list': 'nav.items.routes',
  'model:route:create': 'menus.buttons.createRoute',
  'model:route:update': 'menus.buttons.updateRoute',
  'model:route:delete': 'menus.buttons.deleteRoute',
  'automation:catalog': 'nav.items.automation',
  'automation:api-trigger:list': 'nav.items.apiTriggers',
  'automation:api-trigger:create': 'menus.buttons.createTrigger',
  'automation:api-trigger:update': 'menus.buttons.updateTrigger',
  'automation:api-trigger:delete': 'menus.buttons.deleteTrigger',
  'automation:api-trigger:trigger': 'menus.buttons.executeTrigger',
  'automation:api-trigger:logs': 'menus.buttons.triggerLogs',
  'automation:api-trigger-security:view': 'nav.items.apiTriggerSecurity',
  'automation:api-trigger-security:update': 'menus.buttons.updateTriggerSecurity'
})

const menuKeysByPath = Object.freeze({
  '/ai': 'nav.items.ai',
  '/ai-chat': 'nav.items.aiChat',
  '/system': 'nav.items.system',
  '/users': 'nav.items.users',
  '/roles': 'nav.items.roles',
  '/menus': 'nav.items.menus',
  '/departments': 'nav.items.departments',
  '/positions': 'nav.items.positions',
  '/dictionaries': 'nav.items.dictionaries',
  '/settings': 'nav.items.settings',
  '/online-users': 'nav.items.onlineUsers',
  '/operation-logs': 'nav.items.operationLogs',
  '/login-logs': 'nav.items.loginLogs',
  '/tasks': 'nav.items.tasks',
  '/api-keys': 'nav.items.apiKeys',
  '/mail': 'nav.items.mail',
  '/mail/accounts': 'nav.items.mailAccounts',
  '/mail/routes': 'nav.items.mailRoutes',
  '/models': 'nav.items.models',
  '/model-providers': 'nav.items.providers',
  '/model-routes': 'nav.items.routes',
  '/automation': 'nav.items.automation',
  '/automation/api-triggers': 'nav.items.apiTriggers',
  '/automation/api-trigger-security': 'nav.items.apiTriggerSecurity'
})

/** 根据内置菜单权限或路径解析当前语言名称，自定义菜单回退后台原始名称。 */
export function localizeMenuName(menu, translate) {
  const key = menuKeysByPermission[menu?.permission] || menuKeysByPath[menu?.path]
  return key ? translate(key) : (menu?.name || '')
}

/** 从导航树查找当前页面；路径相同时优先返回可导航页面而不是目录。 */
export function findNavigationItem(items, path) {
  let fallback = null
  for (const item of items || []) {
    if (item.path === path && item.type === 'MENU') return item
    const child = findNavigationItem(item.children, path)
    if (child) return child
    if (!fallback && item.path === path) fallback = item
  }
  return fallback
}

/**
 * 将后端菜单裁剪为当前用户可访问且已在前端实现的导航树。
 *
 * 目录节点只在包含有效后代时保留；无效目录下的有效后代会提升，避免因菜单配置不完整而丢失可访问页面。
 */
export function buildAccessibleNavigation(menus, availablePaths, hasPermission) {
  const pathSet = new Set(availablePaths)
  const nodes = new Map((menus || []).map(item => [item.id, { ...item, children: [] }]))
  const roots = []

  nodes.forEach(node => {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  })

  const sortNodes = list => list.sort((left, right) => (left.sortOrder || 0) - (right.sortOrder || 0))

  /** 递归裁剪节点，并在目录不可用时提升其有效后代。 */
  function prune(node) {
    if (node.type === 'BUTTON') return []
    const children = sortNodes(node.children).flatMap(prune)

    if (node.type === 'CATALOG') {
      return node.visible !== false && hasPermission(node.permission) && children.length
        ? [{ ...node, children }]
        : children
    }

    if (node.visible !== false && hasPermission(node.permission) && node.type === 'MENU' && node.path && pathSet.has(node.path)) {
      return [{ ...node, children }]
    }

    return children
  }

  return sortNodes(roots).flatMap(prune)
}

/** 从路由记录提取可展示导航的真实页面路径。 */
export function getNavigablePaths(routes) {
  return new Set((routes || [])
    .filter(route => route.meta?.navigable === true && route.components?.default)
    .map(route => route.path))
}
