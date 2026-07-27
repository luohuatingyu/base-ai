const menuKeysByPermission = Object.freeze({
  'ai:catalog': 'nav.items.ai',
  'ai:chat:invoke': 'nav.items.aiChat',
  'system:catalog': 'nav.items.system',
  'system:user:list': 'nav.items.users',
  'system:role:list': 'nav.items.roles',
  'system:menu:list': 'nav.items.menus',
  'system:department:list': 'nav.items.departments',
  'system:position:list': 'nav.items.positions',
  'system:dictionary:list': 'nav.items.dictionaries',
  'system:setting:list': 'nav.items.settings',
  'system:session:list': 'nav.items.onlineUsers',
  'system:audit:operation:list': 'nav.items.operationLogs',
  'system:audit:login:list': 'nav.items.loginLogs',
  'system:task:view': 'nav.items.tasks',
  'system:api-key:list': 'nav.items.apiKeys',
  'model:catalog': 'nav.items.models',
  'model:provider:list': 'nav.items.providers',
  'model:model:list': 'nav.items.modelConfig',
  'model:route:list': 'nav.items.routes',
  'automation:catalog': 'nav.items.automation',
  'automation:api-trigger:list': 'nav.items.apiTriggers',
  'automation:api-trigger-security:view': 'nav.items.apiTriggerSecurity'
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
