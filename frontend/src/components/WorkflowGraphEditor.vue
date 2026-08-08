<template>
  <div ref="editorShell" class="workflow-editor-shell" :class="{ 'has-properties': selected, fill: props.fill, maximized: isMaximized }">
    <div class="workflow-flow">
      <VueFlow :id="editorId" v-model:nodes="nodes" v-model:edges="edges" :node-types="nodeTypes"
               fit-view-on-init @node-click="selectNode" @pane-click="clearSelection"
               @pane-context-menu="openTemplateMenu"
               @node-drag-stop="remember" @connect="connect">
        <Background pattern-color="#cbd5e1" :gap="20" />
        <Controls />
        <div class="workflow-history-actions">
          <el-button size="small" :disabled="!canUndo" @click.stop="undo">{{ t('workflowCanvas.undo') }}</el-button>
          <el-button size="small" :disabled="!canRedo" @click.stop="redo">{{ t('workflowCanvas.redo') }}</el-button>
        </div>
        <div class="workflow-display-actions">
          <el-button size="small" @click.stop="toggleMaximized">{{ t(isMaximized ? 'workflowCanvas.restore' : 'workflowCanvas.maximize') }}</el-button>
          <el-button size="small" :disabled="!fullscreenSupported" @click.stop="toggleFullscreen">{{ t(isFullscreen ? 'workflowCanvas.exitFullscreen' : 'workflowCanvas.fullscreen') }}</el-button>
        </div>
      </VueFlow>
    </div>
    <aside v-if="selected" class="workflow-properties">
      <div class="workflow-properties-head"><strong>{{ t('workflowCanvas.properties') }}</strong><el-button text @click="selectedId=''">×</el-button></div>
      <el-form label-position="top">
        <el-form-item :label="t('common.name')"><el-input v-model="selected.data.label" @change="remember" /></el-form-item>
        <el-form-item :label="t('workflowCanvas.nodeConfig')"><WorkflowNodeConfigEditor :model-value="selected.data.config" :node-type="selected.type" @update:model-value="updateSelectedConfig" /></el-form-item>
        <el-button v-if="['ITERATION','LOOP'].includes(selected.type)" class="full" @click="openSubgraph">
          {{ t('workflowCanvas.editSubgraph') }}
        </el-button>
        <el-button v-if="!['START','END'].includes(selected.type)" class="full" type="danger" plain @click="removeSelected">
          {{ t('workflowCanvas.deleteNode') }}
        </el-button>
      </el-form>
    </aside>

    <div v-if="templateMenu.visible" ref="templateMenuElement" class="workflow-template-menu"
         :style="{ left: `${templateMenu.left}px`, top: `${templateMenu.top}px` }"
         @pointerdown.stop @contextmenu.prevent>
      <div class="workflow-template-menu-head">
        <strong>{{ t('workflowCanvas.nodeMenu') }}</strong>
        <button type="button" :aria-label="t('common.close')" @click="closeTemplateMenu">×</button>
      </div>
      <div v-if="templateGroups.length" class="workflow-template-menu-body">
        <nav class="workflow-template-categories" :aria-label="t('workflowCanvas.categories')">
          <button v-for="group in templateGroups" :key="group.category" type="button"
                  :class="{ active: activeCategory === group.category }" @click="activeCategory = group.category">
            <span>{{ t(`workflowCatalog.categories.${group.category}`) }}</span><small>{{ group.items.length }}</small>
          </button>
        </nav>
        <div class="workflow-template-items">
          <button v-for="template in activeTemplates" :key="template.id" type="button" @click="addTemplateFromMenu(template)">
            <span class="workflow-template-item-icon">{{ template.nodeType.slice(0, 2) }}</span>
            <span class="workflow-template-item-label"><strong>{{ template.name }}</strong><small>{{ template.nodeType }}</small></span>
            <span class="workflow-template-source" :class="`source-${template.source.toLowerCase()}`">{{ t(`workflowCatalog.sources.${template.source}`) }}</span>
          </button>
        </div>
      </div>
      <el-empty v-else :description="t('workflowCanvas.noTemplates')" :image-size="52" />
    </div>

    <el-dialog v-model="subgraphVisible" :title="t('workflowCanvas.subgraph')" width="min(1180px, 96vw)" append-to-body>
      <WorkflowGraphEditor v-if="subgraphVisible" v-model="subgraph" :templates="templates" :height="430" />
      <template #footer>
        <el-button @click="subgraphVisible=false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="saveSubgraph">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onBeforeUnmount, onMounted, reactive, ref, toRaw, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { Background } from '@vue-flow/background'
import { Controls } from '@vue-flow/controls'
import { VueFlow, useVueFlow } from '@vue-flow/core'
import WorkflowNode from './WorkflowNode.vue'
import WorkflowNodeConfigEditor from './WorkflowNodeConfigEditor.vue'
import { cloneWorkflowData, createWorkflowGraph, serializeWorkflowGraph, workflowElementId } from '../utils/workflowGraph'
import { WORKFLOW_NODE_TYPES } from '../utils/workflowNodeConfig'
import { groupWorkflowTemplates } from '../utils/workflowTemplateCatalog'

defineOptions({ name: 'WorkflowGraphEditor' })
const props = defineProps({ modelValue: { type: Object, required: true }, templates: { type: Array, default: () => [] }, height: { type: Number, default: 620 }, fill: { type: Boolean, default: false }, historyKey: { type: [String, Number], default: null } })
const emit = defineEmits(['update:modelValue'])
const { t } = useI18n()
const editorId = workflowElementId('editor')
const nodeTypes = Object.fromEntries(WORKFLOW_NODE_TYPES.map(type => [type, WorkflowNode]))
const initial = serializeWorkflowGraph(props.modelValue)
const nodes = ref(initial.nodes)
const edges = ref(initial.edges)
const selectedId = ref('')
const editorShell = ref(null)
const isMaximized = ref(false)
const isFullscreen = ref(false)
const fullscreenSupported = ref(false)
const subgraphVisible = ref(false)
const subgraph = ref(createWorkflowGraph())
const history = ref([serializeWorkflowGraph(initial)])
const historyIndex = ref(0)
const activeCategory = ref('')
const templateMenuElement = ref(null)
const templateMenu = reactive({ visible: false, left: 0, top: 0, flowPosition: { x: 0, y: 0 } })
const { addEdges, screenToFlowCoordinate } = useVueFlow({ id: editorId })
let configHistoryTimer
let lastEmittedModel
const selected = computed(() => nodes.value.find(node => node.id === selectedId.value))
const enabledTemplates = computed(() => props.templates.filter(template => template.enabled))
const templateGroups = computed(() => groupWorkflowTemplates(enabledTemplates.value))
const activeTemplates = computed(() => templateGroups.value.find(group => group.category === activeCategory.value)?.items || [])
const canUndo = computed(() => historyIndex.value > 0)
const canRedo = computed(() => historyIndex.value < history.value.length - 1)

watch([() => props.historyKey, () => props.modelValue], ([historyKey, value], [previousHistoryKey]) => {
  const historyKeyChanged = historyKey !== previousHistoryKey
  if (!historyKeyChanged) {
    if (toRaw(value) === lastEmittedModel) return
  }
  const next = serializeWorkflowGraph(value)
  if (!historyKeyChanged && JSON.stringify(next) === JSON.stringify(serializeWorkflowGraph({ nodes: nodes.value, edges: edges.value }))) return
  nodes.value = next.nodes; edges.value = next.edges
  history.value = [next]; historyIndex.value = 0; selectedId.value = ''
}, { deep: true })
watch([nodes, edges], () => {
  const nextModel = serializeWorkflowGraph({ nodes: nodes.value, edges: edges.value })
  lastEmittedModel = nextModel; emit('update:modelValue', nextModel)
}, { deep: true })

/** 在空白画布右键坐标打开功能分类节点菜单。 */
function openTemplateMenu(event) {
  event.preventDefault()
  templateMenu.flowPosition = screenToFlowCoordinate({ x: event.clientX, y: event.clientY })
  templateMenu.left = event.clientX
  templateMenu.top = event.clientY
  templateMenu.visible = true
  activeCategory.value = templateGroups.value[0]?.category || ''
  nextTick(clampTemplateMenu)
}
/** 将右键菜单限制在浏览器可视区域内。 */
function clampTemplateMenu() {
  const bounds = templateMenuElement.value?.getBoundingClientRect()
  if (!bounds) return
  templateMenu.left = Math.max(8, Math.min(templateMenu.left, window.innerWidth - bounds.width - 8))
  templateMenu.top = Math.max(8, Math.min(templateMenu.top, window.innerHeight - bounds.height - 8))
}
/** 单击模板后在原始右键坐标创建节点并关闭菜单。 */
function addTemplateFromMenu(template) {
  addNode(template, { ...templateMenu.flowPosition })
  closeTemplateMenu()
}
/** 关闭节点模板菜单。 */
function closeTemplateMenu() { templateMenu.visible = false }
/** 用模板快照创建独立画布实例。 */
function addNode(template, position) {
  nodes.value.push({ id: workflowElementId('node'), type: template.nodeType, templateId: template.id, position,
    data: { label: template.name, nodeType: template.nodeType, config: cloneWorkflowData(template.config || {}) } })
  remember()
}
/** 接受用户连线并生成稳定 ID。 */
function connect(connection) { addEdges([{ ...connection, id: workflowElementId('edge') }]); nextTick(remember) }
/** 选中节点并展示实例配置。 */
function selectNode({ node }) { closeTemplateMenu(); selectedId.value = node.id }
/** 点击画布空白区域时清除选择和临时菜单。 */
function clearSelection() { closeTemplateMenu(); selectedId.value = '' }
/** 更新可视化节点配置，并合并连续输入产生的历史快照。 */
function updateSelectedConfig(value) { selected.value.data.config = value; clearTimeout(configHistoryTimer); configHistoryTimer = setTimeout(remember, 350) }
/** 删除当前节点及其关联边。 */
function removeSelected() {
  const id = selectedId.value
  nodes.value = nodes.value.filter(node => node.id !== id)
  edges.value = edges.value.filter(edge => edge.source !== id && edge.target !== id)
  selectedId.value = ''; remember()
}
/** 退出画布显示模式，确保 Teleport 弹窗不会被全屏顶层遮挡。 */
async function leaveDisplayMode() {
  isMaximized.value = false
  if (document.fullscreenElement !== editorShell.value) return true
  try { await document.exitFullscreen(); return true } catch { showFullscreenWarning(); return false }
}
/** 在画布内部展示全屏错误，兼容原生全屏顶层限制。 */
function showFullscreenWarning() {
  ElMessage.warning({ message: t('workflowCanvas.fullscreenFailed'), appendTo: editorShell.value })
}
/** 切换覆盖应用内容区的最大化画布。 */
async function toggleMaximized() {
  closeTemplateMenu()
  if (isFullscreen.value && !await leaveDisplayMode()) return
  isMaximized.value = !isMaximized.value
}
/** 切换浏览器原生全屏，失败时保留当前显示模式。 */
async function toggleFullscreen() {
  if (!fullscreenSupported.value) return
  closeTemplateMenu()
  const wasMaximized = isMaximized.value
  try {
    if (isFullscreen.value) await document.exitFullscreen()
    else { isMaximized.value = false; await editorShell.value?.requestFullscreen() }
  } catch { isMaximized.value = wasMaximized; showFullscreenWarning() }
}
/** 根据浏览器全屏事件同步按钮文案和退出状态。 */
function syncFullscreenState() { isFullscreen.value = document.fullscreenElement === editorShell.value }
/** 打开迭代或循环节点的嵌套子画布。 */
async function openSubgraph() { if (!await leaveDisplayMode()) return; subgraph.value = serializeWorkflowGraph(selected.value.data.config?.bodyGraph || createWorkflowGraph()); subgraphVisible.value = true }
/** 保存嵌套子画布到当前控制节点配置。 */
function saveSubgraph() { selected.value.data.config = { ...(selected.value.data.config || {}), bodyGraph: serializeWorkflowGraph(subgraph.value) }; subgraphVisible.value = false; remember() }
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
/** 处理全局取消操作，避免菜单在失焦后残留。 */
function handleGlobalPointer() { closeTemplateMenu() }
/** Escape 优先关闭节点菜单，否则退出应用内最大化。 */
function handleGlobalKey(event) {
  if (event.key !== 'Escape') return
  if (templateMenu.visible) closeTemplateMenu()
  else isMaximized.value = false
}
onMounted(() => {
  fullscreenSupported.value = Boolean(document.fullscreenEnabled && editorShell.value?.requestFullscreen)
  document.addEventListener('pointerdown', handleGlobalPointer)
  document.addEventListener('fullscreenchange', syncFullscreenState)
  window.addEventListener('keydown', handleGlobalKey)
  window.addEventListener('resize', closeTemplateMenu)
})
onBeforeUnmount(() => {
  clearTimeout(configHistoryTimer)
  document.removeEventListener('pointerdown', handleGlobalPointer)
  document.removeEventListener('fullscreenchange', syncFullscreenState)
  window.removeEventListener('keydown', handleGlobalKey)
  window.removeEventListener('resize', closeTemplateMenu)
})
</script>

<style scoped>
.workflow-editor-shell { position: relative; height: v-bind('`${height}px`'); display: grid; grid-template-columns: minmax(0, 1fr); overflow: hidden; border: 1px solid var(--app-border); border-radius: 12px; background: #fff; }
.workflow-editor-shell.fill { flex: 1; height: auto; min-height: 0; }
.workflow-editor-shell.maximized,
.workflow-editor-shell:fullscreen { position: fixed; inset: 0; z-index: 3100; width: 100vw; height: 100vh; min-height: 0; border: 0; border-radius: 0; }
.workflow-editor-shell.has-properties { grid-template-columns: minmax(0, 1fr) 360px; }
.workflow-properties { min-width: 0; overflow: auto; padding: 14px; background: #f8fafc; }
.workflow-properties { border-left: 1px solid var(--app-border); }
.workflow-flow { position: relative; min-width: 0; min-height: 0; background: #f8fafc; }
.workflow-properties-head { display: flex; align-items: center; justify-content: space-between; margin-bottom: 12px; }
.workflow-properties :deep(.workflow-config-grid), .workflow-properties :deep(.workflow-extra-add) { grid-template-columns: 1fr; }
.workflow-history-actions { position: absolute; top: 12px; left: 12px; z-index: 4; display: flex; gap: 4px; }
.workflow-display-actions { position: absolute; top: 12px; right: 12px; z-index: 4; display: flex; gap: 4px; }
.workflow-template-menu { position: fixed; z-index: 3200; width: min(640px, calc(100vw - 16px)); overflow: hidden; border: 1px solid #d8e1ee; border-radius: 14px; background: #fff; box-shadow: 0 20px 56px rgb(15 23 42 / 24%); }
.workflow-template-menu-head { display: flex; align-items: center; justify-content: space-between; padding: 12px 14px; border-bottom: 1px solid #edf1f6; }
.workflow-template-menu-head button { border: 0; color: var(--app-muted); background: transparent; font-size: 20px; cursor: pointer; }
.workflow-template-menu-body { display: grid; grid-template-columns: 190px minmax(0, 1fr); height: min(430px, calc(100vh - 100px)); }
.workflow-template-categories { overflow-y: auto; padding: 8px; border-right: 1px solid #edf1f6; background: #f8fafc; }
.workflow-template-categories button { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 8px; margin-bottom: 3px; padding: 9px 10px; border: 0; border-radius: 8px; color: #475569; background: transparent; text-align: left; cursor: pointer; }
.workflow-template-categories button.active { color: #1d4ed8; background: #eaf1ff; font-weight: 700; }
.workflow-template-categories small { color: var(--app-muted); }
.workflow-template-items { overflow-y: auto; padding: 10px; }
.workflow-template-items > button { display: grid; width: 100%; grid-template-columns: 36px minmax(0, 1fr) auto; gap: 10px; align-items: center; margin-bottom: 7px; padding: 9px; border: 1px solid #e5eaf2; border-radius: 9px; color: inherit; background: #fff; text-align: left; cursor: pointer; }
.workflow-template-items > button:hover { border-color: #93b4ef; background: #f8fbff; }
.workflow-template-item-icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 9px; color: #315ea8; background: #eaf1ff; font-size: 11px; font-weight: 800; }
.workflow-template-item-label { display: flex; min-width: 0; flex-direction: column; gap: 3px; }
.workflow-template-item-label strong, .workflow-template-item-label small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-template-item-label small { color: var(--app-muted); }
.workflow-template-source { padding: 3px 7px; border-radius: 999px; color: #315ea8; background: #eaf1ff; font-size: 11px; font-weight: 700; }
.workflow-template-source.source-custom { color: #6846b7; background: #f0ebff; }
@media (max-width: 900px) { .workflow-editor-shell.has-properties { grid-template-columns: minmax(500px, 1fr); overflow: auto; } .workflow-properties { position: absolute; right: 16px; z-index: 4; width: min(360px, calc(100vw - 32px)); height: 540px; box-shadow: var(--app-shadow); } }
@media (max-width: 560px) { .workflow-template-menu-body { grid-template-columns: 130px minmax(0, 1fr); } }
</style>
