<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('dataSync.title') }}</h2><p>{{ t('dataSync.description') }}</p></div>
      <el-button @click="load" :loading="loading">{{ t('common.refresh') }}</el-button>
    </div>

    <el-alert :title="t('dataSync.securityNotice')" type="warning" show-icon :closable="false" />
    <el-form v-if="auth.hasPermission(form.id ? 'operations:data-sync:update' : 'operations:data-sync:create')" label-position="top" class="data-sync-form">
      <div class="data-sync-grid">
        <el-form-item :label="t('dataSync.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('dataSync.strategy')"><el-select v-model="form.strategy" class="full"><el-option v-for="item in strategies" :key="item" :label="t(`dataSync.strategies.${item}`)" :value="item" /></el-select></el-form-item>
        <el-form-item :label="t('dataSync.source')"><el-select v-model="form.sourceConnectionId" class="full" @change="loadTables"><el-option v-for="item in connections" :key="item.id" :label="`${item.name} (${item.connectionType})`" :value="item.id" /></el-select></el-form-item>
        <el-form-item :label="t('dataSync.target')"><el-select v-model="form.targetConnectionId" class="full"><el-option v-for="item in connections" :key="item.id" :label="`${item.name} (${item.connectionType})`" :value="item.id" /></el-select></el-form-item>
      </div>
      <div class="data-sync-grid">
        <el-form-item :label="t('dataSync.schedule')"><el-input v-model="form.scheduleCron" :placeholder="t('dataSync.schedulePlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" :active-text="t('common.enabled')" /></el-form-item>
      </div>
      <el-form-item :label="t('dataSync.tables')">
        <el-checkbox-group v-model="selectedTables" class="data-sync-tables">
          <el-checkbox v-for="item in sourceTables" :key="item.name" :label="item.name">{{ item.schema ? `${item.schema}.` : '' }}{{ item.name }}</el-checkbox>
        </el-checkbox-group>
      </el-form-item>
      <el-checkbox v-if="form.strategy === 'FULL_REPLACE'" v-model="form.confirmDestructive">{{ t('dataSync.confirmDestructive') }}</el-checkbox>
      <div class="table-actions data-sync-actions">
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
        <el-button v-if="auth.hasPermission('operations:data-sync:preview')" :loading="previewing" @click="preview">{{ t('dataSync.preview') }}</el-button>
      </div>
    </el-form>

    <el-table :data="plans" v-loading="loading" class="data-sync-table">
      <el-table-column prop="name" :label="t('dataSync.name')" min-width="180" />
      <el-table-column prop="strategy" :label="t('dataSync.strategy')" width="150"><template #default="scope">{{ t(`dataSync.strategies.${scope.row.strategy}`) }}</template></el-table-column>
      <el-table-column :label="t('dataSync.tables')" width="100"><template #default="scope">{{ scope.row.tables?.length || 0 }}</template></el-table-column>
      <el-table-column prop="scheduleCron" :label="t('dataSync.schedule')" min-width="150" />
      <el-table-column prop="lastRunStatus" :label="t('dataSync.lastStatus')" width="140" />
      <el-table-column :label="t('common.operation')" width="390" fixed="right"><template #default="scope"><div class="table-actions"><el-button v-if="auth.hasPermission('operations:data-sync:update')" link @click="edit(scope.row)">{{ t('common.edit') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:run')" link type="primary" :disabled="running(scope.row)" @click="run(scope.row)">{{ t('dataSync.run') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:cancel')" link type="warning" :disabled="!running(scope.row)" @click="cancel(scope.row)">{{ t('dataSync.cancel') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:run') && ['FAILED', 'CANCELLED'].includes(scope.row.lastRunStatus)" link type="primary" @click="retry(scope.row)">{{ t('dataSync.retry') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:logs') && scope.row.lastRunId" link @click="showRun(scope.row)">{{ t('dataSync.details') }}</el-button><el-button v-if="auth.hasPermission('operations:data-sync:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button></div></template></el-table-column>
    </el-table>

    <el-dialog v-model="previewVisible" :title="t('dataSync.preview')" width="min(960px, 94vw)">
      <el-table :data="previewRows"><el-table-column prop="mapping.sourceTable" :label="t('dataSync.sourceTable')" /><el-table-column prop="mapping.targetTable" :label="t('dataSync.targetTable')" /><el-table-column prop="sourceRows" :label="t('dataSync.sourceRows')" /><el-table-column :label="t('dataSync.warnings')"><template #default="scope">{{ formatWarnings(scope.row.warnings) }}</template></el-table-column></el-table>
    </el-dialog>
    <el-dialog v-model="runVisible" :title="t('dataSync.details')" width="min(1040px, 94vw)">
      <el-descriptions v-if="runDetail" :column="3" border>
        <el-descriptions-item :label="t('common.status')">{{ runDetail.status }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.readRows')">{{ runDetail.readRows }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.writtenRows')">{{ runDetail.writtenRows }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.traceId')">{{ runDetail.traceId }}</el-descriptions-item>
        <el-descriptions-item :label="t('dataSync.error')">{{ runDetail.errorMessage || '-' }}</el-descriptions-item>
      </el-descriptions>
      <el-table :data="runDetail?.tables || []" v-loading="runLoading" class="run-table">
        <el-table-column prop="sourceTable" :label="t('dataSync.sourceTable')" min-width="160" />
        <el-table-column prop="targetTable" :label="t('dataSync.targetTable')" min-width="160" />
        <el-table-column prop="status" :label="t('common.status')" width="130" />
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
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n(), auth = useAuthStore()
const connections = ref([]), plans = ref([]), sourceTables = ref([]), selectedTables = ref([])
const loading = ref(false), saving = ref(false), previewing = ref(false), previewVisible = ref(false), previewRows = ref([])
const runVisible = ref(false), runLoading = ref(false), runDetail = ref(null)
const strategies = ['UPSERT', 'FULL_REPLACE', 'APPEND']
const form = reactive({ id: null, name: '', sourceConnectionId: null, targetConnectionId: null, strategy: 'UPSERT', scheduleCron: '', enabled: true, confirmDestructive: false })

// 加载当前用户可见的数据库连接和同步计划。
async function load() {
  loading.value = true
  try { const [connectionResponse, planResponse] = await Promise.all([http.get('/data-sync/connections'), http.get('/data-sync/plans')]); connections.value = connectionResponse.data || []; plans.value = planResponse.data || [] } catch (error) { showHttpError(error, 'dataSync.loadFailed') } finally { loading.value = false }
}

// 根据源连接读取可选表，并清除旧连接的选择。
async function loadTables() {
  if (!form.sourceConnectionId) { sourceTables.value = []; return }
  try { const { data } = await http.get(`/data-sync/connections/${form.sourceConnectionId}/tables`); sourceTables.value = data || []; selectedTables.value = [] } catch (error) { showHttpError(error, 'dataSync.loadFailed') }
}

// 将界面中的表选择转换为后端使用的同名表映射。
function mappings() { return selectedTables.value.map(name => ({ sourceSchema: '', sourceTable: name, targetSchema: '', targetTable: name, columns: [] })) }

// 执行只读结构预检并展示兼容性提示。
async function preview() {
  if (!form.sourceConnectionId || !form.targetConnectionId || !selectedTables.value.length) return ElMessage.warning(t('dataSync.formRequired'))
  previewing.value = true
  try { const { data } = await http.post('/data-sync/preview', { sourceConnectionId: form.sourceConnectionId, targetConnectionId: form.targetConnectionId, strategy: form.strategy, tables: mappings() }); previewRows.value = data?.tables || []; previewVisible.value = true } catch (error) { showHttpError(error, 'dataSync.previewFailed') } finally { previewing.value = false }
}

// 创建或更新同步计划。
async function save() {
  if (!form.name || !form.sourceConnectionId || !form.targetConnectionId || !selectedTables.value.length) return ElMessage.warning(t('dataSync.formRequired'))
  saving.value = true
  try { const body = { ...form, tables: mappings() }; if (form.id) await http.put(`/data-sync/plans/${form.id}`, body); else await http.post('/data-sync/plans', body); ElMessage.success(t('common.success')); await load() } catch (error) { showHttpError(error, 'dataSync.saveFailed') } finally { saving.value = false }
}

// 判断计划最近一次运行是否尚未结束。
function running(row) { return ['RUNNING', 'CANCEL_REQUESTED'].includes(row.lastRunStatus) }
// 将已保存计划回填到编辑表单。
async function edit(row) { Object.assign(form, { id: row.id, name: row.name, sourceConnectionId: row.sourceConnectionId, targetConnectionId: row.targetConnectionId, strategy: row.strategy, scheduleCron: row.scheduleCron || '', enabled: row.enabled, confirmDestructive: row.strategy === 'FULL_REPLACE' }); await loadTables(); selectedTables.value = (row.tables || []).map(table => table.sourceTable) }
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
.data-sync-form { margin: 18px 0 24px; padding: 18px; border: 1px solid #dfe7f2; border-radius: 12px; background: #f8fafc; }
.data-sync-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.data-sync-tables { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px 14px; }
.data-sync-actions { margin-top: 8px; }
.data-sync-table { margin-top: 20px; }
.run-table { margin-top: 18px; }
.full { width: 100%; }
@media (max-width: 760px) { .data-sync-grid, .data-sync-tables { grid-template-columns: 1fr; } }
</style>
