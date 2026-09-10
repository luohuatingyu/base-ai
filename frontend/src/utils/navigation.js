const menuKeysByPermission = Object.freeze({
  'ai:catalog': 'nav.items.ai',
  'ai:chat:invoke': 'nav.items.aiChat',
  'ai:model:catalog': 'nav.items.models',
  'automation:catalog': 'nav.items.automation',
  'automation:workflow:catalog': 'nav.items.workflow',
  'operations:catalog': 'nav.items.operations',
  'operations:monitoring:catalog': 'nav.items.monitoring',
  'system:catalog': 'nav.items.system',
  'system:access:catalog': 'nav.items.access',
  'system:organization:catalog': 'nav.items.organization',
  'system:user:list': 'nav.items.users',
  'system:user:create': 'menus.buttons.createUser',
  'system:user:update': 'menus.buttons.updateUser',
  'system:user:delete': 'menus.buttons.deleteUser',
  'system:role:list': 'nav.items.roles',
  'system:role:create': 'menus.buttons.createRole',
  'system:role:update': 'menus.buttons.updateRole',
  'system:role:delete': 'menus.buttons.deleteRole',
  'system:menu:list': 'nav.items.menus',
  'system:menu:create': 'menus.buttons.createMenu',
  'system:menu:update': 'menus.buttons.updateMenu',
  'system:menu:delete': 'menus.buttons.deleteMenu',
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
  'operations:session:list': 'nav.items.onlineUsers',
  'operations:session:terminate': 'menus.buttons.forceLogout',
  'operations:audit:operation:list': 'nav.items.operationLogs',
  'operations:audit:login:list': 'nav.items.loginLogs',
  'operations:task:view': 'nav.items.tasks',
  'operations:task:manage': 'menus.buttons.manageTask',
  'operations:data-source:list': 'nav.items.dataSources',
  'operations:data-source:create': 'dataSources.add',
  'operations:data-source:update': 'common.edit',
  'operations:data-source:delete': 'common.delete',
  'operations:data-source:test': 'dataSources.test',
  'operations:data-sync:list': 'nav.items.dataSync',
  'operations:data-sync:create': 'dataSync.title',
  'operations:data-sync:update': 'common.edit',
  'operations:data-sync:delete': 'common.delete',
  'operations:data-sync:preview': 'dataSync.preview',
  'operations:data-sync:run': 'dataSync.run',
  'operations:data-sync:cancel': 'dataSync.cancel',
  'operations:data-sync:logs': 'dataSync.title',
  'operations:server:list': 'nav.items.servers',
  'operations:server:create': 'servers.add',
  'operations:server:update': 'servers.edit',
  'operations:server:delete': 'common.delete',
  'operations:server:test': 'servers.test',
  'operations:server:deploy': 'servers.deploy',
  'operations:server:rollback': 'servers.deploy',
  'operations:server:logs': 'servers.title',
  'system:api-key:list': 'nav.items.apiKeys',
  'system:api-key:create': 'menus.buttons.createApiKey',
  'system:api-key:update': 'menus.buttons.updateApiKey',
  'system:api-key:delete': 'menus.buttons.revokeApiKey',
  'system:api-key:rotate': 'menus.buttons.rotateApiKey',
  'system:mail:catalog': 'nav.items.mail',
  'system:mail:account:list': 'nav.items.mailAccounts',
  'system:mail:account:create': 'mailAccounts.add',
  'system:mail:account:update': 'mailAccounts.edit',
  'system:mail:account:delete': 'common.delete',
  'system:mail:route:list': 'nav.items.mailRoutes',
  'system:mail:route:create': 'mailRoutes.add',
  'system:mail:route:update': 'mailRoutes.edit',
  'system:mail:route:delete': 'common.delete',
  'ai:model:provider:list': 'nav.items.providers',
  'ai:model:provider:create': 'menus.buttons.createProvider',
  'ai:model:provider:update': 'menus.buttons.updateProvider',
  'ai:model:provider:delete': 'menus.buttons.deleteProvider',
  'ai:model:model:list': 'nav.items.modelConfig',
  'ai:model:model:create': 'menus.buttons.createModel',
  'ai:model:model:update': 'menus.buttons.updateModel',
  'ai:model:model:delete': 'menus.buttons.deleteModel',
  'ai:model:route:list': 'nav.items.routes',
  'ai:model:route:create': 'menus.buttons.createRoute',
  'ai:model:route:update': 'menus.buttons.updateRoute',
  'ai:model:route:delete': 'menus.buttons.deleteRoute',
  'ai:model:knowledge-base:list': 'nav.items.knowledgeBases',
  'ai:model:knowledge-base:create': 'knowledgeBases.add',
  'ai:model:knowledge-base:update': 'common.edit',
  'ai:model:knowledge-base:delete': 'common.delete',
  'automation:api-trigger:list': 'nav.items.apiTriggers',
  'automation:api-trigger:create': 'menus.buttons.createTrigger',
  'automation:api-trigger:update': 'menus.buttons.updateTrigger',
  'automation:api-trigger:delete': 'menus.buttons.deleteTrigger',
  'automation:api-trigger:trigger': 'menus.buttons.executeTrigger',
  'automation:api-trigger:logs': 'menus.buttons.triggerLogs',
  'automation:api-trigger-security:view': 'nav.items.apiTriggerSecurity',
  'automation:api-trigger-security:update': 'menus.buttons.updateTriggerSecurity',
  'operations:device-agent:list': 'nav.items.deviceAgents',
  'automation:workflow:node:list': 'nav.items.workflowNodes',
  'automation:workflow:node:create': 'workflowNodes.add',
  'automation:workflow:node:import': 'workflowNodes.add',
  'automation:workflow:node:update': 'workflowNodes.edit',
  'automation:workflow:node:delete': 'common.delete',
  'automation:workflow:node:docs': 'nav.items.workflowNodeDocs',
  'automation:workflow:canvas:list': 'nav.items.workflowCanvas',
  'automation:workflow:canvas:create': 'workflowCanvas.add',
  'automation:workflow:canvas:update': 'common.edit',
  'automation:workflow:canvas:delete': 'common.delete',
  'automation:workflow:canvas:publish': 'workflowCanvas.publish',
  'automation:workflow:canvas:execute': 'workflowCanvas.run',
  'automation:workflow:canvas:logs': 'workflowCanvas.logs'
})

const menuKeysByPath = Object.freeze({
  '/ai': 'nav.items.ai',
  '/ai-chat': 'nav.items.aiChat',
  '/operations': 'nav.items.operations',
  '/operations/monitoring': 'nav.items.monitoring',
  '/system': 'nav.items.system',
  '/system/access': 'nav.items.access',
  '/system/organization': 'nav.items.organization',
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
  '/data-sources': 'nav.items.dataSources',
  '/data-sync': 'nav.items.dataSync',
  '/servers': 'nav.items.servers',
  '/api-keys': 'nav.items.apiKeys',
  '/mail': 'nav.items.mail',
  '/mail/accounts': 'nav.items.mailAccounts',
  '/mail/routes': 'nav.items.mailRoutes',
  '/models': 'nav.items.models',
  '/model-providers': 'nav.items.providers',
  '/model-routes': 'nav.items.routes',
  '/knowledge-bases': 'nav.items.knowledgeBases',
  '/automation': 'nav.items.automation',
  '/automation/api-triggers': 'nav.items.apiTriggers',
  '/automation/api-trigger-security': 'nav.items.apiTriggerSecurity',
  '/automation/device-agents': 'nav.items.deviceAgents',
  '/workflow': 'nav.items.workflow',
  '/workflow/nodes': 'nav.items.workflowNodes',
  '/workflow/node-docs': 'nav.items.workflowNodeDocs',
  '/workflow/canvases': 'nav.items.workflowCanvas'
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
