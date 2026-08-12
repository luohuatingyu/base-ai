import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { reactive } from 'vue'
import { cloneWorkflowData, createWorkflowGraph, localizeWorkflowStatus, removeWorkflowEdge, serializeWorkflowGraph, validateWorkflowGraph, withWorkflowEdgeInteractionWidth } from '../src/utils/workflowGraph.js'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const canvasViewSource = readFileSync(new URL('../src/views/WorkflowCanvasView.vue', import.meta.url), 'utf8')
const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')
const workflowNodeSource = readFileSync(new URL('../src/components/WorkflowNode.vue', import.meta.url), 'utf8')
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

test('悬停连线显示提示且左键点击立即删除并纳入撤销历史', () => {
  const source = [
    { id: 'first', source: 'start', target: 'http', interactionWidth: 20 },
    { id: 'second', source: 'http', target: 'end' }
  ]
  const remaining = removeWorkflowEdge(source, 'first')
  const runtimeEdges = withWorkflowEdgeInteractionWidth(source, 96)
  const serialized = serializeWorkflowGraph({ nodes: [], edges: runtimeEdges })

  assert.deepEqual(remaining, [{ id: 'second', source: 'http', target: 'end' }])
  assert.equal(source.length, 2)
  assert.deepEqual(removeWorkflowEdge(source, 'missing'), source)
  assert.deepEqual(removeWorkflowEdge(null, 'first'), [])
  assert.deepEqual(runtimeEdges.map(edge => edge.interactionWidth), [96, 96])
  assert.equal(source[0].interactionWidth, 20)
  assert.ok(serialized.edges.every(edge => !Object.prototype.hasOwnProperty.call(edge, 'interactionWidth')))
  assert.equal(withWorkflowEdgeInteractionWidth([{ id: 'fallback' }], 0)[0].interactionWidth, 20)
  assert.match(graphEditorSource, /:default-edge-options="edgeOptions"/)
  assert.match(graphEditorSource, /EDGE_INTERACTION_WIDTH\s*=\s*32/)
  assert.match(graphEditorSource, /withWorkflowEdgeInteractionWidth\(initial\.edges, EDGE_INTERACTION_WIDTH\)/)
  assert.match(graphEditorSource, /withWorkflowEdgeInteractionWidth\(next\.edges, EDGE_INTERACTION_WIDTH\)/)
  assert.match(graphEditorSource, /withWorkflowEdgeInteractionWidth\(graph\.edges, EDGE_INTERACTION_WIDTH\)/)
  assert.match(graphEditorSource, /interactionWidth:\s*EDGE_INTERACTION_WIDTH/)
  assert.match(graphEditorSource, /vue-flow__edge-interaction\)\s*\{[^}]*stroke:\s*transparent[^}]*pointer-events:\s*stroke/)
  assert.match(graphEditorSource, /@edge-mouse-enter="showEdgeDeleteHint"\s+@edge-mouse-move="moveEdgeDeleteHint"/)
  assert.match(graphEditorSource, /@edge-mouse-leave="hideEdgeDeleteHint"\s+@edge-click="deleteEdge"/)
  assert.match(graphEditorSource, /workflow-edge-delete-hint[\s\S]*workflowCanvas\.deleteEdgeHint/)
  assert.match(graphEditorSource, /function deleteEdge\(\{ event, edge \}\)[\s\S]*event\.stopPropagation\(\)[\s\S]*removeWorkflowEdge\(edges\.value, edge\.id\)[\s\S]*remember\(\)/)
  assert.match(graphEditorSource, /vue-flow__edge:hover \.vue-flow__edge-path/)
  assert.doesNotMatch(graphEditorSource, /@edge-context-menu|workflow-edge-menu|openEdgeMenu/)
  assert.equal(zhCN.workflowCanvas.deleteEdgeHint, '点击删除连线')
  assert.equal(enUS.workflowCanvas.deleteEdgeHint, 'Click to delete edge')
})

test('画布节点实时显示缺失必填配置及字段提示', () => {
  assert.match(workflowNodeSource, /missingNodeConfigRequirements\(nodeType\.value, props\.data\?\.config\)/)
  assert.match(workflowNodeSource, /has-missing-config/)
  assert.match(workflowNodeSource, /v-if="missingRequirements\.length"[\s\S]*:title="missingHint"/)
  assert.match(workflowNodeSource, /workflowConfig\.requiredMissing/)
  assert.match(workflowNodeSource, /workflowConfig\.requirementLabels/)
})

test('画布节点仅使用功能类型图标配色并兼容历史节点', () => {
  assert.match(workflowNodeSource, /class="workflow-node-icon"\s+:style="categoryStyle"/)
  assert.match(workflowNodeSource, /workflowTemplateCategoryStyle\(props\.data\?\.functionalCategory, nodeType\.value\)/)
  assert.match(workflowNodeSource, /\.workflow-node\s*\{[^}]*background:\s*#fff/)
  assert.doesNotMatch(workflowNodeSource, /\.workflow-node--start\s*\{[^}]*background:/)
  assert.match(workflowNodeSource, /\.workflow-node\.has-missing-config\s*\{[^}]*background:\s*var\(--el-color-danger-light-9\)/)
})

test('画布端点在宽松命中范围内提供连线反馈', () => {
  assert.match(graphEditorSource, /:connection-radius="CONNECTION_RADIUS"/)
  assert.match(graphEditorSource, /const CONNECTION_RADIUS = 32/)
  assert.match(workflowNodeSource, /:deep\(\.vue-flow__handle\)\s*\{[^}]*width:\s*6px[^}]*height:\s*6px[^}]*border:\s*0[^}]*background:\s*transparent/)
  assert.match(workflowNodeSource, /:deep\(\.vue-flow__handle::before\)\s*\{[^}]*width:\s*40px[^}]*height:\s*40px[^}]*background:\s*transparent[^}]*pointer-events:\s*auto/)
  assert.match(workflowNodeSource, /:deep\(\.vue-flow__handle::after\)\s*\{[^}]*width:\s*6px[^}]*height:\s*6px[^}]*border-radius:\s*50%/)
  assert.match(workflowNodeSource, /:deep\(\.vue-flow__handle:hover::after\)[\s\S]*:deep\(\.vue-flow__handle\.connecting::after\)[\s\S]*box-shadow:/)
  assert.match(workflowNodeSource, /:deep\(\.vue-flow__handle\.valid::after\)\s*\{[^}]*background:/)
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
  assert.deepEqual(graph.nodes.map(node => node.data.label), ['START', 'END'])
  assert.equal(graph.edges.length, 1)
  assert.match(workflowNodeSource, /localizedWorkflowNodeLabel/)
})

test('工作流画布拒绝悬空连线和普通循环', () => {
  const dangling = {
    nodes: [{ id: 'start', type: 'START' }, { id: 'end', type: 'END' }],
    edges: [{ id: 'edge', source: 'start', target: 'missing' }]
  }
  assert.equal(validateWorkflowGraph(dangling), 'edgeMissing')

  const cyclic = {
    nodes: [{ id: 'start', type: 'START' }, { id: 'llm', type: 'LLM' }, { id: 'end', type: 'END' }],
    edges: [
      { id: 'a', source: 'start', target: 'llm' },
      { id: 'b', source: 'llm', target: 'start' },
      { id: 'c', source: 'llm', target: 'end' }
    ]
  }
  assert.equal(validateWorkflowGraph(cyclic), 'cycle')
})

test('画布校验错误和运行状态完整支持双语且未知状态安全回退', () => {
  const translate = locale => key => key.split('.').reduce((value, part) => value?.[part], locale) || key
  assert.equal(validateWorkflowGraph(null), 'graphInvalid')
  assert.equal(zhCN.workflowCanvas.graphErrors.graphInvalid, '画布结构无效')
  assert.equal(enUS.workflowCanvas.graphErrors.graphInvalid, 'The canvas structure is invalid')
  for (const status of ['DRAFT', 'PUBLISHED', 'QUEUED', 'RUNNING', 'WAITING', 'SUCCESS', 'FAILED', 'FAILED_CONTINUED', 'CANCELLED']) {
    assert.notEqual(localizeWorkflowStatus(status, translate(zhCN)), status)
    assert.notEqual(localizeWorkflowStatus(status, translate(enUS)), status)
  }
  assert.equal(localizeWorkflowStatus('FUTURE_STATUS', translate(zhCN)), 'FUTURE_STATUS')
  assert.match(canvasViewSource, /localizeWorkflowStatus\(item\.status, t\)/)
  assert.match(canvasViewSource, /localizeWorkflowStatus\(activeRun\.status, t\)/)
  assert.match(canvasViewSource, /:label="t\('workflowCanvas\.runId'\)"/)
  assert.match(canvasViewSource, /:label="t\('workflowCanvas\.version'\)"/)
})

test('历史运行节点名仅在保留默认名称身份时按当前语言展示', () => {
  assert.match(canvasViewSource, /runNodeName\(scope\.row\)/)
  assert.match(canvasViewSource, /defaultLabel: node\.defaultNodeName/)
  assert.match(canvasViewSource, /localization: node\.localization/)
  assert.match(canvasViewSource, /localizedWorkflowNodeLabel/)
})
