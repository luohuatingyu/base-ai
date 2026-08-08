import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { reactive } from 'vue'
import { cloneWorkflowData, createWorkflowGraph, validateWorkflowGraph } from '../src/utils/workflowGraph.js'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const canvasViewSource = readFileSync(new URL('../src/views/WorkflowCanvasView.vue', import.meta.url), 'utf8')
const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')
const globalStyles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')

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

test('画布自身的 v-model 回显不会重置撤销重做历史', () => {
  assert.match(canvasViewSource, /<WorkflowGraphEditor[^>]*:history-key="selected\.id"/)
  assert.match(graphEditorSource, /historyKey:\s*\{\s*type:\s*\[String,\s*Number\],\s*default:\s*null\s*\}/)
  assert.match(graphEditorSource, /import\s*\{[^}]*toRaw[^}]*\}\s*from\s*'vue'/)
  assert.match(graphEditorSource, /let\s+lastEmittedModel/)
  assert.match(graphEditorSource, /const\s+historyKeyChanged\s*=\s*historyKey\s*!==\s*previousHistoryKey/)
  assert.match(graphEditorSource, /if\s*\(toRaw\(value\)\s*===\s*lastEmittedModel\)\s*return/)
  assert.match(graphEditorSource, /lastEmittedModel\s*=\s*nextModel;\s*emit\('update:modelValue',\s*nextModel\)/)
  assert.match(graphEditorSource, /history\.value\s*=\s*\[next\];\s*historyIndex\.value\s*=\s*0/)
  assert.match(graphEditorSource, /history\.value\s*=\s*\[\.\.\.history\.value\.slice\(0,\s*historyIndex\.value\s*\+\s*1\),\s*snapshot\]/)
})

test('画布提供应用内最大化和浏览器原生全屏入口', () => {
  assert.match(graphEditorSource, /ref="editorShell"[^>]*:class="\{[^}]*maximized:\s*isMaximized/)
  assert.match(graphEditorSource, /workflow-display-actions[\s\S]*workflowCanvas\.maximize[\s\S]*workflowCanvas\.fullscreen/)
  assert.match(graphEditorSource, /:disabled="!fullscreenSupported"/)
  assert.match(graphEditorSource, /editorShell\.value\?\.requestFullscreen\(\)/)
  assert.match(graphEditorSource, /document\.exitFullscreen\(\)/)
  assert.match(graphEditorSource, /const\s+wasMaximized\s*=\s*isMaximized\.value/)
  assert.match(graphEditorSource, /catch\s*\{\s*isMaximized\.value\s*=\s*wasMaximized;\s*showFullscreenWarning\(\)\s*\}/)
  assert.match(graphEditorSource, /appendTo:\s*editorShell\.value/)
  assert.match(graphEditorSource, /document\.addEventListener\('fullscreenchange',\s*syncFullscreenState\)/)
  assert.match(graphEditorSource, /\.workflow-editor-shell\.maximized,\s*\.workflow-editor-shell:fullscreen\s*\{[^}]*position:\s*fixed[^}]*inset:\s*0[^}]*height:\s*100vh/)
  assert.equal(zhCN.workflowCanvas.maximize, '最大化')
  assert.equal(zhCN.workflowCanvas.fullscreen, '全屏')
  assert.equal(enUS.workflowCanvas.maximize, 'Maximize')
  assert.equal(enUS.workflowCanvas.fullscreen, 'Fullscreen')
})

test('主工作流画布填满工具栏下方的剩余空间', () => {
  assert.match(canvasViewSource, /<WorkflowGraphEditor[^>]*\sfill\s*\/>/)
  assert.match(graphEditorSource, /fill:\s*\{\s*type:\s*Boolean,\s*default:\s*false\s*\}/)
  assert.match(graphEditorSource, /\.workflow-editor-shell\.fill\s*\{[^}]*flex:\s*1[^}]*height:\s*auto[^}]*min-height:\s*0/)
  assert.match(globalStyles, /\.workflow-canvas-page\s*\{[^}]*height:\s*calc\(100vh\s*-\s*124px\)[^}]*min-height:\s*0/)
  assert.match(globalStyles, /\.workflow-list-panel,\s*\.workflow-design-panel\s*\{[^}]*display:\s*flex[^}]*flex-direction:\s*column[^}]*min-height:\s*0/)
  assert.match(globalStyles, /\.workflow-list\s*\{[^}]*flex:\s*1[^}]*min-height:\s*0/)
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
