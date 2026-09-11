<template>
  <div class="panel sync-workspace">
    <div class="sync-header">
      <div class="sync-heading"><span class="sync-icon"><el-icon><Connection /></el-icon></span><div><h2>{{ t('dataSync.title') }}</h2><p>{{ t('dataSync.description') }}</p></div></div>
      <el-button :icon="Refresh" @click="load" :loading="loading">{{ t('common.refresh') }}</el-button>
    </div>

    <div class="sync-overview" :aria-label="t('dataSync.overview')">
      <div><span>{{ t('dataSync.plans') }}</span><strong>{{ loading ? '—' : plans.length }}</strong></div>
      <div><span>{{ t('common.enabled') }}</span><strong>{{ loading ? '—' : plans.filter(plan => plan.enabled).length }}</strong></div>
      <div><span>{{ t('dataSync.statuses.RUNNING') }}</span><strong class="sync-active">{{ loading ? '—' : plans.filter(running).length }}</strong></div>
    </div>
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
          <el-checkbox-group v-if="sourceTables.length" v-model="selectedTables" class="data-sync-tables">
            <el-checkbox v-for="item in sourceTables" :key="item.name" :label="item.name" border><span :title="`${item.schema ? `${item.schema}.` : ''}${item.name}`">{{ item.schema ? `${item.schema}.` : '' }}{{ item.name }}</span></el-checkbox>
          </el-checkbox-group>
          <div v-else class="sync-empty-tables"><el-icon><Grid /></el-icon><span>{{ t('dataSync.tablesHint') }}</span></div>
        </div>
      </el-form-item>
      <div class="data-sync-grid">
        <el-form-item :label="t('dataSync.schedule')"><el-input v-model="form.scheduleCron" :placeholder="t('dataSync.schedulePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" :active-text="t('common.enabled')" /></el-form-item>
      </div>
      <div v-if="form.strategy === 'FULL_REPLACE'" class="sync-destructive"><el-checkbox v-model="form.confirmDestructive">{{ t('dataSync.confirmDestructive') }}</el-checkbox></div>
      </section>
      <div class="table-actions data-sync-actions">
        <el-button v-if="auth.hasPermission('operations:data-sync:preview')" :icon="View" :loading="previewing" @click="preview">{{ t('dataSync.preview') }}</el-button>
        <el-button type="primary" :icon="Check" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </div>
    </el-form>

    <section class="sync-plans">
    <div class="sync-card-heading"><div><h3>{{ t('dataSync.plans') }}</h3><p>{{ t('dataSync.plansHint') }}</p></div><el-tag type="info" effect="plain">{{ plans.length }}</el-tag></div>
    <el-table :data="plans" v-loading="loading" class="data-sync-table">
      <template #empty><el-empty :image-size="64" :description="t('dataSync.emptyPlans')" /></template>
      <el-table-column prop="name" :label="t('dataSync.name')" min-width="180" show-overflow-tooltip><template #default="scope"><strong class="sync-plan-name">{{ scope.row.name }}</strong></template></el-table-column>
      <el-table-column :label="t('dataSync.server')" min-width="160"><template #default="scope">{{ scope.row.serverName || t('dataSync.platformLocal') }}</template></el-table-column>
      <el-table-column prop="strategy" :label="t('dataSync.strategy')" width="150"><template #default="scope">{{ t(`dataSync.strategies.${scope.row.strategy}`) }}</template></el-table-column>
      <el-table-column :label="t('dataSync.tables')" width="100"><template #default="scope">{{ scope.row.tables?.length || 0 }}</template></el-table-column>
      <el-table-column prop="scheduleCron" :label="t('dataSync.schedule')" min-width="150" show-overflow-tooltip><template #default="scope"><span class="sync-schedule">{{ scope.row.scheduleCron || t('dataSync.manual') }}</span></template></el-table-column>
      <el-table-column prop="lastRunStatus" :label="t('dataSync.lastStatus')" width="150"><template #default="scope"><el-tag :type="statusType(scope.row.lastRunStatus)" effect="light">{{ statusLabel(scope.row.lastRunStatus) }}</el-tag></template></el-table-column>
      <el-table-column :label="t('common.operation')" width="240"><template #default="scope"><div class="table-actions sync-row-actions"><el-button v-if="auth.hasPermission('operations:data-sync:update')" link @click="edit(scope.row)">{{ t('common.edit') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:run')" link type="primary" :disabled="running(scope.row)" @click="run(scope.row)">{{ t('dataSync.run') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:cancel')" link type="warning" :disabled="!running(scope.row)" @click="cancel(scope.row)">{{ t('dataSync.cancel') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:run') && ['FAILED', 'CANCELLED'].includes(scope.row.lastRunStatus)" link type="primary" @click="retry(scope.row)">{{ t('dataSync.retry') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:logs') && scope.row.lastRunId" link @click="showRun(scope.row)">{{ t('dataSync.details') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button></div></template></el-table-column>
    </el-table>
    </section>

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
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Check, Coin, Connection, Grid, Refresh, Right, View } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n(), auth = useAuthStore()
const connections = ref([]), servers = ref([]), plans = ref([]), sourceTables = ref([]), selectedTables = ref([])
const loading = ref(false), saving = ref(false), previewing = ref(false), previewVisible = ref(false), previewRows = ref([])
const runVisible = ref(false), runLoading = ref(false), runDetail = ref(null)
const strategies = ['UPSERT', 'FULL_REPLACE', 'APPEND']
const form = reactive({ id: null, name: '', serverId: null, sourceConnectionId: null, targetConnectionId: null, strategy: 'UPSERT', scheduleCron: '', enabled: true, confirmDestructive: false })

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
  try { const body = { ...form, tables: mappings() }; if (form.id) await http.put(`/data-sync/plans/${form.id}`, body); else await http.post('/data-sync/plans', body); ElMessage.success(t('common.success')); await load() } catch (error) { showHttpError(error, 'dataSync.saveFailed') } finally { saving.value = false }
}

// 判断计划最近一次运行是否尚未结束。
function running(row) { return ['RUNNING', 'CANCEL_REQUESTED'].includes(row.lastRunStatus) }
// 将已保存计划回填到编辑表单。
async function edit(row) { Object.assign(form, { id: row.id, name: row.name, serverId: row.serverId, sourceConnectionId: row.sourceConnectionId, targetConnectionId: row.targetConnectionId, strategy: row.strategy, scheduleCron: row.scheduleCron || '', enabled: row.enabled, confirmDestructive: row.strategy === 'FULL_REPLACE' }); await loadTables(); selectedTables.value = (row.tables || []).map(table => table.sourceTable) }
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
.sync-overview { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); margin-bottom: 20px; background: var(--app-surface); border: 1px solid var(--app-border); border-radius: 12px; padding: 20px 0; }
.sync-overview > div { display: flex; flex-direction: column; gap: 8px; padding: 0 24px; }
.sync-overview > div + div { border-left: 1px solid var(--app-border); }
.sync-overview span { color: var(--app-muted); font-size: 13px; }
.sync-overview strong { font-size: 28px; font-variant-numeric: tabular-nums; }
.sync-active { color: var(--app-primary); }
.sync-security { border: 1px solid var(--el-color-warning-light-8); }
.data-sync-form, .sync-plans { margin-top: 22px; border: 1px solid var(--app-border); border-radius: 14px; background: var(--app-surface); overflow: hidden; min-width: 0; }
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
.data-sync-actions { padding: 16px 24px; margin: 0; justify-content: flex-end; border-top: 1px solid var(--app-border); background: var(--app-canvas); }
.data-sync-table { margin: 0; }
.data-sync-table :deep(th.el-table__cell) { background: var(--app-canvas); font-weight: 500; }
.data-sync-table :deep(td.el-table__cell) { padding: 15px 0; }
.sync-plan-name { font-weight: 600; }
.sync-schedule { font-size: 12px; color: var(--app-muted); }
.sync-row-actions { flex-wrap: wrap; gap: 8px 12px; }
.sync-row-actions :deep(.el-button + .el-button) { margin-left: 0; }
.sync-dialog-hint { margin-bottom: 18px; }
.sync-run-summary :deep(.el-descriptions__table) { table-layout: fixed; }
.sync-run-summary :deep(.el-descriptions__label) { width: 125px; }
.sync-run-summary :deep(.el-descriptions__content) { overflow-wrap: anywhere; }
.run-table { margin-top: 18px; }
.full { width: 100%; }
@media (max-width: 1000px) { .data-sync-tables { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) {
  .sync-header { align-items: flex-start; flex-wrap: wrap; }
  .sync-heading { align-items: flex-start; }
  .sync-heading h2 { font-size: 20px; }
  .sync-icon { width: 38px; height: 38px; font-size: 21px; }
  .sync-overview > div { padding: 0 12px; }
  .sync-overview strong { font-size: 24px; }
  .sync-card-heading { flex-wrap: wrap; padding: 16px; }
  .sync-section { padding: 18px 16px; }
  .data-sync-grid, .data-sync-tables, .sync-flow { grid-template-columns: 1fr; }
  .sync-direction { justify-self: center; transform: rotate(90deg); width: 30px; height: 30px; }
  .data-sync-actions { padding: 16px; flex-wrap: wrap; }
}
</style>
