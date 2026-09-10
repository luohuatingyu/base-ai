<template>
  <div class="panel">
    <div class="section-head">
      <div>
        <h2>{{ t('servers.title') }}</h2>
        <p>{{ t('servers.description') }}</p>
      </div>
      <div class="table-actions">
        <el-button @click="load">{{ t('common.refresh') }}</el-button>
        <el-button
          v-if="auth.hasPermission('operations:server:create')"
          type="primary"
          @click="open()"
        >
          {{ t('servers.add') }}
        </el-button>
      </div>
    </div>
    <el-alert :title="t('servers.securityNotice')" type="warning" show-icon :closable="false" />
    <el-table :data="rows" v-loading="loading" class="servers-table">
      <el-table-column prop="name" :label="t('servers.name')" min-width="180" />
      <el-table-column prop="mode" :label="t('servers.mode')" width="110" />
      <el-table-column prop="host" :label="t('servers.host')" min-width="180" />
      <el-table-column :label="t('common.status')" width="100">
        <template #default="scope">
          {{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}
        </template>
      </el-table-column>
      <el-table-column prop="lastTestStatus" :label="t('servers.testStatus')" width="140" />
      <el-table-column :label="t('common.operation')" width="470" fixed="right">
        <template #default="scope">
          <div class="table-actions">
            <el-button
              v-if="auth.hasPermission('operations:server:test')"
              link
              type="primary"
              :disabled="!scope.row.enabled"
              @click="monitor(scope.row)"
            >
              {{ t('servers.monitor') }}
            </el-button>
            <el-button
              v-if="auth.hasPermission('operations:server:test')"
              link
              type="success"
              :disabled="!scope.row.enabled"
              @click="test(scope.row)"
            >
              {{ t('servers.test') }}
            </el-button>
            <el-button
              v-if="auth.hasPermission('operations:server:update')"
              link
              type="primary"
              @click="open(scope.row)"
            >
              {{ t('common.edit') }}
            </el-button>
            <el-button
              v-if="auth.hasPermission('operations:server:deploy')"
              link
              type="warning"
              :disabled="!scope.row.enabled"
              @click="deploy(scope.row)"
            >
              {{ t('servers.deploy') }}
            </el-button>
            <el-button
              v-if="auth.hasPermission('operations:server:logs')"
              link
              @click="history(scope.row)"
            >
              {{ t('servers.history') }}
            </el-button>
            <el-button
              v-if="auth.hasPermission('operations:server:delete')"
              link
              type="danger"
              @click="remove(scope.row)"
            >
              {{ t('common.delete') }}
            </el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('servers.edit') : t('servers.add')" width="min(860px, 94vw)">
      <el-form label-position="top">
        <div class="server-grid">
          <el-form-item :label="t('servers.name')">
            <el-input v-model="form.name" maxlength="120" />
          </el-form-item>
          <el-form-item :label="t('servers.mode')">
            <el-select v-model="form.mode" class="full" @change="applyModeDefaults">
              <el-option label="SSH" value="SSH" />
              <el-option label="LOCAL" value="LOCAL" />
            </el-select>
          </el-form-item>
        </div>
        <div v-if="form.mode === 'SSH'">
          <div class="server-grid">
            <el-form-item :label="t('servers.host')">
              <el-input v-model="form.host" />
            </el-form-item>
            <el-form-item :label="t('servers.port')">
              <el-input-number v-model="form.port" :min="1" :max="65535" class="full" />
            </el-form-item>
            <el-form-item :label="t('servers.username')">
              <el-input v-model="form.username" autocomplete="off" />
            </el-form-item>
            <el-form-item :label="t('servers.authType')">
              <el-select v-model="form.authType" class="full">
                <el-option label="KEY" value="KEY" />
                <el-option label="PASSWORD" value="PASSWORD" />
              </el-select>
            </el-form-item>
          </div>
          <el-form-item :label="t('servers.hostKey')">
            <el-input v-model="form.hostKey" :placeholder="t('servers.hostKeyPlaceholder')" autocomplete="off" />
          </el-form-item>
          <el-form-item v-if="form.authType === 'KEY'" :label="t('servers.privateKey')">
            <el-input v-model="form.privateKey" type="textarea" :rows="4" autocomplete="off" />
          </el-form-item>
          <el-form-item v-if="form.authType === 'KEY'" :label="t('servers.passphrase')">
            <el-input v-model="form.passphrase" type="password" show-password autocomplete="off" />
          </el-form-item>
          <el-form-item v-else :label="t('servers.password')">
            <el-input v-model="form.password" type="password" show-password autocomplete="off" />
          </el-form-item>
        </div>
        <div class="server-grid">
          <el-form-item :label="t('servers.workingDir')">
            <el-input v-model="form.workingDir" placeholder="/opt/base-ai" />
          </el-form-item>
          <el-form-item :label="t('servers.composeFile')">
            <el-input v-model="form.composeFile" placeholder="docker-compose.yml" />
          </el-form-item>
        </div>
        <el-form-item :label="t('common.status')">
          <el-switch v-model="form.enabled" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog
      v-model="monitorVisible"
      :title="t('servers.monitorTitle', { name: monitorServer?.name || '' })"
      width="min(1080px, 96vw)"
    >
      <div class="monitor-head">
        <span class="monitor-time">
          {{ monitorData?.collectedAt ? t('servers.collectedAt', { time: formatCollectedAt(monitorData.collectedAt) }) : t('servers.liveQueryHint') }}
        </span>
        <el-button :loading="monitorLoading" @click="refreshMonitor">{{ t('common.refresh') }}</el-button>
      </div>
      <div v-loading="monitorLoading" class="monitor-content">
        <el-alert
          v-if="monitorData?.status === 'FAILED'"
          :title="t('servers.monitorFailed')"
          :description="monitorErrorText(monitorData.error)"
          type="error"
          show-icon
          :closable="false"
        />
        <template v-if="monitorData?.host">
          <div class="metric-grid">
            <el-card shadow="never" class="metric-card">
              <span>{{ t('servers.cpu') }}</span>
              <strong>{{ formatPercent(monitorData.host.cpuUsagePercent) }}</strong>
              <small>{{ t('servers.cpuDetail', { cores: monitorData.host.cpuCores, load: formatLoad(monitorData.host) }) }}</small>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <span>{{ t('servers.memory') }}</span>
              <strong>{{ formatPercent(monitorData.host.memoryUsagePercent) }}</strong>
              <small>{{ formatUsage(monitorData.host.memoryUsedBytes, monitorData.host.memoryTotalBytes) }}</small>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <span>{{ t('servers.disk') }} · {{ monitorData.host.diskPath }}</span>
              <strong>{{ formatPercent(monitorData.host.diskUsagePercent) }}</strong>
              <small>{{ formatUsage(monitorData.host.diskUsedBytes, monitorData.host.diskTotalBytes) }}</small>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <span>{{ t('servers.uptime') }}</span>
              <strong>{{ formatDuration(monitorData.host.uptimeSeconds) }}</strong>
              <small>{{ t('servers.liveSnapshot') }}</small>
            </el-card>
          </div>
          <el-alert
            v-if="monitorData.containerError"
            :title="t('servers.containerUnavailable')"
            :description="monitorData.containerError"
            type="warning"
            show-icon
            :closable="false"
            class="container-alert"
          />
          <div class="container-head">
            <h3>{{ t('servers.containers') }}</h3>
            <span>{{ t('servers.containerCount', { count: monitorData.containers?.length || 0 }) }}</span>
          </div>
          <el-table :data="monitorData.containers || []" :empty-text="t('servers.noContainers')" max-height="420">
            <el-table-column prop="name" :label="t('servers.containerName')" min-width="150" />
            <el-table-column prop="image" :label="t('servers.containerImage')" min-width="220" show-overflow-tooltip />
            <el-table-column :label="t('servers.containerState')" width="120">
              <template #default="scope">
                <el-tag :type="containerStateType(scope.row.state)">{{ scope.row.state || '-' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column :label="t('servers.containerHealth')" width="130">
              <template #default="scope">
                <el-tag :type="containerHealthType(scope.row.health)">{{ containerHealthText(scope.row.health) }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="status" :label="t('servers.containerStatus')" min-width="220" show-overflow-tooltip />
          </el-table>
        </template>
      </div>
    </el-dialog>

    <el-dialog v-model="deployVisible" :title="t('servers.deploy')" width="420px">
      <el-form label-position="top">
        <el-form-item :label="t('servers.action')">
          <el-select v-model="deployForm.action" class="full">
            <el-option label="DEPLOY" value="DEPLOY" />
            <el-option v-if="auth.hasPermission('operations:server:rollback')" label="ROLLBACK" value="ROLLBACK" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('servers.revision')">
          <el-input v-model="deployForm.revision" :placeholder="t('servers.revisionPlaceholder')" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="deployVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="submitDeploy">{{ t('servers.deploy') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="historyVisible" :title="t('servers.history')" width="min(1040px, 94vw)">
      <el-table :data="deploymentRows" v-loading="historyLoading">
        <el-table-column prop="revision" :label="t('servers.revision')" min-width="150" />
        <el-table-column prop="action" :label="t('servers.action')" width="120" />
        <el-table-column prop="status" :label="t('common.status')" width="130" />
        <el-table-column prop="startedAt" :label="t('servers.startedAt')" min-width="180" />
        <el-table-column :label="t('servers.result')" min-width="260">
          <template #default="scope">
            <span class="deployment-result">{{ scope.row.errorMessage || scope.row.outputSummary || '-' }}</span>
          </template>
        </el-table-column>
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

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const deployVisible = ref(false)
const selectedId = ref(null)
const historyVisible = ref(false)
const historyLoading = ref(false)
const deploymentRows = ref([])
const monitorVisible = ref(false)
const monitorLoading = ref(false)
const monitorServer = ref(null)
const monitorData = ref(null)
const originalAuthType = ref('KEY')
const form = reactive(emptyForm())
const deployForm = reactive({ action: 'DEPLOY', revision: '' })

// 创建不携带敏感值且默认使用 SSH 的服务器表单。
function emptyForm() {
  return { id: null, name: '', mode: 'SSH', host: '', port: 22, username: '', authType: 'KEY', privateKey: '', password: '', passphrase: '', hostKey: '', workingDir: '/opt/base-ai', composeFile: 'docker-compose.yml', enabled: true }
}

// 加载当前用户可见的服务器配置。
async function load() {
  loading.value = true
  try {
    const { data } = await http.get('/servers')
    rows.value = data || []
  } catch (error) {
    showHttpError(error, 'servers.loadFailed')
  } finally {
    loading.value = false
  }
}

// 打开新增或编辑弹窗，并复用服务端返回的脱敏字段。
function open(row) {
  Object.assign(form, emptyForm(), row || {})
  originalAuthType.value = form.authType
  visible.value = true
}

// 切换执行模式时应用受控工作目录默认值。
function applyModeDefaults(mode) {
  if (mode === 'LOCAL') form.workingDir = '/workspace'
  else if (form.workingDir === '/workspace') form.workingDir = '/opt/base-ai'
}

// 校验页面手工维护的服务器名称、SSH 身份和新凭据。
function validateForm() {
  if (!form.name.trim()) return false
  if (form.mode !== 'SSH') return true
  if (!form.host.trim() || !form.username.trim() || !form.hostKey.trim() || !form.port) return false
  const requiresCredential = !form.id || form.authType !== originalAuthType.value
  if (requiresCredential && form.authType === 'KEY' && !form.privateKey.trim()) return false
  if (requiresCredential && form.authType === 'PASSWORD' && !form.password) return false
  return true
}

// 创建或更新服务器配置。
async function save() {
  if (!validateForm()) return ElMessage.warning(t('servers.formRequired'))
  saving.value = true
  try {
    const body = { ...form }
    if (form.id) await http.put(`/servers/${form.id}`, body)
    else await http.post('/servers', body)
    visible.value = false
    await load()
  } catch (error) {
    showHttpError(error, 'servers.saveFailed')
  } finally {
    saving.value = false
  }
}

// 通过隔离 Agent 校验本地或 SSH Compose 环境。
async function test(row) {
  try {
    const { data } = await http.post(`/servers/${row.id}/test`)
    ElMessage[data?.status === 'SUCCEEDED' ? 'success' : 'warning'](data?.error || data?.status || t('servers.testStatus'))
    await load()
  } catch (error) {
    showHttpError(error, 'servers.testFailed')
  }
}

// 打开资源监控弹窗并立即采集一份实时快照。
async function monitor(row) {
  monitorServer.value = row
  monitorData.value = null
  monitorVisible.value = true
  await refreshMonitor()
}

// 重新查询当前服务器资源，不保存历史监控数据。
async function refreshMonitor() {
  if (!monitorServer.value || monitorLoading.value) return
  monitorLoading.value = true
  try {
    const { data } = await http.get(`/servers/${monitorServer.value.id}/monitor`)
    monitorData.value = data
  } catch (error) {
    showHttpError(error, 'servers.monitorFailed')
  } finally {
    monitorLoading.value = false
  }
}

// 打开指定服务器的部署弹窗。
function deploy(row) {
  selectedId.value = row.id
  deployForm.action = 'DEPLOY'
  deployForm.revision = ''
  deployVisible.value = true
}

// 校验不可变版本标签并提交部署或回滚任务。
async function submitDeploy() {
  if (!/^[A-Za-z0-9_][A-Za-z0-9_.-]{0,127}$/.test(deployForm.revision)) return ElMessage.warning(t('servers.revisionInvalid'))
  try {
    await http.post(`/servers/${selectedId.value}/deploy`, deployForm)
    deployVisible.value = false
    ElMessage.success(t('servers.deployAccepted'))
  } catch (error) {
    showHttpError(error, 'servers.deployFailed')
  }
}

// 查询并展示服务器部署历史。
async function history(row) {
  historyVisible.value = true
  historyLoading.value = true
  deploymentRows.value = []
  try {
    const { data } = await http.get(`/servers/${row.id}/deployments`)
    deploymentRows.value = data || []
  } catch (error) {
    showHttpError(error, 'servers.loadFailed')
  } finally {
    historyLoading.value = false
  }
}

// 二次确认后软删除服务器配置。
async function remove(row) {
  try {
    await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm'))
    await http.delete(`/servers/${row.id}`)
    await load()
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showHttpError(error)
  }
}

// 将字节数格式化为适合指标卡片的可读值。
function formatBytes(value) {
  let size = Number(value || 0)
  const units = ['B', 'KiB', 'MiB', 'GiB', 'TiB']
  let unit = 0
  while (size >= 1024 && unit < units.length - 1) {
    size /= 1024
    unit += 1
  }
  return `${size.toFixed(unit === 0 ? 0 : 1)} ${units[unit]}`
}

// 格式化资源已用量和总量。
function formatUsage(used, total) {
  return `${formatBytes(used)} / ${formatBytes(total)}`
}

// 格式化百分比并处理空值。
function formatPercent(value) {
  return `${Number(value || 0).toFixed(1)}%`
}

// 格式化三档系统负载。
function formatLoad(host) {
  return [host.load1, host.load5, host.load15].map(value => Number(value || 0).toFixed(2)).join(' / ')
}

// 将运行秒数转换为天、小时和分钟。
function formatDuration(value) {
  const seconds = Math.max(0, Number(value || 0))
  const days = Math.floor(seconds / 86400)
  const hours = Math.floor((seconds % 86400) / 3600)
  const minutes = Math.floor((seconds % 3600) / 60)
  if (days > 0) return t('servers.durationDays', { days, hours })
  if (hours > 0) return t('servers.durationHours', { hours, minutes })
  return t('servers.durationMinutes', { minutes })
}

// 使用当前语言环境展示采集时间。
function formatCollectedAt(value) {
  const date = new Date(value)
  return Number.isNaN(date.getTime()) ? '-' : date.toLocaleString()
}

// 将 Backend 监控错误键转换为当前语言的安全提示。
function monitorErrorText(value) {
  const messages = {
    'server.agentNotConfigured': 'servers.agentNotConfigured',
    'server.agentInvalidResponse': 'servers.agentInvalidResponse',
    'server.monitorFailed': 'servers.monitorFailed',
  }
  return messages[value] ? t(messages[value]) : String(value || t('servers.monitorFailed'))
}

// 根据容器运行状态选择标签颜色。
function containerStateType(state) {
  const normalized = String(state || '').toUpperCase()
  if (normalized === 'RUNNING') return 'success'
  if (normalized === 'EXITED' || normalized === 'DEAD') return 'danger'
  if (normalized === 'RESTARTING' || normalized === 'PAUSED') return 'warning'
  return 'info'
}

// 根据容器健康检查结果选择标签颜色。
function containerHealthType(health) {
  const normalized = String(health || '').toUpperCase()
  if (normalized === 'HEALTHY') return 'success'
  if (normalized === 'UNHEALTHY') return 'danger'
  if (normalized === 'STARTING') return 'warning'
  return 'info'
}

// 将 Agent 健康状态映射为本地化文案。
function containerHealthText(health) {
  const normalized = String(health || 'NONE').toUpperCase()
  const key = ['HEALTHY', 'UNHEALTHY', 'STARTING'].includes(normalized) ? normalized : 'NONE'
  return t(`servers.health.${key}`)
}

onMounted(load)
</script>

<style scoped>
.servers-table { margin-top: 20px; }
.server-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 14px; }
.deployment-result { white-space: pre-wrap; overflow-wrap: anywhere; }
.full { width: 100%; }
.monitor-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; }
.monitor-time { color: var(--el-text-color-secondary); font-size: 13px; }
.monitor-content { min-height: 220px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.metric-card :deep(.el-card__body) { display: flex; flex-direction: column; gap: 9px; }
.metric-card span, .metric-card small { color: var(--el-text-color-secondary); }
.metric-card strong { color: var(--el-text-color-primary); font-size: 25px; font-variant-numeric: tabular-nums; }
.container-alert { margin-top: 16px; }
.container-head { display: flex; align-items: center; justify-content: space-between; margin: 22px 0 10px; }
.container-head h3 { margin: 0; font-size: 17px; }
.container-head span { color: var(--el-text-color-secondary); font-size: 13px; }
@media (max-width: 900px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) {
  .server-grid, .metric-grid { grid-template-columns: 1fr; }
  .monitor-head { align-items: flex-start; }
}
</style>
