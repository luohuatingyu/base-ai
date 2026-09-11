<template>
  <div class="panel">
    <div class="section-head">
      <div>
        <h2>{{ t('servers.title') }}</h2>
        <p>{{ t('servers.description') }}</p>
      </div>
      <div class="table-actions">
        <el-button class="credential-entry" type="success" plain @click="credentialsVisible = true"><el-icon><Key /></el-icon>{{ t('serverCredentials.title') }}<el-tag size="small" effect="dark">安全</el-tag></el-button>
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
    <ServerCredentialManager v-model="credentialsVisible" @changed="loadCredentials" />
    <el-table :data="rows" v-loading="loading" class="servers-table">
      <el-table-column prop="name" :label="t('servers.name')" min-width="180" />
      <el-table-column prop="mode" :label="t('servers.mode')" width="110" />
      <el-table-column prop="host" :label="t('servers.host')" min-width="180" />
      <el-table-column :label="t('servers.operatingSystem')" min-width="260">
        <template #default="scope">
          <div v-if="scope.row.systemInfo" class="server-system">
            <span class="system-icon" :data-system="systemIcon(scope.row.systemInfo)" role="img" :aria-label="scope.row.systemInfo.family">
              <svg v-if="systemIcon(scope.row.systemInfo) === 'aliyun'" viewBox="0 0 32 32" aria-hidden="true"><path d="M12 7H6L2 11v10l4 4h6v-4H7V11h5zm8 0h6l4 4v10l-4 4h-6v-4h5V11h-5zM11 14h10v4H11z" fill="#ff6a00" /></svg>
              <svg v-else-if="systemIcon(scope.row.systemInfo) === 'ubuntu'" viewBox="0 0 32 32" aria-hidden="true"><circle cx="16" cy="16" r="15" fill="#e95420" /><circle cx="16" cy="16" r="7" fill="none" stroke="white" stroke-width="3" /><g fill="white" stroke="#e95420" stroke-width="2"><circle cx="7" cy="16" r="4" /><circle cx="21" cy="8" r="4" /><circle cx="21" cy="24" r="4" /></g></svg>
              <span v-else-if="systemIcon(scope.row.systemInfo) === 'linux'" aria-hidden="true">🐧</span>
              <span v-else-if="systemIcon(scope.row.systemInfo) === 'macos'" aria-hidden="true">🍎</span>
              <span v-else-if="systemIcon(scope.row.systemInfo) === 'windows'" aria-hidden="true">🪟</span>
              <el-icon v-else><Monitor /></el-icon>
            </span>
            <details class="system-details">
              <summary>{{ scope.row.systemInfo.name }}<small v-if="scope.row.systemInfo.version">{{ t('servers.osVersion') }} {{ scope.row.systemInfo.version }}</small></summary>
              <div>{{ t('servers.osKernel') }}: {{ scope.row.systemInfo.kernel || '-' }}</div>
              <div>{{ t('servers.osArchitecture') }}: {{ scope.row.systemInfo.architecture || '-' }}</div>
              <div>{{ t('servers.osDetectedAt') }}: {{ formatCollectedAt(scope.row.systemInfo.detectedAt) }}</div>
            </details>
          </div>
          <span v-else class="system-unknown">{{ t('servers.osNotDetected') }}</span>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="100">
        <template #default="scope">
          {{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}
        </template>
      </el-table-column>
      <el-table-column :label="t('servers.testStatus')" min-width="160">
        <template #default="scope">
          <div>{{ scope.row.lastTestStatus || '-' }}</div>
          <small v-if="scope.row.lastTestError" class="server-test-error">{{ monitorErrorText(scope.row.lastTestError) }}</small>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" width="330" fixed="right">
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
              :loading="testingIds.has(scope.row.id)"
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

    <el-dialog
      v-model="visible"
      class="server-editor-dialog"
      :title="form.id ? t('servers.edit') : t('servers.add')"
      width="min(920px, 94vw)"
      top="5vh"
    >
      <div class="dialog-intro server-editor-intro">
        <span class="dialog-intro-icon"><el-icon><Connection /></el-icon></span>
        <div>
          <strong>{{ form.id ? t('servers.edit') : t('servers.add') }}</strong>
          <p>{{ t('servers.editorDescription') }}</p>
        </div>
        <el-tag effect="plain">{{ form.mode }}</el-tag>
      </div>

      <el-form label-position="top" class="server-editor-form">
        <section class="config-section">
          <div class="config-section-head">
            <span><el-icon><SetUp /></el-icon></span>
            <div>
              <h3>{{ t('servers.basicSection') }}</h3>
              <p>{{ t('servers.basicSectionHelp') }}</p>
            </div>
          </div>
          <div class="server-grid server-grid--basic">
            <el-form-item :label="t('servers.name')">
              <el-input v-model="form.name" maxlength="120" />
            </el-form-item>
            <div class="status-setting">
              <div>
                <strong>{{ t('common.status') }}</strong>
                <small>{{ t('servers.enabledHelp') }}</small>
              </div>
              <el-switch v-model="form.enabled" />
            </div>
          </div>
          <el-form-item :label="t('servers.mode')" class="section-last-field">
            <div class="selection-cards selection-cards--mode">
              <button
                type="button"
                class="selection-card"
                :class="{ 'is-active': form.mode === 'SSH' }"
                @click="form.mode = 'SSH'"
              >
                <span class="selection-card-icon"><el-icon><Connection /></el-icon></span>
                <span>
                  <strong>SSH</strong>
                  <small>{{ t('servers.sshModeDescription') }}</small>
                </span>
                <span class="selection-card-check"><el-icon><Check /></el-icon></span>
              </button>
              <button
                type="button"
                class="selection-card"
                :class="{ 'is-active': form.mode === 'LOCAL' }"
                @click="form.mode = 'LOCAL'"
              >
                <span class="selection-card-icon"><el-icon><Monitor /></el-icon></span>
                <span>
                  <strong>LOCAL</strong>
                  <small>{{ t('servers.localModeDescription') }}</small>
                </span>
                <span class="selection-card-check"><el-icon><Check /></el-icon></span>
              </button>
            </div>
          </el-form-item>
        </section>

        <template v-if="form.mode === 'SSH'">
          <section class="config-section">
            <div class="config-section-head">
              <span><el-icon><Location /></el-icon></span>
              <div>
                <h3>{{ t('servers.connectionSection') }}</h3>
                <p>{{ t('servers.connectionSectionHelp') }}</p>
              </div>
            </div>
            <div class="server-grid server-grid--connection">
              <el-form-item :label="t('servers.host')">
                <el-input v-model="form.host" :placeholder="t('servers.hostPlaceholder')" />
                <div class="field-help">{{ t('servers.hostHelp') }}</div>
              </el-form-item>
              <el-form-item :label="t('servers.port')">
                <el-input-number v-model="form.port" :min="1" :max="65535" class="full" />
                <div class="field-help">{{ t('servers.portHelp') }}</div>
              </el-form-item>
              <el-form-item :label="t('servers.username')">
                <el-input v-model="form.username" :disabled="!!selectedCredential?.username" autocomplete="off" :placeholder="t('servers.usernamePlaceholder')" />
                <div class="field-help">{{ t('servers.usernameHelp') }}</div>
              </el-form-item>
            </div>
          </section>

          <section class="config-section config-section--security">
            <div class="config-section-head">
              <span><el-icon><Lock /></el-icon></span>
              <div>
                <h3>{{ t('servers.securitySection') }}</h3>
                <p>{{ t('servers.securitySectionHelp') }}</p>
              </div>
            </div>
            <el-form-item :label="t('servers.authType')">
              <div class="selection-cards selection-cards--auth">
                <button
                  type="button"
                  class="selection-card"
                  :class="{ 'is-active': form.authType === 'KEY' }"
                  @click="form.authType = 'KEY'"
                >
                  <span class="selection-card-icon"><el-icon><Key /></el-icon></span>
                  <span>
                    <strong>{{ t('servers.keyAuth') }}</strong>
                    <small>{{ t('servers.keyAuthDescription') }}</small>
                  </span>
                  <span class="selection-card-check"><el-icon><Check /></el-icon></span>
                </button>
                <button
                  type="button"
                  class="selection-card"
                  :class="{ 'is-active': form.authType === 'PASSWORD' }"
                  @click="form.authType = 'PASSWORD'"
                >
                  <span class="selection-card-icon"><el-icon><Unlock /></el-icon></span>
                  <span>
                    <strong>{{ t('servers.passwordAuth') }}</strong>
                    <small>{{ t('servers.passwordAuthDescription') }}</small>
                  </span>
                  <span class="selection-card-check"><el-icon><Check /></el-icon></span>
                </button>
                <button
                  type="button"
                  class="selection-card"
                  :class="{ 'is-active': form.authType === 'KEY_PASSWORD' }"
                  @click="form.authType = 'KEY_PASSWORD'"
                >
                  <span class="selection-card-icon"><el-icon><Key /></el-icon></span>
                  <span>
                    <strong>{{ t('servers.combinedAuth') }}</strong>
                    <small>{{ t('servers.combinedAuthDescription') }}</small>
                  </span>
                  <span class="selection-card-check"><el-icon><Check /></el-icon></span>
                </button>
              </div>
            </el-form-item>

            <el-form-item :label="t('serverCredentials.select')">
              <el-select v-model="form.credentialId" clearable filterable :loading="credentialsLoading" @change="selectCredential">
                <el-option v-for="credential in selectableCredentials" :key="credential.id" :value="credential.id" :label="`${credential.label} (${credential.username || credential.type})`" />
              </el-select>
              <el-button link type="primary" @click="credentialsVisible = true">{{ t('serverCredentials.title') }}</el-button>
              <div class="field-help">{{ t('serverCredentials.selectionHint') }}</div>
            </el-form-item>
            <template v-if="!form.credentialId && form.id">
            <div v-if="['KEY', 'KEY_PASSWORD'].includes(form.authType)" class="credential-panel">
              <el-form-item :label="t('servers.privateKey')">
                <div class="private-key-editor">
                  <div class="private-key-toolbar">
                    <div>
                      <span class="private-key-toolbar-icon"><el-icon><Document /></el-icon></span>
                      <span>
                        <strong>{{ privateKeyFileName || t('servers.privateKeySource') }}</strong>
                        <small>{{ form.id ? t('servers.savedCredentialHint') : t('servers.privateKeyHelp') }}</small>
                      </span>
                    </div>
                    <div class="private-key-file-row">
                      <input ref="privateKeyFileInput" class="hidden-file-input" type="file" @change="onPrivateKeyFileSelected" />
                      <el-button plain type="primary" @click="openPrivateKeyFilePicker">
                        <el-icon><Upload /></el-icon>
                        {{ t('servers.selectPrivateKeyFile') }}
                      </el-button>
                    </div>
                  </div>
                  <el-input
                    v-model="form.privateKey"
                    type="textarea"
                    :rows="5"
                    :placeholder="t('servers.privateKeyPlaceholder')"
                    autocomplete="off"
                  />
                </div>
              </el-form-item>
              <el-form-item :label="t('servers.passphrase')" class="section-last-field">
                <el-input v-model="form.passphrase" type="password" show-password autocomplete="off" :placeholder="t('servers.passphrasePlaceholder')">
                  <template #prefix><el-icon><Lock /></el-icon></template>
                </el-input>
                <div class="field-help">{{ t('servers.passphraseHelp') }}</div>
              </el-form-item>
            </div>
            <div v-if="['PASSWORD', 'KEY_PASSWORD'].includes(form.authType)" class="credential-panel">
              <el-form-item :label="t('servers.password')" class="section-last-field">
                <el-input v-model="form.password" type="password" show-password autocomplete="off" :placeholder="t('servers.passwordPlaceholder')">
                  <template #prefix><el-icon><Lock /></el-icon></template>
                </el-input>
                <div class="field-help">{{ form.id ? t('servers.savedCredentialHint') : t('servers.passwordHelp') }}</div>
              </el-form-item>
            </div>
            </template>
          </section>
        </template>

        <div v-else class="local-mode-notice">
          <span><el-icon><Monitor /></el-icon></span>
          <div><strong>LOCAL</strong><p>{{ t('servers.localModeDescription') }}</p></div>
        </div>
      </el-form>
      <template #footer>
        <div class="dialog-footer-content">
          <span class="secure-submit-hint"><el-icon><Lock /></el-icon>{{ t('servers.secureSaveHint') }}</span>
          <div>
            <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
            <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
          </div>
        </div>
      </template>
    </el-dialog>

    <el-dialog
      v-model="monitorVisible"
      :title="t('servers.monitorTitle', { name: monitorServer?.name || '' })"
      class="server-monitor-dialog"
      width="min(1080px, 96vw)"
      top="5vh"
    >
      <div class="monitor-hero">
        <span class="monitor-hero-icon"><el-icon><DataLine /></el-icon></span>
        <div>
          <div class="monitor-identity">
            <strong>{{ monitorServer?.name || '-' }}</strong>
            <el-tag size="small" type="success" effect="light">{{ t('servers.liveStatus') }}</el-tag>
          </div>
          <p>{{ monitorServerAddress }}</p>
          <small>
            {{ monitorData?.collectedAt ? t('servers.collectedAt', { time: formatCollectedAt(monitorData.collectedAt) }) : t('servers.liveQueryHint') }}
          </small>
        </div>
        <el-button :loading="monitorLoading" @click="refreshMonitor">
          <el-icon><Refresh /></el-icon>
          {{ t('common.refresh') }}
        </el-button>
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
              <div class="metric-card-head"><span><el-icon><Cpu /></el-icon></span>{{ t('servers.cpu') }}</div>
              <strong>{{ formatPercent(monitorData.host.cpuUsagePercent) }}</strong>
              <small>{{ t('servers.cpuDetail', { cores: monitorData.host.cpuCores, load: formatLoad(monitorData.host) }) }}</small>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <div class="metric-card-head"><span><el-icon><Coin /></el-icon></span>{{ t('servers.memory') }}</div>
              <strong>{{ formatPercent(monitorData.host.memoryUsagePercent) }}</strong>
              <small>{{ formatUsage(monitorData.host.memoryUsedBytes, monitorData.host.memoryTotalBytes) }}</small>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <div class="metric-card-head"><span><el-icon><Folder /></el-icon></span>{{ t('servers.disk') }} · {{ monitorData.host.diskPath }}</div>
              <strong>{{ formatPercent(monitorData.host.diskUsagePercent) }}</strong>
              <small>{{ formatUsage(monitorData.host.diskUsedBytes, monitorData.host.diskTotalBytes) }}</small>
            </el-card>
            <el-card shadow="never" class="metric-card">
              <div class="metric-card-head"><span><el-icon><Timer /></el-icon></span>{{ t('servers.uptime') }}</div>
              <strong>{{ formatDuration(monitorData.host.uptimeSeconds) }}</strong>
              <small>{{ t('servers.liveSnapshot') }}</small>
            </el-card>
          </div>
        </template>
      </div>
    </el-dialog>

  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import {
  Check,
  Coin,
  Connection,
  Cpu,
  DataLine,
  Document,
  Folder,
  Key,
  Location,
  Lock,
  Monitor,
  Refresh,
  SetUp,
  Timer,
  Unlock,
  Upload,
} from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { readPrivateKeyFile } from '../utils/serverCredentials'
import ServerCredentialManager from '../components/ServerCredentialManager.vue'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const loading = ref(false)
const visible = ref(false)
const saving = ref(false)
const testingIds = ref(new Set())
const monitorVisible = ref(false)
const monitorLoading = ref(false)
const monitorServer = ref(null)
const monitorData = ref(null)
const originalAuthType = ref('KEY')
const privateKeyFileInput = ref(null)
const privateKeyFileName = ref('')
const form = reactive(emptyForm())
const credentialsVisible = ref(false)
const credentialsLoading = ref(false)
const credentials = ref([])
const selectedCredential = computed(() => credentials.value.find(credential => credential.id === form.credentialId))
const selectableCredentials = computed(() => credentials.value.filter(credential => credential.enabled
  && credential.ownerUserId === (form.ownerUserId || auth.user?.id)
  && (!['KEY', 'KEY_PASSWORD'].includes(form.authType) || credential.hasPrivateKey)
  && (!['PASSWORD', 'KEY_PASSWORD'].includes(form.authType) || credential.hasPassword)))

// 加载可复用凭据元数据，绝不读取下拉选项的秘密。
async function loadCredentials() {
  credentialsLoading.value = true
  try { credentials.value = (await http.get('/server-credentials')).data || [] }
  catch (error) { credentials.value = []; showHttpError(error) }
  finally { credentialsLoading.value = false }
}

// 选择凭据时带入账号，清除旧的临时秘密输入。
function selectCredential(id) {
  const credential = credentials.value.find(item => item.id === id)
  if (credential?.username) form.username = credential.username
  form.privateKey = ''; form.password = ''; form.passphrase = ''
}

// 组合当前监控服务器的连接地址，避免在本地模式展示无意义端口。
const monitorServerAddress = computed(() => {
  if (!monitorServer.value) return '-'
  if (monitorServer.value.mode === 'LOCAL') return 'LOCAL'
  return `${monitorServer.value.username || '-'}@${monitorServer.value.host || '-'}:${monitorServer.value.port || 22}`
})

// 创建不携带敏感值且默认使用 SSH 的服务器表单。
function emptyForm() {
  return { id: null, name: '', mode: 'SSH', host: '', port: 22, username: '', authType: 'KEY', credentialId: null, privateKey: '', password: '', passphrase: '', hostKey: '', workingDir: '', composeFile: '', enabled: true }
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
  privateKeyFileName.value = ''
  visible.value = true
  loadCredentials()
}

// 打开系统文件选择器，让用户从本机选择 SSH 私钥文件。
function openPrivateKeyFilePicker() {
  privateKeyFileInput.value?.click()
}

// 读取本地私钥文件到表单内存，文件本身和本地路径不会提交。
async function onPrivateKeyFileSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  try {
    form.privateKey = await readPrivateKeyFile(file)
    privateKeyFileName.value = file.name
    ElMessage.success(t('servers.privateKeyFileLoaded', { name: file.name }))
  } catch (error) {
    privateKeyFileName.value = ''
    ElMessage.warning(t(privateKeyFileErrorKey(error)))
  }
}

// 将私钥文件读取错误转换为不包含本地信息的提示文案。
function privateKeyFileErrorKey(error) {
  if (error?.message === 'PRIVATE_KEY_FILE_EMPTY') return 'servers.privateKeyFileEmpty'
  if (error?.message === 'PRIVATE_KEY_FILE_TOO_LARGE') return 'servers.privateKeyFileTooLarge'
  return 'servers.privateKeyFileReadFailed'
}

// 校验页面手工维护的服务器名称、SSH 身份和新凭据。
function validateForm() {
  if (!form.name.trim()) return false
  if (form.mode !== 'SSH') return true
  if (!form.host.trim() || !form.username.trim() || !form.port) return false
  if (form.credentialId) return selectableCredentials.value.some(credential => credential.id === form.credentialId)
  if (!form.id) return false
  const requiresCredential = !form.id || form.authType !== originalAuthType.value
  if (requiresCredential && ['KEY', 'KEY_PASSWORD'].includes(form.authType) && !form.privateKey.trim()) return false
  if (requiresCredential && ['PASSWORD', 'KEY_PASSWORD'].includes(form.authType) && !form.password.trim()) return false
  return true
}

// 创建或更新服务器配置。
async function save() {
  if (!validateForm()) return ElMessage.warning(t('servers.formRequired'))
  saving.value = true
  try {
    const body = { ...form, credentialId: form.credentialId || null }
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

// 通过隔离 Agent 校验本地可用性或 SSH 登录，并阻止重复请求。
async function test(row) {
  if (testingIds.value.has(row.id)) return
  testingIds.value.add(row.id)
  try {
    const { data } = await http.post(`/servers/${row.id}/test`)
    ElMessage[data?.status === 'SUCCEEDED' ? 'success' : 'warning'](data?.error ? monitorErrorText(data.error) : data?.status || t('servers.testStatus'))
    await load()
  } catch (error) {
    showHttpError(error, 'servers.testFailed')
  } finally {
    testingIds.value.delete(row.id)
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
    if (data?.systemInfo) monitorServer.value.systemInfo = data.systemInfo
  } catch (error) {
    showHttpError(error, 'servers.monitorFailed')
  } finally {
    monitorLoading.value = false
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

// 根据探测结果选择本地图标，未知发行版回退到系统家族图标。
function systemIcon(identity) {
  if (identity?.family === 'Linux') {
    if (['alinux', 'alios', 'anolis'].includes(identity.id)) return 'aliyun'
    if (identity.id === 'ubuntu') return 'ubuntu'
    return 'linux'
  }
  if (identity?.family === 'Darwin') return 'macos'
  if (/^(Windows|MINGW|MSYS|CYGWIN)/i.test(identity?.family || '')) return 'windows'
  return 'unknown'
}

// 将 Backend 监控错误键转换为当前语言的安全提示。
function monitorErrorText(value) {
  const messages = {
    'server.agentNotConfigured': 'servers.agentNotConfigured',
    'server.agentInvalidResponse': 'servers.agentInvalidResponse',
    'server.monitorFailed': 'servers.monitorFailed',
    'server.testFailed': 'servers.testFailed',
    'server.agentUnavailable': 'servers.agentUnavailable',
    'server.agentUnauthorized': 'servers.agentUnauthorized',
    SSH_LOCAL_USER_MISSING: 'servers.sshLocalUserMissing',
    SSH_AUTHENTICATION_FAILED: 'servers.sshAuthenticationFailed',
    SSH_CONNECTION_TIMEOUT: 'servers.sshConnectionTimeout',
    SSH_CONNECTION_REFUSED: 'servers.sshConnectionRefused',
    SSH_HOST_UNRESOLVED: 'servers.sshHostUnresolved',
    SSH_NETWORK_UNREACHABLE: 'servers.sshNetworkUnreachable',
    SSH_PRIVATE_KEY_INVALID: 'servers.sshPrivateKeyInvalid',
    SSH_COMMAND_FAILED: 'servers.sshCommandFailed',
    MONITOR_OS_UNSUPPORTED: 'servers.monitorOsUnsupported',
    MONITOR_OUTPUT_INVALID: 'servers.monitorOutputInvalid',
  }
  return messages[value] ? t(messages[value]) : String(value || t('servers.monitorFailed'))
}

onMounted(load)
</script>

<style scoped>
.servers-table { margin-top: 20px; }
.server-system { display: flex; align-items: flex-start; gap: 10px; padding: 6px 0; }
.system-icon { display: inline-flex; align-items: center; justify-content: center; flex: 0 0 30px; height: 30px; font-size: 26px; }
.system-icon svg { width: 30px; height: 30px; }
.system-details { min-width: 0; overflow-wrap: anywhere; }
.system-details summary { cursor: pointer; color: var(--el-text-color-primary); }
.system-details small { display: block; }
.system-details div, .system-details small, .system-unknown { color: var(--el-text-color-secondary); font-size: 12px; }
.server-test-error { color: var(--el-color-danger); overflow-wrap: anywhere; }
.server-editor-dialog,
.server-monitor-dialog { max-height: 90vh; overflow: hidden; }
.server-editor-dialog :deep(.el-dialog__body),
.server-monitor-dialog :deep(.el-dialog__body) { max-height: calc(90vh - 132px); overflow: auto; background: #f7f9fc; }
.dialog-intro { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; padding: 16px 18px; border: 1px solid #dfe7f5; border-radius: 12px; background: linear-gradient(135deg, #fff, #f1f5ff); }
.dialog-intro > div,
.monitor-hero > div { flex: 1; min-width: 0; }
.dialog-intro strong { color: var(--el-text-color-primary); font-size: 16px; }
.dialog-intro p,
.local-mode-notice p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.55; }
.dialog-intro-icon,
.monitor-hero-icon { display: grid; flex: 0 0 auto; place-items: center; width: 42px; height: 42px; border-radius: 12px; color: #fff; background: linear-gradient(135deg, var(--app-primary), #6b7bf2); box-shadow: 0 8px 18px rgba(53, 106, 230, 0.2); font-size: 20px; }
.server-editor-form { display: grid; gap: 16px; }
.config-section { padding: 20px; border: 1px solid var(--app-border); border-radius: 12px; background: #fff; box-shadow: 0 4px 14px rgba(31, 53, 91, 0.035); }
.config-section--security { border-color: #dae4f7; }
.config-section-head { display: flex; align-items: flex-start; gap: 12px; margin-bottom: 18px; padding-bottom: 14px; border-bottom: 1px solid #edf1f6; }
.config-section-head > span { display: grid; flex: 0 0 auto; place-items: center; width: 34px; height: 34px; border-radius: 9px; color: var(--app-primary); background: #edf3ff; font-size: 17px; }
.config-section-head h3 { margin: 0; color: var(--el-text-color-primary); font-size: 16px; }
.config-section-head p { margin: 4px 0 0; color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.5; }
.server-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 16px; }
.server-grid--basic { grid-template-columns: minmax(0, 1fr) minmax(250px, 0.7fr); }
.server-grid--connection { grid-template-columns: minmax(0, 1.4fr) minmax(140px, 0.55fr) minmax(0, 1fr); }
.status-setting { display: flex; align-items: center; justify-content: space-between; gap: 16px; min-height: 64px; margin-bottom: 20px; padding: 0 16px; border: 1px solid var(--app-border); border-radius: 9px; background: #fafbfe; }
.status-setting div { display: flex; flex-direction: column; gap: 3px; }
.status-setting strong { color: var(--el-text-color-primary); font-size: 14px; }
.status-setting small,
.field-help { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.field-help { width: 100%; margin-top: 6px; }
.section-last-field { margin-bottom: 0; }
.selection-cards { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; width: 100%; }
.selection-cards--auth { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.selection-card { display: grid; grid-template-columns: 38px minmax(0, 1fr) 22px; align-items: center; gap: 12px; min-height: 78px; padding: 14px; border: 1px solid var(--app-border); border-radius: 10px; color: var(--el-text-color-primary); background: #fff; cursor: pointer; text-align: left; transition: border-color 0.2s ease, background-color 0.2s ease, box-shadow 0.2s ease, transform 0.2s ease; }
.selection-card:hover { border-color: #aac0ef; background: #fafcff; transform: translateY(-1px); }
.selection-card.is-active { border-color: var(--app-primary); background: #f4f7ff; box-shadow: 0 0 0 3px rgba(53, 106, 230, 0.08); }
.selection-card-icon { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 10px; color: #637188; background: #f0f3f8; font-size: 18px; }
.selection-card.is-active .selection-card-icon { color: var(--app-primary); background: #e4ecff; }
.selection-card > span:nth-child(2) { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.selection-card strong { font-family: inherit; font-size: 14px; }
.selection-card small { overflow-wrap: anywhere; color: var(--el-text-color-secondary); font-family: inherit; font-size: 12px; line-height: 1.4; }
.selection-card-check { display: grid; place-items: center; width: 20px; height: 20px; border: 1px solid #cbd4e2; border-radius: 50%; color: transparent; font-size: 12px; }
.selection-card.is-active .selection-card-check { border-color: var(--app-primary); color: #fff; background: var(--app-primary); }
.credential-panel { margin-top: 4px; padding: 16px; border: 1px solid #e3e9f3; border-radius: 10px; background: #fafbfe; }
.private-key-editor { width: 100%; overflow: hidden; border: 1px solid var(--app-border); border-radius: 10px; background: #fff; }
.private-key-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 12px; padding: 12px 14px; border-bottom: 1px solid var(--app-border); background: #f7f9fd; }
.private-key-toolbar > div:first-child { display: flex; align-items: center; gap: 10px; min-width: 0; }
.private-key-toolbar > div:first-child > span:last-child { display: flex; min-width: 0; flex-direction: column; gap: 2px; }
.private-key-toolbar strong { overflow: hidden; color: var(--el-text-color-primary); font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.private-key-toolbar small { color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.4; }
.private-key-toolbar-icon { display: grid; flex: 0 0 auto; place-items: center; width: 30px; height: 30px; border-radius: 8px; color: var(--app-primary); background: #e8efff; }
.private-key-editor :deep(.el-textarea__inner) { border: 0; border-radius: 0; box-shadow: none; background: #fff; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 12px; line-height: 1.55; }
.private-key-file-row { display: flex; flex: 0 0 auto; align-items: center; gap: 10px; }
.hidden-file-input { display: none; }
.local-mode-notice { display: flex; align-items: center; gap: 14px; padding: 20px; border: 1px solid #cfe8dc; border-radius: 12px; background: #f1fbf6; }
.local-mode-notice > span { display: grid; place-items: center; width: 38px; height: 38px; border-radius: 10px; color: #16875d; background: #dff4e9; font-size: 18px; }
.local-mode-notice p { color: #547267; }
.dialog-footer-content { display: flex; align-items: center; justify-content: space-between; gap: 18px; width: 100%; }
.dialog-footer-content > div { display: flex; gap: 10px; }
.secure-submit-hint { display: inline-flex; align-items: center; gap: 6px; color: var(--el-text-color-secondary); font-size: 12px; }
.full { width: 100%; }
.credential-entry { display: inline-flex; align-items: center; gap: 6px; font-weight: 600; }
.credential-entry .el-tag { margin-left: 2px; border: 0; transform: scale(.9); }
.monitor-hero { display: flex; align-items: center; gap: 14px; margin-bottom: 18px; padding: 18px; border: 1px solid #dce5f4; border-radius: 12px; background: linear-gradient(135deg, #fff, #f0f5ff); }
.monitor-identity { display: flex; align-items: center; gap: 10px; }
.monitor-identity strong { color: var(--el-text-color-primary); font-size: 17px; }
.monitor-hero p { margin: 4px 0; color: #40516d; font-family: ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace; font-size: 13px; }
.monitor-hero small { color: var(--el-text-color-secondary); font-size: 12px; }
.monitor-content { min-height: 220px; }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.metric-card { border-color: var(--app-border); border-radius: 11px; }
.metric-card :deep(.el-card__body) { display: flex; min-height: 132px; flex-direction: column; gap: 10px; padding: 17px; }
.metric-card-head { display: flex; align-items: center; gap: 8px; color: var(--el-text-color-secondary); font-size: 12px; font-weight: 600; }
.metric-card-head span { display: grid; place-items: center; width: 28px; height: 28px; border-radius: 8px; color: var(--app-primary); background: #edf3ff; font-size: 15px; }
.metric-card small { color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.5; }
.metric-card strong { margin-top: auto; color: var(--el-text-color-primary); font-size: 26px; font-variant-numeric: tabular-nums; }
@media (max-width: 900px) { .metric-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 760px) {
  .server-grid, .server-grid--basic, .server-grid--connection, .metric-grid, .selection-cards { grid-template-columns: 1fr; }
  .dialog-intro, .monitor-hero { align-items: flex-start; }
  .monitor-hero { flex-wrap: wrap; }
  .monitor-hero > .el-button { width: 100%; }
  .private-key-toolbar { align-items: flex-start; flex-direction: column; }
  .private-key-file-row, .private-key-file-row .el-button { width: 100%; }
  .dialog-footer-content { align-items: stretch; flex-direction: column; }
  .dialog-footer-content > div { justify-content: flex-end; }
}
@media (max-width: 520px) {
  .config-section { padding: 16px; }
  .dialog-intro > .el-tag { display: none; }
  .dialog-footer-content > div { display: grid; grid-template-columns: repeat(2, 1fr); }
}
</style>
