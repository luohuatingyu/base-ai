/** 将带 parentId 的扁平列表转换为树结构。 */
export function buildTree(items) {
  const nodes = new Map(items.map(item => [item.id, { ...item, children: [] }]))
  const roots = []
  nodes.forEach(node => {
    const parent = nodes.get(node.parentId)
    if (parent) parent.children.push(node)
    else roots.push(node)
  })
  const sort = list => list.sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0)).forEach(item => sort(item.children))
  sort(roots)
  return roots
}
