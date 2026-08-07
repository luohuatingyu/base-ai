/** 生成适用于浏览器和 Node 测试环境的画布元素 ID。 */
export function workflowElementId(prefix = 'item') {
  const random = globalThis.crypto?.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`
  return `${prefix}-${random}`
}

/** 创建包含唯一开始和结束节点的最小工作流。 */
export function createWorkflowGraph() {
  const start = { id: workflowElementId('start'), type: 'START', position: { x: 80, y: 160 }, data: { label: 'START', config: {} } }
  const end = { id: workflowElementId('end'), type: 'END', position: { x: 440, y: 160 }, data: { label: 'END', config: {} } }
  return { nodes: [start, end], edges: [{ id: workflowElementId('edge'), source: start.id, target: end.id }] }
}

/** 在前端快速检查结构错误，后端仍执行最终可信校验。 */
export function validateWorkflowGraph(graph, maxNodes = 100) {
  if (!graph || !Array.isArray(graph.nodes) || !Array.isArray(graph.edges)) return 'graph invalid'
  if (graph.nodes.length < 2 || graph.nodes.length > maxNodes) return 'node limit exceeded'
  const ids = new Set()
  const triggerTypes = new Set(['WEBHOOK_TRIGGER', 'SCHEDULE_TRIGGER', 'KAFKA_TRIGGER', 'RABBITMQ_TRIGGER'])
  let starts = 0
  let ends = 0
  for (const node of graph.nodes) {
    if (!node?.id || ids.has(node.id)) return 'node id invalid'
    ids.add(node.id)
    if (node.type === 'START' || triggerTypes.has(node.type)) starts += 1
    if (node.type === 'END') ends += 1
  }
  if (starts !== 1 || ends < 1) return 'start/end boundary invalid'
  const incoming = new Map([...ids].map(id => [id, 0]))
  const outgoing = new Map([...ids].map(id => [id, []]))
  for (const edge of graph.edges) {
    if (!edge?.id || !ids.has(edge.source) || !ids.has(edge.target)) return 'edge target or source missing'
    incoming.set(edge.target, incoming.get(edge.target) + 1)
    outgoing.get(edge.source).push(edge.target)
  }
  const queue = [...incoming].filter(([, count]) => count === 0).map(([id]) => id)
  let visited = 0
  while (queue.length) {
    const id = queue.shift()
    visited += 1
    for (const target of outgoing.get(id)) {
      incoming.set(target, incoming.get(target) - 1)
      if (incoming.get(target) === 0) queue.push(target)
    }
  }
  if (visited !== ids.size) return 'cycle detected'
  return ''
}

/** 深复制来自 JSON 接口的工作流数据，并兼容 Vue 响应式代理。 */
export function cloneWorkflowData(value) {
  return JSON.parse(JSON.stringify(value))
}

/** 深复制画布，避免 Vue Flow 运行态字段污染保存版本。 */
export function serializeWorkflowGraph(graph) {
  return cloneWorkflowData({ nodes: graph?.nodes || [], edges: graph?.edges || [] })
}
