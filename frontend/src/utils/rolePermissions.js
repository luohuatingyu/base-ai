import { buildTree } from './tree.js'

/** 将角色权限菜单转换为包含本地化标签的树节点。 */
export function buildRolePermissionTree(menus, labelResolver) {
  /** 递归补充树节点展示标签，同时保留后端权限字段。 */
  const decorate = node => ({
    ...node,
    label: labelResolver(node),
    children: node.children.map(decorate)
  })

  return buildTree(menus || []).map(decorate)
}

/** 按后端菜单顺序返回已选择权限，确保请求与回显结果稳定。 */
function orderedIds(menus, selected) {
  return (menus || []).filter(item => selected.has(item.id)).map(item => item.id)
}

/** 查找节点的全部有效上级，遇到错误循环时安全停止。 */
function ancestorsOf(node, nodes) {
  const ancestors = []
  const visited = new Set([node.id])
  let parent = nodes.get(node.parentId)
  while (parent && !visited.has(parent.id)) {
    ancestors.push(parent)
    visited.add(parent.id)
    parent = nodes.get(parent.parentId)
  }
  return ancestors
}

/** 查找节点的全部下级，用于取消页面或目录时清理依赖权限。 */
function descendantIds(nodeId, menus) {
  const childrenByParent = new Map()
  for (const item of menus || []) {
    const children = childrenByParent.get(item.parentId) || []
    children.push(item)
    childrenByParent.set(item.parentId, children)
  }
  const descendants = new Set()
  const pending = [...(childrenByParent.get(nodeId) || [])]
  while (pending.length) {
    const child = pending.shift()
    if (descendants.has(child.id)) continue
    descendants.add(child.id)
    pending.push(...(childrenByParent.get(child.id) || []))
  }
  return descendants
}

/** 规范化历史角色权限，为页面和合法按钮补齐全部上级节点。 */
export function normalizeRolePermissionIds(menus, selectedIds) {
  const nodes = new Map((menus || []).map(item => [item.id, item]))
  const selected = new Set()
  for (const id of selectedIds || []) {
    const node = nodes.get(id)
    if (!node) continue
    const ancestors = ancestorsOf(node, nodes)
    if (node.type === 'BUTTON' && !ancestors.some(item => item.type === 'MENU')) continue
    selected.add(node.id)
    if (node.type === 'MENU' || node.type === 'BUTTON') ancestors.forEach(item => selected.add(item.id))
  }
  return orderedIds(menus, selected)
}

/**
 * 按精确依赖规则更新角色权限。
 *
 * 勾选页面不会授予按钮；勾选按钮会补齐页面和目录；取消页面会清除其下按钮。
 */
export function updateRolePermissionSelection(menus, selectedIds, changedId, checked) {
  const nodes = new Map((menus || []).map(item => [item.id, item]))
  const node = nodes.get(changedId)
  const selected = new Set(normalizeRolePermissionIds(menus, selectedIds))
  if (!node) return orderedIds(menus, selected)

  if (checked) {
    const ancestors = ancestorsOf(node, nodes)
    if (node.type === 'BUTTON' && !ancestors.some(item => item.type === 'MENU')) {
      return orderedIds(menus, selected)
    }
    selected.add(node.id)
    if (node.type === 'MENU' || node.type === 'BUTTON') ancestors.forEach(item => selected.add(item.id))
  } else {
    selected.delete(node.id)
    if (node.type === 'MENU' || node.type === 'CATALOG') {
      const descendants = descendantIds(node.id, menus)
      for (const item of menus || []) {
        if (descendants.has(item.id) && (node.type === 'CATALOG' || item.type === 'BUTTON')) selected.delete(item.id)
      }
    }
  }

  return orderedIds(menus, selected)
}
