<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('workflowConnections.title') }}</h2><p>{{ t('workflowConnections.description') }}</p></div>
      <el-button v-if="auth.hasPermission('workflow:connection:create')" type="primary" @click="open()">{{ t('workflowConnections.add') }}</el-button>
    </div>
    <el-alert :title="t('workflowConnections.securityNotice')" type="warning" show-icon :closable="false" />
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('common.code')" min-width="150" />
      <el-table-column prop="name" :label="t('common.name')" min-width="160" />
      <el-table-column prop="connectionType" :label="t('common.type')" width="130" />
      <el-table-column :label="t('common.status')" width="100"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag></template></el-table-column>
      <el-table-column :label="t('common.operation')" width="220" fixed="right"><template #default="scope"><div class="table-actions">
        <el-button v-if="auth.hasPermission('workflow:connection:update')" link type="success" @click="test(scope.row)">{{ t('workflowConnections.test') }}</el-button>
        <el-button v-if="auth.hasPermission('workflow:connection:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
        <el-button v-if="auth.hasPermission('workflow:connection:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
      </div></template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('workflowConnections.edit') : t('workflowConnections.add')" width="min(760px, 94vw)">
      <el-form label-position="top">
        <div class="connection-grid"><el-form-item :label="t('common.code')"><el-input v-model="form.code" /></el-form-item><el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item></div>
        <el-form-item :label="t('common.type')"><el-select v-model="form.connectionType" class="full" @change="resetConfig"><el-option v-for="type in connectionTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item :label="t('workflowConnections.config')"><el-input v-model="form.configText" type="textarea" :rows="16" spellcheck="false" /></el-form-item>
        <div class="form-help">{{ t('workflowConnections.maskHelp') }}</div>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([]), visible = ref(false)
const connectionTypes = ['MYSQL', 'POSTGRESQL', 'REDIS', 'S3', 'KAFKA', 'RABBITMQ', 'WEBHOOK']
const defaults = {
  MYSQL: { url: 'jdbc:mysql://host:3306/database', username: '', password: '', allowWrite: false },
  POSTGRESQL: { url: 'jdbc:postgresql://host:5432/database', username: '', password: '', allowWrite: false },
  REDIS: { uri: 'redis://host:6379/0', keyPrefix: '', allowWrite: false },
  S3: { endpoint: '', region: 'us-east-1', accessKey: '', secretKey: '', bucket: '', keyPrefix: '', pathStyle: true, allowDelete: false },
  KAFKA: { bootstrapServers: '', topicPrefix: '', securityProtocol: '', saslMechanism: '', username: '', password: '' },
  RABBITMQ: { uri: 'amqp://user:password@host:5672/vhost', exchangePrefix: '', queuePrefix: '' },
  WEBHOOK: { url: '', method: 'POST', testMethod: 'GET', headers: {}, secret: '' }
}
const form = reactive(emptyForm())

/** 加载当前用户可见的脱敏连接。 */
async function load() { rows.value = (await http.get('/workflow/connections')).data || [] }
/** 打开连接编辑器并保留服务端脱敏占位符。 */
function open(row) { Object.assign(form, emptyForm(), row || {}, { configText: JSON.stringify(row?.config || defaults.MYSQL, null, 2) }); visible.value = true }
/** 切换类型时使用安全默认配置。 */
function resetConfig(type) { form.configText = JSON.stringify(defaults[type] || {}, null, 2) }
/** 校验 JSON 后创建或更新连接。 */
async function save() {
  let config
  try { config = JSON.parse(form.configText || '{}') } catch { return ElMessage.warning(t('workflowConnections.invalidJson')) }
  if (!form.code.trim() || !form.name.trim()) return ElMessage.warning(t('workflowConnections.required'))
  const command = { code: form.code, name: form.name, connectionType: form.connectionType, config, enabled: form.enabled }
  try {
    if (form.id) await http.put(`/workflow/connections/${form.id}`, command)
    else await http.post('/workflow/connections', command)
    visible.value = false; await load(); ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
}
/** 执行无副作用连接测试。 */
async function test(row) { try { await http.post(`/workflow/connections/${row.id}/test`); ElMessage.success(t('workflowConnections.connected')) } catch (error) { showHttpError(error, 'workflowConnections.testFailed') } }
/** 删除未被工作流引用的连接。 */
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm')); await http.delete(`/workflow/connections/${row.id}`); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
/** 创建空连接表单。 */
function emptyForm() { return { id: null, code: '', name: '', connectionType: 'MYSQL', configText: JSON.stringify(defaults.MYSQL, null, 2), enabled: true } }
onMounted(load)
</script>

<style scoped>
.panel { display: grid; gap: 18px; }
.connection-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-help { margin: -8px 0 18px; color: var(--app-muted); font-size: 13px; }
@media (max-width: 640px) { .connection-grid { grid-template-columns: 1fr; gap: 0; } }
</style>
