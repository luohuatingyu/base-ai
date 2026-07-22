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
