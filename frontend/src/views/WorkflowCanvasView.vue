<template>
  <div class="workflow-canvas-page">
    <section class="panel workflow-list-panel">
      <div class="workflow-list-heading"><strong>{{ t('workflowCanvas.workflows') }}</strong><el-button v-if="auth.hasPermission('workflow:canvas:create')" size="small" type="primary" @click="openCreate">+</el-button></div>
      <el-input v-model="keyword" clearable :placeholder="t('workflowCanvas.search')" />
      <div class="workflow-list">
        <button v-for="item in filteredRows" :key="item.id" :class="{ active: selected?.id === item.id }" @click="select(item)">
          <strong>{{ item.name }}</strong><small>{{ item.code }}</small><el-tag size="small" :type="item.status === 'PUBLISHED' ? 'success' : 'info'">{{ item.status }}</el-tag>
        </button>
      </div>
    </section>

    <section v-if="selected" class="panel workflow-design-panel">
      <div class="workflow-toolbar">
        <div><strong>{{ selected.name }}</strong><small>{{ selected.code }} · v{{ selected.currentVersion }}</small></div>
        <div class="workflow-toolbar-actions">
          <el-button v-if="auth.hasPermission('workflow:canvas:update')" @click="save">{{ t('common.save') }}</el-button>
          <el-button v-if="auth.hasPermission('workflow:canvas:publish')" type="success" @click="publish">{{ t('workflowCanvas.publish') }}</el-button>
          <el-button v-if="auth.hasPermission('workflow:canvas:execute')" type="primary" @click="runVisible=true">{{ t('workflowCanvas.run') }}</el-button>
          <el-button v-if="auth.hasPermission('workflow:canvas:logs')" @click="openRuns">{{ t('workflowCanvas.logs') }}</el-button>
          <el-dropdown><el-button>⋯</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="auth.hasPermission('workflow:canvas:delete')" @click="remove">{{ t('common.delete') }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown>
        </div>
      </div>
      <div class="workflow-metadata">
        <el-input v-model="selected.name" :placeholder="t('common.name')" />
        <el-input v-model="selected.description" :placeholder="t('common.description')" />
        <el-input v-model="inputSchemaText" :placeholder="t('workflowCanvas.inputSchema')" />
      </div>
      <WorkflowGraphEditor v-model="graph" :templates="templates" fill />
    </section>
    <section v-else class="panel workflow-empty"><el-empty :description="t('workflowCanvas.empty')" /></section>

    <el-dialog v-model="createVisible" :title="t('workflowCanvas.add')" width="min(560px, 94vw)">
      <el-form label-width="100px"><el-form-item :label="t('common.code')"><el-input v-model="createForm.code" /></el-form-item><el-form-item :label="t('common.name')"><el-input v-model="createForm.name" /></el-form-item><el-form-item :label="t('common.description')"><el-input v-model="createForm.description" /></el-form-item></el-form>
      <template #footer><el-button @click="createVisible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="createWorkflow">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="runVisible" :title="t('workflowCanvas.run')" width="min(680px, 94vw)">
      <el-input v-model="runInputText" type="textarea" :rows="10" spellcheck="false" />
      <el-alert v-if="activeRun" class="workflow-run-alert" :title="`${activeRun.status} · ${activeRun.id}`" :type="runAlertType" show-icon :closable="false" />
      <pre v-if="activeRun?.output"><code>{{ JSON.stringify(activeRun.output, null, 2) }}</code></pre>
      <template #footer><el-button v-if="activeRun && ['QUEUED','RUNNING','WAITING'].includes(activeRun.status)" type="danger" @click="cancelRun">{{ t('workflowCanvas.cancel') }}</el-button><el-button type="primary" :loading="running" @click="startRun">{{ t('workflowCanvas.start') }}</el-button></template>
    </el-dialog>

    <el-drawer v-model="runsVisible" :title="t('workflowCanvas.logs')" size="min(900px, 96vw)">
      <el-table :data="runs"><el-table-column prop="id" label="Run ID" min-width="290" /><el-table-column prop="versionNumber" label="Version" width="90" /><el-table-column prop="status" :label="t('common.status')" width="120" /><el-table-column prop="createdAt" :label="t('common.time')" min-width="170" /><el-table-column width="90"><template #default="scope"><el-button link type="primary" @click="openRun(scope.row.id)">{{ t('common.detail') }}</el-button></template></el-table-column></el-table>
      <div v-if="runDetail" class="workflow-run-detail"><h3>{{ runDetail.id }}</h3><pre><code>{{ JSON.stringify(runDetail.output || runDetail.errorMessage, null, 2) }}</code></pre><el-table :data="runDetail.nodes"><el-table-column prop="sequenceNo" label="#" width="60" /><el-table-column prop="nodeName" :label="t('common.name')" /><el-table-column prop="nodeType" :label="t('common.type')" width="110" /><el-table-column prop="status" :label="t('common.status')" width="110" /><el-table-column prop="iterationPath" :label="t('workflowCanvas.iteration')" /></el-table></div>
    </el-drawer>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import WorkflowGraphEditor from '../components/WorkflowGraphEditor.vue'
import { cloneWorkflowData, createWorkflowGraph, serializeWorkflowGraph, validateWorkflowGraph } from '../utils/workflowGraph'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([]), templates = ref([]), selected = ref(null), graph = ref(createWorkflowGraph())
const keyword = ref(''), inputSchemaText = ref('{}'), createVisible = ref(false), runVisible = ref(false), runsVisible = ref(false)
const runInputText = ref('{}'), activeRun = ref(null), running = ref(false), runs = ref([]), runDetail = ref(null)
const createForm = reactive({ code: '', name: '', description: '' })
let pollTimer
const filteredRows = computed(() => rows.value.filter(item => !keyword.value || `${item.name} ${item.code}`.toLowerCase().includes(keyword.value.toLowerCase())))
const runAlertType = computed(() => activeRun.value?.status === 'SUCCESS' ? 'success' : activeRun.value?.status === 'FAILED' ? 'error' : 'info')

/** 并行加载工作流和模板目录。 */
async function load() { const [workflowResponse, templateResponse] = await Promise.all([http.get('/workflow/canvases'), http.get('/workflow/nodes')]); rows.value = workflowResponse.data || []; templates.value = templateResponse.data || []; if (selected.value) select(rows.value.find(item => item.id === selected.value.id) || null) }
/** 选择工作流并复制当前草稿，避免编辑列表缓存。 */
function select(item) { selected.value = item ? cloneWorkflowData(item) : null; graph.value = serializeWorkflowGraph(item?.graph || createWorkflowGraph()); inputSchemaText.value = JSON.stringify(item?.inputSchema || {}, null, 0) }
/** 打开空的新建窗口。 */
function openCreate() { Object.assign(createForm, { code: '', name: '', description: '' }); createVisible.value = true }
/** 创建带最小开始结束节点的首个版本。 */
async function createWorkflow() { if (!createForm.code.trim() || !createForm.name.trim()) return ElMessage.warning(t('workflowCanvas.required')); try { const response = await http.post('/workflow/canvases', { ...createForm, graph: createWorkflowGraph(), inputSchema: {} }); createVisible.value = false; await load(); select(response.data); ElMessage.success(t('common.successSaved')) } catch (error) { showHttpError(error, 'common.saveFailed') } }
/** 校验并保存新的不可变草稿版本。 */
async function save() {
  const error = validateWorkflowGraph(graph.value); if (error) return ElMessage.warning(t('workflowCanvas.invalidGraph', { error }))
  let inputSchema; try { inputSchema = JSON.parse(inputSchemaText.value || '{}') } catch { return ElMessage.warning(t('workflowCanvas.invalidJson')) }
  try { const response = await http.put(`/workflow/canvases/${selected.value.id}`, { code: selected.value.code, name: selected.value.name, description: selected.value.description, graph: serializeWorkflowGraph(graph.value), inputSchema, revision: selected.value.revision }); await load(); select(response.data); ElMessage.success(t('common.successSaved')) } catch (error) { showHttpError(error, 'common.saveFailed') }
}
/** 发布当前保存版本供开放平台调用。 */
async function publish() { try { const response = await http.post(`/workflow/canvases/${selected.value.id}/publish`); await load(); select(response.data); ElMessage.success(t('workflowCanvas.published')) } catch (error) { showHttpError(error) } }
/** 软删除工作流定义。 */
async function remove() { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: selected.value.name }), t('common.deleteConfirm')); await http.delete(`/workflow/canvases/${selected.value.id}`); selected.value = null; await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
/** 提交异步调试运行并开始轮询。 */
async function startRun() { let inputs; try { inputs = JSON.parse(runInputText.value || '{}') } catch { return ElMessage.warning(t('workflowCanvas.invalidJson')) } running.value = true; try { const response = await http.post(`/workflow/canvases/${selected.value.id}/runs`, { inputs }); activeRun.value = { id: response.data.runId, status: response.data.status }; schedulePoll() } catch (error) { showHttpError(error) } finally { running.value = false } }
/** 每秒查询一次运行状态，终态自动停止。 */
function schedulePoll() { clearInterval(pollTimer); pollTimer = setInterval(async () => { if (!activeRun.value?.id) return; const response = await http.get(`/workflow/runs/${activeRun.value.id}`); activeRun.value = response.data; if (!['QUEUED', 'RUNNING', 'WAITING'].includes(response.data.status)) clearInterval(pollTimer) }, 1000) }
/** 取消当前排队或运行任务。 */
async function cancelRun() { const response = await http.post(`/workflow/runs/${activeRun.value.id}/cancel`); activeRun.value = response.data; clearInterval(pollTimer) }
/** 打开运行列表。 */
async function openRuns() { runsVisible.value = true; runs.value = (await http.get(`/workflow/canvases/${selected.value.id}/runs`)).data || []; runDetail.value = null }
/** 加载节点级输入输出和状态。 */
async function openRun(id) { runDetail.value = (await http.get(`/workflow/runs/${id}`)).data }
onMounted(load)
onBeforeUnmount(() => clearInterval(pollTimer))
</script>
