import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { reactive } from 'vue'
import { cloneWorkflowData, createWorkflowGraph, validateWorkflowGraph } from '../src/utils/workflowGraph.js'

const canvasViewSource = readFileSync(new URL('../src/views/WorkflowCanvasView.vue', import.meta.url), 'utf8')
const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')

test('工作流 JSON 数据可安全复制 Vue 响应式对象', () => {
  const workflow = reactive({
    id: 1,
    graph: { nodes: [{ id: 'llm', data: { config: { prompt: 'original' } } }], edges: [] },
    inputSchema: { properties: { name: { type: 'string' } } }
  })

  assert.throws(() => structuredClone(workflow), error => error.name === 'DataCloneError')
  const copy = cloneWorkflowData(workflow)
  copy.graph.nodes[0].data.config.prompt = 'changed'
  copy.inputSchema.properties.name.type = 'number'

  assert.equal(workflow.graph.nodes[0].data.config.prompt, 'original')
  assert.equal(workflow.inputSchema.properties.name.type, 'string')
})

test('工作流选择和节点模板配置统一使用响应式安全复制', () => {
  assert.match(canvasViewSource, /selected\.value\s*=\s*item\s*\?\s*cloneWorkflowData\(item\)\s*:\s*null/)
  assert.match(graphEditorSource, /config:\s*cloneWorkflowData\(template\.config\s*\|\|\s*\{\}\)/)
  assert.doesNotMatch(`${canvasViewSource}\n${graphEditorSource}`, /structuredClone\(/)
})

test('撤销重做作为画布悬浮操作并复用编辑器历史状态', () => {
  assert.doesNotMatch(canvasViewSource, /ref="graphEditor"|workflow-toolbar-actions[\s\S]*workflowCanvas\.undo/)
  assert.match(graphEditorSource, /workflow-history-actions[\s\S]*workflowCanvas\.undo[\s\S]*workflowCanvas\.redo/)
  assert.match(graphEditorSource, /const canUndo = computed\(\(\) => historyIndex\.value > 0\)/)
  assert.match(graphEditorSource, /const canRedo = computed\(\(\) => historyIndex\.value < history\.value\.length - 1\)/)
  assert.match(graphEditorSource, /defineExpose\(\{ canUndo, canRedo, undo, redo \}\)/)
})

test('工作流画布初始化唯一开始和结束节点', () => {
  const graph = createWorkflowGraph()
  assert.deepEqual(graph.nodes.map(node => node.type), ['START', 'END'])
  assert.equal(graph.edges.length, 1)
})

test('工作流画布拒绝悬空连线和普通循环', () => {
  const dangling = {
    nodes: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END' }],
    edges: [{ id: 'edge', source: 'start', target: 'missing' }]
  }
  assert.match(validateWorkflowGraph(dangling), /target/)

  const cyclic = {
    nodes: [{ id: 'start', type: 'START' }, { id: 'llm', type: 'LLM' }, { id: 'end', type: 'END' }],
    edges: [
      { id: 'a', source: 'start', target: 'llm' },
      { id: 'b', source: 'llm', target: 'start' },
      { id: 'c', source: 'llm', target: 'end' }
    ]
  }
  assert.match(validateWorkflowGraph(cyclic), /cycle/)
})
