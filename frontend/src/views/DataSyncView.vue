<template>
  <div class="panel sync-workspace">
    <div class="sync-header">
      <div class="sync-heading"><span class="sync-icon"><el-icon><Connection /></el-icon></span><div><h2>{{ t('dataSync.title') }}</h2><p>{{ t('dataSync.description') }}</p></div></div>
      <div class="sync-header-actions"><el-button :icon="Refresh" @click="load" :loading="loading">{{ t('common.refresh') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:create')" type="primary" :icon="Plus" @click="createPlan">{{ t('dataSync.newPlan') }}</el-button></div>
    </div>

    <div class="sync-overview" :aria-label="t('dataSync.overview')">
      <div v-for="metric in overviewMetrics" :key="metric.label" :class="['sync-metric', metric.tone]"><div class="sync-metric-top"><span>{{ t(metric.label) }}</span><el-icon><component :is="metric.icon" /></el-icon></div><strong>{{ loading ? '—' : metric.value }}</strong><small>{{ t(metric.hint) }}</small></div>
    </div>
    <div class="sync-workbench">
      <section class="sync-plans" :aria-label="t('dataSync.plans')">
        <div class="sync-card-heading"><div><h3>{{ t('dataSync.plans') }}</h3><p>{{ t('dataSync.plansHint') }}</p></div><el-tag effect="plain">{{ filteredPlans.length }} / {{ plans.length }}</el-tag></div>
        <div class="sync-toolbar">
          <el-input v-model="planSearch" :prefix-icon="Search" :placeholder="t('dataSync.searchPlans')" :aria-label="t('dataSync.searchPlans')" clearable />
          <el-select v-model="statusFilter" :aria-label="t('dataSync.filterStatus')"><el-option :label="t('dataSync.allStatuses')" value="ALL" /><el-option :label="t('dataSync.notRun')" value="NOT_RUN" /><el-option v-for="status in runStatuses" :key="status" :label="statusLabel(status)" :value="status" /></el-select>
        </div>
        <div v-loading="loading" class="sync-plan-list" :aria-busy="loading">
          <button v-for="plan in filteredPlans" :key="plan.id" type="button" :class="['sync-plan-card', { selected: selectedPlan?.id === plan.id }]" :aria-pressed="selectedPlan?.id === plan.id" @click="selectedPlanId = plan.id">
            <div class="sync-plan-title"><span class="sync-plan-symbol"><el-icon><Connection /></el-icon></span><strong :title="plan.name">{{ plan.name }}</strong><el-tag :type="statusType(plan.lastRunStatus)" size="small">{{ statusLabel(plan.lastRunStatus) }}</el-tag></div>
            <div class="sync-card-flow"><div><small>{{ t('dataSync.source') }}</small><span :title="connectionName(plan.sourceConnectionId)"><el-icon><Coin /></el-icon>{{ connectionName(plan.sourceConnectionId) }}</span></div><el-icon class="sync-flow-arrow"><Right /></el-icon><div><small>{{ t('dataSync.target') }}</small><span :title="connectionName(plan.targetConnectionId)"><el-icon><Coin /></el-icon>{{ connectionName(plan.targetConnectionId) }}</span></div></div>
            <div class="sync-plan-meta"><span><el-icon><Monitor /></el-icon>{{ plan.serverName || t('dataSync.platformLocal') }}</span><span>{{ t(`dataSync.strategies.${plan.strategy}`) }}</span><span>{{ t('dataSync.selectedCount', { count: plan.tables?.length || 0 }) }}</span></div>
            <div class="sync-plan-footer"><span><el-icon><Clock /></el-icon>{{ plan.scheduleCron || t('dataSync.manual') }}</span><span :class="{ 'sync-enabled': plan.enabled }"><i />{{ t(plan.enabled ? 'common.enabled' : 'common.disabled') }}</span></div>
          </button>
          <el-empty v-if="!loading && !filteredPlans.length" :image-size="80" :description="t(plans.length ? 'dataSync.noMatches' : 'dataSync.emptyPlans')"><el-button v-if="plans.length" @click="resetFilters">{{ t('dataSync.clearFilters') }}</el-button><el-button v-else-if="auth.hasPermission('operations:data-sync:create')" type="primary" :icon="Plus" @click="createPlan">{{ t('dataSync.newPlan') }}</el-button></el-empty>
        </div>
      </section>
      <aside class="sync-inspector" :aria-label="t('dataSync.planSummary')">
        <div class="sync-card-heading"><div><h3>{{ t('dataSync.planSummary') }}</h3><p>{{ t('dataSync.summaryHint') }}</p></div><el-icon><View /></el-icon></div>
        <template v-if="selectedPlan">
          <div class="sync-summary-title"><el-tag :type="statusType(selectedPlan.lastRunStatus)" effect="light">{{ statusLabel(selectedPlan.lastRunStatus) }}</el-tag><h3>{{ selectedPlan.name }}</h3><span>{{ t(selectedPlan.enabled ? 'common.enabled' : 'common.disabled') }}</span></div>
          <div class="sync-summary-route"><div><i /><small>{{ t('dataSync.source') }}</small><strong>{{ connectionName(selectedPlan.sourceConnectionId) }}</strong></div><div><i /><small>{{ t('dataSync.target') }}</small><strong>{{ connectionName(selectedPlan.targetConnectionId) }}</strong></div></div>
          <dl class="sync-summary-fields"><div><dt>{{ t('dataSync.server') }}</dt><dd>{{ selectedPlan.serverName || t('dataSync.platformLocal') }}</dd></div><div><dt>{{ t('dataSync.strategy') }}</dt><dd>{{ t(`dataSync.strategies.${selectedPlan.strategy}`) }}</dd></div><div><dt>{{ t('dataSync.schedule') }}</dt><dd>{{ selectedPlan.scheduleCron || t('dataSync.manual') }}</dd></div></dl>
          <div class="sync-summary-tables"><h4>{{ t('dataSync.tables') }} <span>{{ selectedPlan.tables?.length || 0 }}</span></h4><div><el-tag v-for="table in selectedPlan.tables || []" :key="`${table.sourceSchema}.${table.sourceTable}`" type="info" effect="plain" :title="table.sourceTable">{{ table.sourceTable }}</el-tag></div></div>
          <div class="sync-summary-actions">
            <el-button v-if="auth.hasPermission('operations:data-sync:run')" type="primary" :icon="VideoPlay" :disabled="running(selectedPlan)" @click="run(selectedPlan)">{{ t('dataSync.run') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-sync:update')" :icon="Edit" @click="edit(selectedPlan)">{{ t('common.edit') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-sync:cancel')" :disabled="!running(selectedPlan)" @click="cancel(selectedPlan)">{{ t('dataSync.cancel') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-sync:run') && ['FAILED', 'CANCELLED'].includes(selectedPlan.lastRunStatus)" @click="retry(selectedPlan)">{{ t('dataSync.retry') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-sync:logs') && selectedPlan.lastRunId" :icon="Document" @click="showRun(selectedPlan)">{{ t('dataSync.details') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-sync:delete')" type="danger" plain :icon="Delete" @click="remove(selectedPlan)">{{ t('common.delete') }}</el-button>
          </div>
        </template>
        <el-empty v-else :image-size="72" :description="t('dataSync.selectPlan')" />
        <div class="sync-safety-note"><el-icon><Lock /></el-icon><div><strong>{{ t('dataSync.safetyTitle') }}</strong><p>{{ t('dataSync.safetyHint') }}</p></div></div>
      </aside>
    </div>
    <el-drawer v-model="editorVisible" :title="t(form.id ? 'dataSync.editPlan' : 'dataSync.newPlan')" size="min(860px, 100vw)" class="sync-editor" :close-on-click-modal="false" :close-on-press-escape="!saving" :show-close="!saving" :before-close="closeEditor">
    <el-alert class="sync-security" :title="t('dataSync.securityNotice')" type="warning" show-icon :closable="false" />
    <el-form v-if="auth.hasPermission(form.id ? 'operations:data-sync:update' : 'operations:data-sync:create')" label-position="top" class="data-sync-form">
      <div class="sync-card-heading"><div><h3>{{ t(form.id ? 'dataSync.editPlan' : 'dataSync.configure') }}</h3><p>{{ t('dataSync.configureHint') }}</p></div><el-tag effect="plain">{{ t(`dataSync.strategies.${form.strategy}`) }}</el-tag></div>
      <section class="sync-section">
      <h4><span>01</span>{{ t('dataSync.basic') }}</h4>
      <div class="data-sync-grid">
        <el-form-item :label="t('dataSync.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('dataSync.strategy')"><el-select v-model="form.strategy" class="full"><el-option v-for="item in strategies" :key="item" :label="t(`dataSync.strategies.${item}`)" :value="item" /></el-select></el-form-item>
        <el-form-item :label="t('dataSync.server')"><el-select v-model="form.serverId" class="full" @change="loadTables"><el-option v-for="item in servers" :key="item.id" :label="`${item.name} (${item.mode})`" :value="item.id" :disabled="!item.enabled" /></el-select></el-form-item>
      </div>
      </section>
      <section class="sync-section">
      <h4><span>02</span>{{ t('dataSync.connections') }}</h4>
      <div class="sync-flow">
        <div class="sync-endpoint"><span class="sync-endpoint-label"><el-icon><Coin /></el-icon>{{ t('dataSync.readOnly') }}</span><el-form-item :label="t('dataSync.source')"><el-select v-model="form.sourceConnectionId" class="full" @change="loadTables"><el-option v-for="item in connections" :key="item.id" :label="`${item.name} (${item.connectionType})`" :value="item.id" /></el-select></el-form-item></div>
        <span class="sync-direction" aria-hidden="true"><el-icon><Right /></el-icon></span>
        <div class="sync-endpoint sync-target"><span class="sync-endpoint-label"><el-icon><Coin /></el-icon>{{ t('dataSync.writeTarget') }}</span><el-form-item :label="t('dataSync.target')"><el-select v-model="form.targetConnectionId" class="full"><el-option v-for="item in connections" :key="item.id" :label="`${item.name} (${item.connectionType})`" :value="item.id" /></el-select></el-form-item></div>
      </div>
      </section>
      <section class="sync-section">
      <div class="sync-card-heading"><h4><span>03</span>{{ t('dataSync.tablesAndSchedule') }}</h4><el-tag type="info" effect="plain">{{ t('dataSync.selectedCount', { count: selectedTables.length }) }}</el-tag></div>
      <el-form-item :label="t('dataSync.tables')">
        <div class="sync-table-picker">
          <el-input v-model="tableSearch" :prefix-icon="Search" :placeholder="t('dataSync.searchTables')" :aria-label="t('dataSync.searchTables')" clearable class="sync-table-search" />
          <el-checkbox-group v-if="sourceTables.length" v-model="selectedTables" class="data-sync-tables">
            <el-checkbox v-for="item in filteredTables" :key="item.name" :label="item.name" border><span :title="`${item.schema ? `${item.schema}.` : ''}${item.name}`">{{ item.schema ? `${item.schema}.` : '' }}{{ item.name }}</span></el-checkbox>
          </el-checkbox-group>
          <div v-else class="sync-empty-tables"><el-icon><Grid /></el-icon><span>{{ t('dataSync.tablesHint') }}</span></div>
          <p v-if="sourceTables.length && !filteredTables.length" class="sync-dialog-hint">{{ t('dataSync.noMatches') }}</p>
        </div>
      </el-form-item>
      <div class="data-sync-grid">
        <el-form-item :label="t('dataSync.schedule')"><el-input v-model="form.scheduleCron" :placeholder="t('dataSync.schedulePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" :active-text="t('common.enabled')" /></el-form-item>
      </div>
      <div v-if="form.strategy === 'FULL_REPLACE'" class="sync-destructive"><el-checkbox v-model="form.confirmDestructive">{{ t('dataSync.confirmDestructive') }}</el-checkbox></div>
      </section>
    </el-form>
    <template #footer>
      <div v-if="auth.hasPermission(form.id ? 'operations:data-sync:update' : 'operations:data-sync:create')" class="table-actions data-sync-actions">
        <span>{{ t('dataSync.selectedCount', { count: selectedTables.length }) }}</span>
        <el-button v-if="auth.hasPermission('operations:data-sync:preview')" :icon="View" :loading="previewing" @click="preview">{{ t('dataSync.preview') }}</el-button>
        <el-button type="primary" :icon="Check" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </div>
    </template>
    </el-drawer>

    <el-dialog v-model="previewVisible" :title="t('dataSync.preview')" width="min(960px, 94vw)">
      <p class="sync-dialog-hint">{{ t('dataSync.previewHint') }}</p>
      <el-table :data="previewRows" stripe><el-table-column prop="mapping.sourceTable" :label="t('dataSync.sourceTable')" min-width="160" show-overflow-tooltip /><el-table-column prop="mapping.targetTable" :label="t('dataSync.targetTable')" min-width="160" show-overflow-tooltip /><el-table-column prop="sourceRows" :label="t('dataSync.sourceRows')" min-width="100" /><el-table-column :label="t('dataSync.warnings')" min-width="220"><template #default="scope">{{ formatWarnings(scope.row.warnings) }}</template></el-table-column></el-table>
    </el-dialog>
    <el-dialog v-model="runVisible" :title="t('dataSync.details')" width="min(1040px, 94vw)">
      <el-descriptions v-if="runDetail" :column="1" border class="sync-run-summary">
        <el-descriptions-item :label="t('common.status')"><el-tag :type="statusType(runDetail.status)">{{ statusLabel(runDetail.status) }}</el-tag></el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.server')">{{ runDetail.serverName || t('dataSync.platformLocal') }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.readRows')">{{ runDetail.readRows }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.writtenRows')">{{ runDetail.writtenRows }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.traceId')">{{ runDetail.traceId }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.error')">{{ runDetail.errorMessage || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="runDetail?.tables || []" v-loading="runLoading" class="run-table">
        <el-table-column prop="sourceTable" :label="t('dataSync.sourceTable')" min-width="160" />
        <el-table-column prop="targetTable" :label="t('dataSync.targetTable')" min-width="160" />
        <el-table-column prop="status" :label="t('common.status')" width="150"><template #default="scope"><el-tag :type="statusType(scope.row.status)">{{ statusLabel(scope.row.status) }}</el-tag></template></el-table-column>
        <el-table-column prop="readRows" :label="t('dataSync.readRows')" width="110" />
        <el-table-column prop="insertedRows" :label="t('dataSync.writtenRows')" width="110" />
        <el-table-column prop="errorMessage" :label="t('dataSync.error')" min-width="220" show-overflow-tooltip />
      </el-table>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, CircleCheck, Clock, Coin, Connection, Delete, Document, Edit, Grid, Lock, Monitor, Plus, Refresh, Right, Search, VideoPlay, View, Warning } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n(), auth = useAuthStore()
const connections = ref([]), servers = ref([]), plans = ref([]), sourceTables = ref([]), selectedTables = ref([])
const loading = ref(false), saving = ref(false), previewing = ref(false), previewVisible = ref(false), previewRows = ref([])
const runVisible = ref(false), runLoading = ref(false), runDetail = ref(null)
const strategies = ['UPSERT', 'FULL_REPLACE', 'APPEND']
const form = reactive({ id: null, name: '', serverId: null, sourceConnectionId: null, targetConnectionId: null, strategy: 'UPSERT', scheduleCron: '', enabled: true, confirmDestructive: false })
const editorVisible = ref(false), selectedPlanId = ref(null), planSearch = ref(''), statusFilter = ref('ALL'), tableSearch = ref('')
const runStatuses = ['PENDING', 'RUNNING', 'SUCCEEDED', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED', 'SKIPPED']
const filteredPlans = computed(() => filterPlans(plans.value, planSearch.value, statusFilter.value))
const selectedPlan = computed(() => filteredPlans.value.find(plan => plan.id === selectedPlanId.value) || filteredPlans.value[0] || null)
const filteredTables = computed(() => sourceTables.value.filter(table => `${table.schema || ''}.${table.name}`.toLowerCase().includes(tableSearch.value.trim().toLowerCase())))
const overviewMetrics = computed(() => [
  { label: 'dataSync.plans', value: plans.value.length, icon: Connection, tone: 'neutral', hint: 'dataSync.totalHint' },
  { label: 'common.enabled', value: plans.value.filter(plan => plan.enabled).length, icon: CircleCheck, tone: 'success', hint: 'dataSync.enabledHint' },
  { label: 'dataSync.statuses.RUNNING', value: plans.value.filter(running).length, icon: Refresh, tone: 'active', hint: 'dataSync.runningHint' },
  { label: 'dataSync.statuses.FAILED', value: plans.value.filter(plan => plan.lastRunStatus === 'FAILED').length, icon: Warning, tone: 'danger', hint: 'dataSync.failedHint' },
])

// 按名称、服务器与连接名称联合筛选，成功状态兼容历史值。
function filterPlans(items, keyword, status) {
  const query = keyword.trim().toLowerCase()
  return items.filter(plan => {
    const matchesStatus = status === 'ALL' || (status === 'NOT_RUN' ? !plan.lastRunStatus : status === 'SUCCEEDED' ? ['SUCCESS', 'SUCCEEDED'].includes(plan.lastRunStatus) : plan.lastRunStatus === status)
    const searchable = [plan.name, plan.serverName, connectionName(plan.sourceConnectionId), connectionName(plan.targetConnectionId)].join(' ').toLowerCase()
    return matchesStatus && searchable.includes(query)
  })
}

// 只展示列表接口提供的连接名称，已删除或不可见的连接保留标识。
function connectionName(connectionId) {
  return connections.value.find(connection => connection.id === connectionId)?.name || t('dataSync.unavailableConnection', { id: connectionId ?? '—' })
}

// 清除查询条件，恢复完整任务列表。
function resetFilters() {
  planSearch.value = ''
  statusFilter.value = 'ALL'
}

// 新建时清除上次编辑状态，避免误更新已有计划。
function createPlan() {
  Object.assign(form, { id: null, name: '', serverId: null, sourceConnectionId: null, targetConnectionId: null, strategy: 'UPSERT', scheduleCron: '', enabled: true, confirmDestructive: false })
  sourceTables.value = []
  selectedTables.value = []
  tableSearch.value = ''
  editorVisible.value = true
}

// 保存期间保持抽屉打开，避免请求未结束时切换编辑对象。
function closeEditor(done) {
  if (!saving.value) done()
}

// 根据运行状态呈现语义颜色，未知状态使用中性色。
function statusType(status) {
  const colors = { SUCCEEDED: 'success', SUCCESS: 'success', FAILED: 'danger', RUNNING: 'primary', CANCEL_REQUESTED: 'warning', CANCELLED: 'info' }
  return Object.hasOwn(colors, status) ? colors[status] : 'info'
}

// 已知状态使用本地化文案，保留未知状态以兼容后端扩展。
function statusLabel(status) {
  if (!status) return t('dataSync.notRun')
  return ['PENDING', 'RUNNING', 'SUCCEEDED', 'SUCCESS', 'FAILED', 'CANCEL_REQUESTED', 'CANCELLED', 'SKIPPED'].includes(status) ? t(`dataSync.statuses.${status}`) : status
}

// 加载当前用户可见的数据库连接和同步计划。
async function load() {
  loading.value = true
  try { const [connectionResponse, serverResponse, planResponse] = await Promise.all([http.get('/data-sync/connections'), http.get('/data-sync/servers'), http.get('/data-sync/plans')]); connections.value = connectionResponse.data || []; servers.value = serverResponse.data || []; plans.value = planResponse.data || [] } catch (error) { showHttpError(error, 'dataSync.loadFailed') } finally { loading.value = false }
}

// 根据源连接读取可选表，并清除旧连接的选择。
async function loadTables() {
  selectedTables.value = []
  tableSearch.value = ''
  if (!form.serverId || !form.sourceConnectionId) { sourceTables.value = []; return }
  try { const { data } = await http.get(`/data-sync/connections/${form.sourceConnectionId}/tables`, { params: { serverId: form.serverId } }); sourceTables.value = data || [] } catch (error) { sourceTables.value = []; showHttpError(error, 'dataSync.loadFailed') }
}

// 将界面中的表选择转换为后端使用的同名表映射。
function mappings() { return selectedTables.value.map(name => ({ sourceSchema: '', sourceTable: name, targetSchema: '', targetTable: name, columns: [] })) }

// 执行只读结构预检并展示兼容性提示。
async function preview() {
  if (!form.serverId || !form.sourceConnectionId || !form.targetConnectionId || !selectedTables.value.length) return ElMessage.warning(t('dataSync.formRequired'))
  previewing.value = true
  try { const { data } = await http.post('/data-sync/preview', { sourceConnectionId: form.sourceConnectionId, targetConnectionId: form.targetConnectionId, serverId: form.serverId, strategy: form.strategy, tables: mappings() }); previewRows.value = data?.tables || []; previewVisible.value = true } catch (error) { showHttpError(error, 'dataSync.previewFailed') } finally { previewing.value = false }
}

// 创建或更新同步计划。
async function save() {
  if (!form.name || !form.serverId || !form.sourceConnectionId || !form.targetConnectionId || !selectedTables.value.length) return ElMessage.warning(t('dataSync.formRequired'))
  saving.value = true
  try { const body = { ...form, tables: mappings() }; if (form.id) await http.put(`/data-sync/plans/${form.id}`, body); else await http.post('/data-sync/plans', body); ElMessage.success(t('common.success')); editorVisible.value = false; await load() } catch (error) { showHttpError(error, 'dataSync.saveFailed') } finally { saving.value = false }
}

// 判断计划最近一次运行是否尚未结束。
function running(row) { return ['RUNNING', 'CANCEL_REQUESTED'].includes(row.lastRunStatus) }
// 将已保存计划回填到编辑表单。
async function edit(row) { Object.assign(form, { id: row.id, name: row.name, serverId: row.serverId, sourceConnectionId: row.sourceConnectionId, targetConnectionId: row.targetConnectionId, strategy: row.strategy, scheduleCron: row.scheduleCron || '', enabled: row.enabled, confirmDestructive: row.strategy === 'FULL_REPLACE' }); await loadTables(); selectedTables.value = (row.tables || []).map(table => table.sourceTable); editorVisible.value = true }
// 手动启动同步计划。
async function run(row) { try { await http.post(`/data-sync/plans/${row.id}/run`); ElMessage.success(t('dataSync.runAccepted')); await load() } catch (error) { showHttpError(error, 'dataSync.runFailed') } }
// 请求取消最近一次运行。
async function cancel(row) { if (!row.lastRunId) return; try { await http.post(`/data-sync/runs/${row.lastRunId}/cancel`); await load() } catch (error) { showHttpError(error, 'dataSync.cancelFailed') } }
// 重试最近一次失败或取消的运行。
async function retry(row) { if (!row.lastRunId) return; try { await http.post(`/data-sync/runs/${row.lastRunId}/retry`); ElMessage.success(t('dataSync.runAccepted')); await load() } catch (error) { showHttpError(error, 'dataSync.runFailed') } }
// 加载逐表运行明细。
async function showRun(row) { runVisible.value = true; runLoading.value = true; runDetail.value = null; try { const { data } = await http.get(`/data-sync/runs/${row.lastRunId}`); runDetail.value = data } catch (error) { showHttpError(error, 'dataSync.loadFailed') } finally { runLoading.value = false } }
// 将后端稳定警告码转换为当前语言文案。
function formatWarnings(warnings = []) { if (!warnings.length) return t('dataSync.noWarnings'); return warnings.map(item => { const [code, column] = item.split(':'); return t(`dataSync.warningCodes.${code}`, { column }) }).join(', ') }
// 二次确认后删除同步计划。
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm')); await http.delete(`/data-sync/plans/${row.id}`); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
onMounted(load)
</script>

<style scoped>
.sync-workspace { background: var(--app-canvas); border: 0; box-shadow: none; min-width: 0; }
.sync-header, .sync-heading, .sync-card-heading { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.sync-header { margin-bottom: 24px; }
.sync-heading { justify-content: flex-start; min-width: 0; }
.sync-heading h2 { margin: 0 0 6px; font-size: 23px; letter-spacing: -.5px; }
.sync-heading p, .sync-card-heading p, .sync-dialog-hint { margin: 0; color: var(--app-muted); font-size: 13px; line-height: 1.7; }
.sync-icon { display: grid; place-items: center; width: 48px; height: 48px; flex-shrink: 0; color: var(--app-primary); background: var(--el-color-primary-light-9); border: 1px solid var(--el-color-primary-light-8); border-radius: 14px; font-size: 25px; }
.sync-overview { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 18px; margin-bottom: 24px; }
.sync-overview > div { display: flex; flex-direction: column; gap: 10px; padding: 20px 22px; border: 1px solid var(--app-border); border-radius: 14px; background: var(--app-surface); }
.sync-metric-top { display: flex; justify-content: space-between; align-items: center; gap: 8px; }
.sync-metric-top .el-icon { padding: 9px; box-sizing: content-box; border-radius: 10px; background: var(--app-canvas); color: var(--app-muted); }
.sync-metric.active .el-icon { color: var(--app-primary); background: var(--el-color-primary-light-9); }
.sync-metric.success .el-icon { color: var(--el-color-success); background: var(--el-color-success-light-9); }
.sync-metric.danger .el-icon { color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.sync-metric small { color: var(--app-muted); font-size: 12px; }
.sync-overview span { color: var(--app-muted); font-size: 13px; }
.sync-overview strong { font-size: 32px; line-height: 1; font-weight: 650; font-variant-numeric: tabular-nums; }
.sync-security { border: 1px solid var(--el-color-warning-light-8); }
.data-sync-form, .sync-plans, .sync-inspector { border: 1px solid var(--app-border); border-radius: 14px; background: var(--app-surface); overflow: hidden; min-width: 0; }
.data-sync-form { margin-top: 20px; }
.sync-header-actions { display: flex; flex-wrap: wrap; gap: 10px; }
.sync-header-actions .el-button + .el-button { margin-left: 0; }
.sync-workbench { display: grid; grid-template-columns: minmax(0, 1.85fr) minmax(290px, 1fr); gap: 22px; align-items: start; }
.sync-inspector { position: sticky; top: 20px; }
.sync-toolbar { display: flex; gap: 12px; padding: 20px 22px; border-bottom: 1px solid var(--app-border); }
.sync-toolbar > .el-select { flex: 0 0 150px; }
.sync-plan-list { display: grid; gap: 14px; padding: 20px; min-height: 260px; }
.sync-plan-card { appearance: none; width: 100%; min-width: 0; text-align: start; padding: 20px; border: 1px solid var(--app-border); background: var(--app-surface); color: var(--app-text); border-radius: 12px; font: inherit; cursor: pointer; transition: border-color .15s, box-shadow .15s; }
.sync-plan-card:hover { border-color: var(--el-color-primary-light-5); }
.sync-plan-card.selected { border-color: var(--app-primary); box-shadow: inset 3px 0 var(--app-primary); background: linear-gradient(110deg, var(--el-color-primary-light-9), var(--app-surface) 65%); }
.sync-plan-card:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 3px; }
.sync-plan-title { display: flex; align-items: center; gap: 10px; min-width: 0; }
.sync-plan-title > strong { flex: 1; min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 15px; }
.sync-plan-title > .el-tag { flex-shrink: 0; }
.sync-plan-symbol { display: grid; place-items: center; width: 32px; height: 32px; flex-shrink: 0; border-radius: 8px; background: var(--el-color-primary-light-9); color: var(--app-primary); }
.sync-card-flow { display: grid; grid-template-columns: minmax(0, 1fr) 28px minmax(0, 1fr); align-items: center; gap: 14px; padding: 20px 0; }
.sync-card-flow > div { display: grid; gap: 8px; min-width: 0; }
.sync-card-flow small { font-size: 11px; color: var(--app-muted); }
.sync-card-flow span { display: block; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 13px; }
.sync-card-flow span .el-icon { margin-right: 7px; color: var(--app-muted); vertical-align: -2px; }
.sync-flow-arrow { color: var(--app-primary); }
.sync-plan-meta { display: flex; align-items: center; gap: 8px 16px; flex-wrap: wrap; color: var(--app-muted); font-size: 12px; overflow-wrap: anywhere; }
.sync-plan-meta .el-icon, .sync-plan-footer .el-icon { vertical-align: -2px; margin-right: 5px; }
.sync-plan-footer { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 10px; border-top: 1px solid var(--app-border); margin-top: 16px; padding-top: 13px; color: var(--app-muted); font-size: 12px; overflow-wrap: anywhere; }
.sync-plan-footer i { display: inline-block; width: 6px; height: 6px; border-radius: 50%; margin-right: 6px; background: var(--app-muted); }
.sync-plan-footer .sync-enabled i { background: var(--el-color-success); }
.sync-summary-title { padding: 24px 24px 18px; }
.sync-summary-title h3 { margin: 14px 0 8px; font-size: 19px; overflow-wrap: anywhere; }
.sync-summary-title > span:last-child { color: var(--app-muted); font-size: 12px; }
.sync-summary-route { margin: 0 24px 24px; padding: 18px; background: var(--app-canvas); border: 1px solid var(--app-border); border-radius: 10px; }
.sync-summary-route > div { position: relative; display: grid; gap: 6px; padding-left: 23px; }
.sync-summary-route > div + div { padding-top: 24px; }
.sync-summary-route > div:first-child::after { content: ''; position: absolute; left: 4px; top: 14px; bottom: -24px; border-left: 1px dashed var(--el-color-primary-light-5); }
.sync-summary-route i { position: absolute; left: 0; top: 3px; width: 9px; height: 9px; border-radius: 50%; background: var(--app-primary); }
.sync-summary-route > div + div i { top: 27px; background: var(--el-color-success); }
.sync-summary-route small { color: var(--app-muted); font-size: 11px; }
.sync-summary-route strong { font-size: 13px; overflow-wrap: anywhere; }
.sync-summary-fields { display: grid; gap: 16px; padding: 0 24px; margin: 0; font-size: 12px; }
.sync-summary-fields > div { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1.4fr); gap: 12px; }
.sync-summary-fields dt { color: var(--app-muted); }
.sync-summary-fields dd { margin: 0; text-align: end; overflow-wrap: anywhere; }
.sync-summary-tables { padding: 20px 24px; }
.sync-summary-tables h4 { margin: 0 0 12px; font-size: 12px; font-weight: 500; }
.sync-summary-tables h4 span { color: var(--app-muted); margin-left: 5px; }
.sync-summary-tables > div { display: flex; gap: 7px; flex-wrap: wrap; max-height: 160px; overflow: auto; }
.sync-summary-tables .el-tag { max-width: 100%; }
.sync-summary-tables :deep(.el-tag__content) { overflow: hidden; text-overflow: ellipsis; }
.sync-summary-actions { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; padding: 4px 24px 24px; }
.sync-summary-actions .el-button { margin: 0; min-width: 0; height: auto; min-height: 34px; }
.sync-summary-actions :deep(.el-button > span) { white-space: normal; line-height: 1.4; }
.sync-safety-note { display: flex; gap: 10px; padding: 18px 24px; background: var(--app-canvas); border-top: 1px solid var(--app-border); font-size: 12px; }
.sync-safety-note > .el-icon { flex-shrink: 0; margin-top: 2px; color: var(--app-primary); }
.sync-safety-note strong { font-weight: 500; }
.sync-safety-note p { color: var(--app-muted); margin: 6px 0 0; line-height: 1.7; }
.sync-table-search { margin-bottom: 12px; }
.sync-card-heading { padding: 20px 24px; border-bottom: 1px solid var(--app-border); }
.sync-card-heading h3 { margin: 0 0 5px; font-size: 16px; }
.sync-section { padding: 20px 24px; }
.sync-section + .sync-section { border-top: 1px solid var(--app-border); }
.sync-section h4 { display: flex; align-items: center; gap: 10px; margin: 0 0 20px; font-size: 14px; }
.sync-section h4 > span { display: grid; place-items: center; width: 26px; height: 26px; border-radius: 8px; background: var(--el-color-primary-light-9); color: var(--app-primary); font-size: 11px; }
.sync-section > .sync-card-heading { padding: 0 0 18px; border: 0; }
.sync-section > .sync-card-heading h4 { margin: 0; }
.data-sync-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 22px; }
.sync-section :deep(.el-form-item) { min-width: 0; margin-bottom: 16px; }
.sync-section :deep(.el-form-item__label) { font-weight: 500; }
.sync-flow { display: grid; grid-template-columns: minmax(0, 1fr) 40px minmax(0, 1fr); align-items: center; gap: 14px; }
.sync-endpoint { min-width: 0; padding: 18px; border: 1px solid var(--app-border); background: var(--app-canvas); border-radius: 10px; }
.sync-target { background: var(--el-color-primary-light-9); border-color: var(--el-color-primary-light-8); }
.sync-endpoint-label { display: flex; align-items: center; gap: 7px; margin-bottom: 14px; color: var(--app-muted); font-size: 12px; }
.sync-target .sync-endpoint-label { color: var(--app-primary); }
.sync-endpoint :deep(.el-form-item) { margin-bottom: 0; }
.sync-direction { display: grid; place-items: center; width: 40px; height: 40px; border-radius: 50%; border: 1px solid var(--app-border); color: var(--app-primary); font-size: 20px; }
.sync-table-picker { width: 100%; padding: 14px; border: 1px dashed var(--app-border); border-radius: 10px; background: var(--app-canvas); }
.data-sync-tables { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; max-height: 280px; overflow-y: auto; padding: 2px; }
.data-sync-tables :deep(.el-checkbox) { margin: 0; width: 100%; min-width: 0; background: var(--app-surface); }
.data-sync-tables :deep(.el-checkbox.is-checked) { background: var(--el-color-primary-light-9); }
.data-sync-tables :deep(.el-checkbox__label) { min-width: 0; overflow: hidden; text-overflow: ellipsis; }
.sync-empty-tables { display: flex; align-items: center; justify-content: center; gap: 12px; min-height: 86px; color: var(--app-muted); line-height: 1.6; }
.sync-empty-tables > .el-icon { flex-shrink: 0; font-size: 24px; }
.sync-destructive { padding: 12px 16px; border: 1px solid var(--el-color-danger-light-8); border-radius: 8px; background: var(--el-color-danger-light-9); }
.sync-destructive :deep(.el-checkbox) { white-space: normal; height: auto; margin: 0; }
.sync-destructive :deep(.el-checkbox__label) { line-height: 1.6; color: var(--el-color-danger); }
.data-sync-actions { padding: 12px 0 0; margin: 0; justify-content: flex-end; border-top: 1px solid var(--app-border); }
.data-sync-actions > span { margin-right: auto; color: var(--app-muted); font-size: 12px; }
.sync-dialog-hint { margin-bottom: 18px; }
.sync-run-summary :deep(.el-descriptions__table) { table-layout: fixed; }
.sync-run-summary :deep(.el-descriptions__label) { width: 125px; }
.sync-run-summary :deep(.el-descriptions__content) { overflow-wrap: anywhere; }
.run-table { margin-top: 18px; }
.full { width: 100%; }
@media (max-width: 1100px) { .data-sync-tables { grid-template-columns: repeat(2, minmax(0, 1fr)); } .sync-workbench { grid-template-columns: minmax(0, 1fr); } .sync-inspector { position: static; } }
@media (max-width: 760px) {
  .sync-header { align-items: flex-start; flex-wrap: wrap; }
  .sync-heading { align-items: flex-start; }
  .sync-heading h2 { font-size: 20px; }
  .sync-icon { width: 38px; height: 38px; font-size: 21px; }
  .sync-overview { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
  .sync-overview > div { padding: 16px; }
  .sync-overview strong { font-size: 24px; }
  .sync-card-heading { flex-wrap: wrap; padding: 16px; }
  .sync-toolbar { padding: 16px; flex-wrap: wrap; }
  .sync-toolbar > .el-select { flex: 1 1 100%; }
  .sync-plan-list { padding: 12px; }
  .sync-plan-card { padding: 14px; }
  .sync-card-flow { gap: 8px; }
  .sync-section { padding: 18px 16px; }
  .data-sync-grid, .data-sync-tables, .sync-flow { grid-template-columns: 1fr; }
  .sync-direction { justify-self: center; transform: rotate(90deg); width: 30px; height: 30px; }
  .data-sync-actions { padding: 16px; flex-wrap: wrap; }
}
</style>
