<template>
  <div class="workflow-editor-shell">
    <aside class="workflow-palette">
      <strong>{{ t('workflowCanvas.palette') }}</strong>
      <button v-for="template in enabledTemplates" :key="template.id" draggable="true"
              @dragstart="startDrag($event, template)" @click="addTemplate(template)">
        <span>{{ template.name }}</span><small>{{ template.nodeType }}</small>
      </button>
    </aside>
    <div class="workflow-flow" @drop="drop" @dragover.prevent>
      <VueFlow :id="editorId" v-model:nodes="nodes" v-model:edges="edges" :node-types="nodeTypes"
               fit-view-on-init @node-click="selectNode" @pane-click="selectedId = ''"
               @node-drag-stop="remember" @connect="connect">
        <Background pattern-color="#cbd5e1" :gap="20" />
        <Controls />
      </VueFlow>
    </div>
    <aside v-if="selected" class="workflow-properties">
      <div class="workflow-properties-head"><strong>{{ t('workflowCanvas.properties') }}</strong><el-button text @click="selectedId=''">×</el-button></div>
      <el-form label-position="top">
        <el-form-item :label="t('common.name')"><el-input v-model="selected.data.label" @change="remember" /></el-form-item>
        <el-form-item :label="t('workflowCanvas.nodeConfig')">
          <el-input v-model="configText" type="textarea" :rows="14" spellcheck="false" @blur="applyConfig" />
        </el-form-item>
        <el-button v-if="['ITERATION','LOOP'].includes(selected.type)" class="full" @click="openSubgraph">
          {{ t('workflowCanvas.editSubgraph') }}
        </el-button>
        <el-button v-if="!['START','END'].includes(selected.type)" class="full" type="danger" plain @click="removeSelected">
          {{ t('workflowCanvas.deleteNode') }}
        </el-button>
      </el-form>
    </aside>

    <el-dialog v-model="subgraphVisible" :title="t('workflowCanvas.subgraph')" width="min(1180px, 96vw)" append-to-body>
      <WorkflowGraphEditor v-if="subgraphVisible" ref="subgraphEditor" v-model="subgraph" :templates="templates" :height="430" />
      <template #footer>
        <el-button :disabled="!subgraphEditor?.canUndo" @click="subgraphEditor?.undo()">{{ t('workflowCanvas.undo') }}</el-button>
        <el-button :disabled="!subgraphEditor?.canRedo" @click="subgraphEditor?.redo()">{{ t('workflowCanvas.redo') }}</el-button>
        <el-button @click="subgraphVisible=false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="saveSubgraph">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import WorkflowNode from './WorkflowNode.vue'
import { cloneWorkflowData, createWorkflowGraph, serializeWorkflowGraph, workflowElementId } from '../utils/workflowGraph'

defineOptions({ name: 'WorkflowGraphEditor' })
const props = defineProps({ modelValue: { type: Object, required: true }, templates: { type: Array, default: () => [] }, height: { type: Number, default: 620 } })
const emit = defineEmits(['update:modelValue'])
const { t } = useI18n()
const editorId = workflowElementId('editor')
const nodeTypes = Object.fromEntries(['START', 'END', 'LLM', 'HTTP', 'AGENT', 'CONDITION', 'ITERATION', 'LOOP'].map(type => [type, WorkflowNode]))
const initial = serializeWorkflowGraph(props.modelValue)
const nodes = ref(initial.nodes)
const edges = ref(initial.edges)
const selectedId = ref('')
const configText = ref('{}')
const subgraphVisible = ref(false)
const subgraph = ref(createWorkflowGraph())
const subgraphEditor = ref(null)
const history = ref([serializeWorkflowGraph(initial)])
const historyIndex = ref(0)
const { addEdges, screenToFlowCoordinate } = useVueFlow({ id: editorId })
const selected = computed(() => nodes.value.find(node => node.id === selectedId.value))
const enabledTemplates = computed(() => props.templates.filter(template => template.enabled))
const canUndo = computed(() => historyIndex.value > 0)
const canRedo = computed(() => historyIndex.value < history.value.length - 1)

watch(() => props.modelValue, value => {
  const next = serializeWorkflowGraph(value)
  if (JSON.stringify(next) === JSON.stringify(serializeWorkflowGraph({ nodes: nodes.value, edges: edges.value }))) return
  nodes.value = next.nodes; edges.value = next.edges
  history.value = [next]; historyIndex.value = 0; selectedId.value = ''
}, { deep: true })
watch([nodes, edges], () => emit('update:modelValue', serializeWorkflowGraph({ nodes: nodes.value, edges: edges.value })), { deep: true })
watch(selected, value => { configText.value = JSON.stringify(value?.data?.config || {}, null, 2) })

/** 记录拖拽模板信息。 */
function startDrag(event, template) { event.dataTransfer.setData('application/workflow-template', JSON.stringify(template)); event.dataTransfer.effectAllowed = 'move' }
/** 点击模板时放入递增位置，兼顾不支持拖放的设备。 */
function addTemplate(template) { addNode(template, { x: 180 + nodes.value.length * 24, y: 100 + nodes.value.length * 20 }) }
/** 将拖入位置转换为 Vue Flow 坐标并新增节点。 */
function drop(event) {
  const raw = event.dataTransfer.getData('application/workflow-template')
  if (!raw) return
  const bounds = event.currentTarget.getBoundingClientRect()
  addNode(JSON.parse(raw), screenToFlowCoordinate({ x: event.clientX, y: event.clientY }))
}
/** 用模板快照创建独立画布实例。 */
function addNode(template, position) {
  nodes.value.push({ id: workflowElementId('node'), type: template.nodeType, templateId: template.id, position,
    data: { label: template.name, nodeType: template.nodeType, config: cloneWorkflowData(template.config || {}) } })
  remember()
}
/** 接受用户连线并生成稳定 ID。 */
function connect(connection) { addEdges([{ ...connection, id: workflowElementId('edge') }]); nextTick(remember) }
/** 选中节点并展示实例配置。 */
function selectNode({ node }) { selectedId.value = node.id }
/** 解析配置 JSON，失败时保留编辑文本并提示。 */
function applyConfig() {
  try { selected.value.data.config = JSON.parse(configText.value || '{}'); remember() }
  catch { ElMessage.error(t('workflowCanvas.invalidJson')) }
}
/** 删除当前节点及其关联边。 */
function removeSelected() {
  const id = selectedId.value
  nodes.value = nodes.value.filter(node => node.id !== id)
  edges.value = edges.value.filter(edge => edge.source !== id && edge.target !== id)
  selectedId.value = ''; remember()
}
/** 打开迭代或循环节点的嵌套子画布。 */
function openSubgraph() { subgraph.value = serializeWorkflowGraph(selected.value.data.config?.bodyGraph || createWorkflowGraph()); subgraphVisible.value = true }
/** 保存嵌套子画布到当前控制节点配置。 */
function saveSubgraph() { selected.value.data.config = { ...(selected.value.data.config || {}), bodyGraph: serializeWorkflowGraph(subgraph.value) }; configText.value = JSON.stringify(selected.value.data.config, null, 2); subgraphVisible.value = false; remember() }
/** 保存一份可撤销快照并截断旧的重做分支。 */
function remember() {
  const snapshot = serializeWorkflowGraph({ nodes: nodes.value, edges: edges.value })
  const current = JSON.stringify(history.value[historyIndex.value])
  if (JSON.stringify(snapshot) === current) return
  history.value = [...history.value.slice(0, historyIndex.value + 1), snapshot].slice(-50)
  historyIndex.value = history.value.length - 1
}
/** 恢复上一个画布快照。 */
function undo() { if (canUndo.value) restore(historyIndex.value - 1) }
/** 恢复下一个画布快照。 */
function redo() { if (canRedo.value) restore(historyIndex.value + 1) }
/** 替换运行态节点和连线。 */
function restore(index) { historyIndex.value = index; const graph = serializeWorkflowGraph(history.value[index]); nodes.value = graph.nodes; edges.value = graph.edges; selectedId.value = '' }
defineExpose({ canUndo, canRedo, undo, redo })
</script>

<style scoped>
.workflow-editor-shell { height: v-bind('`${height}px`'); display: grid; grid-template-columns: 180px minmax(0, 1fr) 280px; overflow: hidden; border: 1px solid var(--app-border); border-radius: 12px; background: #fff; }
.workflow-palette, .workflow-properties { min-width: 0; overflow: auto; padding: 14px; background: #f8fafc; }
.workflow-palette { border-right: 1px solid var(--app-border); }
.workflow-properties { border-left: 1px solid var(--app-border); }
.workflow-palette > strong { display: block; margin-bottom: 12px; }
.workflow-palette > button { width: 100%; display: flex; flex-direction: column; gap: 3px; margin-bottom: 8px; padding: 9px 10px; border: 1px solid #dbe4f0; border-radius: 8px; background: white; text-align: left; cursor: grab; }
.workflow-palette small { color: var(--app-muted); }
.workflow-flow { min-width: 0; min-height: 0; background: #f8fafc; }
.workflow-properties-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
@media (max-width: 900px) { .workflow-editor-shell { grid-template-columns: 130px minmax(500px, 1fr); overflow: auto; } .workflow-properties { position: absolute; right: 16px; z-index: 4; width: 280px; height: 500px; box-shadow: var(--app-shadow); } }
</style>
