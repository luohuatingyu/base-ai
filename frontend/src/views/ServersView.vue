<template>
  <div class="panel">
    <div class="section-head"><div><h2>{{ t('servers.title') }}</h2><p>{{ t('servers.description') }}</p></div><div class="table-actions"><el-button @click="load">{{ t('common.refresh') }}</el-button><el-button v-if="auth.hasPermission('server:create')" type="primary" @click="open()">{{ t('common.add') }}</el-button></div></div>
    <el-alert :title="t('servers.securityNotice')" type="warning" show-icon :closable="false" />
    <el-table :data="rows" v-loading="loading" class="servers-table"><el-table-column prop="name" :label="t('servers.name')" min-width="180"/><el-table-column prop="mode" :label="t('servers.mode')" width="110"/><el-table-column prop="host" :label="t('servers.host')" min-width="180"/><el-table-column :label="t('common.status')" width="100"><template #default="scope">{{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}</template></el-table-column><el-table-column prop="lastTestStatus" :label="t('servers.testStatus')" width="140"/><el-table-column :label="t('common.operation')" width="390" fixed="right"><template #default="scope"><div class="table-actions"><el-button v-if="auth.hasPermission('server:test')" link type="success" :disabled="!scope.row.enabled" @click="test(scope.row)">{{ t('servers.test') }}</el-button><el-button v-if="auth.hasPermission('server:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button><el-button v-if="auth.hasPermission('server:deploy')" link type="warning" :disabled="!scope.row.enabled" @click="deploy(scope.row)">{{ t('servers.deploy') }}</el-button><el-button v-if="auth.hasPermission('server:logs')" link @click="history(scope.row)">{{ t('servers.history') }}</el-button><el-button v-if="auth.hasPermission('server:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button></div></template></el-table-column></el-table>

    <el-dialog v-model="visible" :title="form.id ? t('servers.edit') : t('servers.add')" width="min(860px, 94vw)"><el-form label-position="top"><div class="server-grid"><el-form-item :label="t('servers.name')"><el-input v-model="form.name"/></el-form-item><el-form-item :label="t('servers.mode')"><el-select v-model="form.mode" class="full" @change="applyModeDefaults"><el-option label="LOCAL" value="LOCAL"/><el-option label="SSH" value="SSH"/></el-select></el-form-item></div><div v-if="form.mode === 'SSH'"><div class="server-grid"><el-form-item :label="t('servers.host')"><el-input v-model="form.host"/></el-form-item><el-form-item :label="t('servers.port')"><el-input-number v-model="form.port" :min="1" :max="65535" class="full"/></el-form-item><el-form-item :label="t('servers.username')"><el-input v-model="form.username"/></el-form-item><el-form-item :label="t('servers.authType')"><el-select v-model="form.authType" class="full"><el-option label="KEY" value="KEY"/><el-option label="PASSWORD" value="PASSWORD"/></el-select></el-form-item></div><el-form-item :label="t('servers.hostKey')"><el-input v-model="form.hostKey" :placeholder="t('servers.hostKeyPlaceholder')"/></el-form-item><el-form-item v-if="form.authType === 'KEY'" :label="t('servers.privateKey')"><el-input v-model="form.privateKey" type="textarea" :rows="4" autocomplete="off"/></el-form-item><el-form-item v-if="form.authType === 'KEY'" :label="t('servers.passphrase')"><el-input v-model="form.passphrase" type="password" show-password autocomplete="off"/></el-form-item><el-form-item v-else :label="t('servers.password')"><el-input v-model="form.password" type="password" show-password autocomplete="off"/></el-form-item></div><div class="server-grid"><el-form-item :label="t('servers.workingDir')"><el-input v-model="form.workingDir" placeholder="/opt/base-ai"/></el-form-item><el-form-item :label="t('servers.composeFile')"><el-input v-model="form.composeFile" placeholder="docker-compose.yml"/></el-form-item></div><el-form-item :label="t('common.status')"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template></el-dialog>
    <el-dialog v-model="deployVisible" :title="t('servers.deploy')" width="420px"><el-form label-position="top"><el-form-item :label="t('servers.action')"><el-select v-model="deployForm.action" class="full"><el-option label="DEPLOY" value="DEPLOY"/><el-option v-if="auth.hasPermission('server:rollback')" label="ROLLBACK" value="ROLLBACK"/></el-select></el-form-item><el-form-item :label="t('servers.revision')"><el-input v-model="deployForm.revision" :placeholder="t('servers.revisionPlaceholder')"/></el-form-item></el-form><template #footer><el-button @click="deployVisible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="submitDeploy">{{ t('servers.deploy') }}</el-button></template></el-dialog>
    <el-dialog v-model="historyVisible" :title="t('servers.history')" width="min(1040px, 94vw)"><el-table :data="deploymentRows" v-loading="historyLoading"><el-table-column prop="revision" :label="t('servers.revision')" min-width="150"/><el-table-column prop="action" :label="t('servers.action')" width="120"/><el-table-column prop="status" :label="t('common.status')" width="130"/><el-table-column prop="startedAt" :label="t('servers.startedAt')" min-width="180"/><el-table-column :label="t('servers.result')" min-width="260"><template #default="scope"><span class="deployment-result">{{ scope.row.errorMessage || scope.row.outputSummary || '-' }}</span></template></el-table-column></el-table></el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n(), auth = useAuthStore()
const rows = ref([]), loading = ref(false), visible = ref(false), deployVisible = ref(false), selectedId = ref(null)
const historyVisible = ref(false), historyLoading = ref(false), deploymentRows = ref([])
const form = reactive(emptyForm()), deployForm = reactive({ action: 'DEPLOY', revision: '' })
// 创建不携带敏感值的默认服务器表单。
function emptyForm() { return { id: null, name: '', mode: 'LOCAL', host: '', port: 22, username: '', authType: 'KEY', privateKey: '', password: '', passphrase: '', hostKey: '', workingDir: '/workspace', composeFile: 'docker-compose.yml', enabled: true } }
// 加载当前用户可见的服务器配置。
async function load() { loading.value = true; try { const { data } = await http.get('/servers'); rows.value = data || [] } catch (error) { showHttpError(error, 'servers.loadFailed') } finally { loading.value = false } }
// 打开新增或编辑弹窗，并复用服务端返回的脱敏字段。
function open(row) { Object.assign(form, emptyForm(), row || {}); visible.value = true }
// 切换执行模式时应用受控工作目录默认值。
function applyModeDefaults(mode) { if (mode === 'LOCAL') form.workingDir = '/workspace'; else if (form.workingDir === '/workspace') form.workingDir = '/opt/base-ai' }
// 创建或更新服务器配置。
async function save() { try { const body = { ...form }; if (form.id) await http.put(`/servers/${form.id}`, body); else await http.post('/servers', body); visible.value = false; await load() } catch (error) { showHttpError(error, 'servers.saveFailed') } }
// 通过隔离 Agent 校验本地或 SSH Compose 环境。
async function test(row) { try { const { data } = await http.post(`/servers/${row.id}/test`); ElMessage[data?.status === 'SUCCEEDED' ? 'success' : 'warning'](data?.error || data?.status || t('servers.testStatus')); await load() } catch (error) { showHttpError(error, 'servers.testFailed') } }
// 打开指定服务器的部署弹窗。
function deploy(row) { selectedId.value = row.id; deployForm.action = 'DEPLOY'; deployForm.revision = ''; deployVisible.value = true }
// 校验不可变版本标签并提交部署或回滚任务。
async function submitDeploy() { if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(deployForm.revision)) return ElMessage.warning(t('servers.revisionInvalid')); try { await http.post(`/servers/${selectedId.value}/deploy`, deployForm); deployVisible.value = false; ElMessage.success(t('servers.deployAccepted')) } catch (error) { showHttpError(error, 'servers.deployFailed') } }
// 查询并展示服务器部署历史。
async function history(row) { historyVisible.value = true; historyLoading.value = true; deploymentRows.value = []; try { const { data } = await http.get(`/servers/${row.id}/deployments`); deploymentRows.value = data || [] } catch (error) { showHttpError(error, 'servers.loadFailed') } finally { historyLoading.value = false } }
// 二次确认后软删除服务器配置。
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm')); await http.delete(`/servers/${row.id}`); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
onMounted(load)
</script>

<style scoped>
.servers-table { margin-top: 20px; }
.server-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.deployment-result { white-space: pre-wrap; overflow-wrap: anywhere; }
.full { width: 100%; }
@media (max-width: 760px) { .server-grid { grid-template-columns: 1fr; } }
</style>
