<template>
  <div class="panel device-agents">
    <div class="section-head">
      <div><h2>{{ t('deviceAgents.title') }}</h2><p>{{ t('deviceAgents.description') }}</p></div>
      <div class="table-actions"><el-button @click="load">{{ t('common.refresh') }}</el-button><el-button type="primary" @click="openPairing">{{ t('deviceAgents.add') }}</el-button></div>
    </div>
    <el-alert :title="t('deviceAgents.readOnlyNotice')" type="success" show-icon :closable="false" />
    <el-table :data="agents" v-loading="loading" class="agent-table" @row-click="selectAgent">
      <el-table-column prop="deviceName" :label="t('deviceAgents.deviceName')" min-width="180" />
      <el-table-column prop="agentId" label="Agent ID" min-width="190" />
      <el-table-column :label="t('common.status')" width="130"><template #default="scope"><el-tag :type="statusType(scope.row)">{{ onlineStatus(scope.row) }}</el-tag></template></el-table-column>
      <el-table-column :label="t('deviceAgents.features')" min-width="170"><template #default="scope"><span>{{ scope.row.featureDiagnostics === 'ENABLED' ? t('deviceAgents.diagnostics') : '-' }}</span><el-tag v-if="scope.row.featureAutostart === 'ENABLED'" class="feature-tag" size="small">{{ t('deviceAgents.autostart') }}</el-tag></template></el-table-column>
      <el-table-column prop="lastAgentVersion" :label="t('deviceAgents.version')" width="120" />
      <el-table-column prop="lastOnlineAt" :label="t('deviceAgents.lastOnline')" min-width="175" />
      <el-table-column :label="t('common.operation')" width="340" fixed="right"><template #default="scope"><div class="table-actions" @click.stop><el-button link type="primary" @click="selectAgent(scope.row)">{{ t('common.detail') }}</el-button><el-button link @click="runCommand(scope.row, 'DIAGNOSTICS')">{{ t('deviceAgents.diagnostics') }}</el-button><el-button link @click="runCommand(scope.row, 'DETECT_DEVICE')">{{ t('deviceAgents.detect') }}</el-button><el-button link type="warning" @click="runCommand(scope.row, 'UPGRADE')">{{ t('deviceAgents.upgrade') }}</el-button><el-dropdown trigger="click"><el-button link>{{ t('deviceAgents.more') }}</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item @click="setDefault(scope.row)">{{ t('deviceAgents.setDefault') }}</el-dropdown-item><el-dropdown-item @click="revoke(scope.row)">{{ t('deviceAgents.revoke') }}</el-dropdown-item><el-dropdown-item divided @click="remove(scope.row)">{{ t('common.delete') }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></template></el-table-column>
    </el-table>

    <section v-if="selected" class="detail-card">
      <div class="section-head"><div><h3>{{ selected.deviceName || selected.agentId }}</h3><p>{{ t('deviceAgents.detailDescription') }}</p></div><el-button @click="loadDetail">{{ t('common.refresh') }}</el-button></div>
      <el-descriptions :column="3" border><el-descriptions-item label="Agent ID">{{ selected.agentId }}</el-descriptions-item><el-descriptions-item :label="t('deviceAgents.readiness')">{{ readiness.status || 'UNKNOWN' }}</el-descriptions-item><el-descriptions-item :label="t('deviceAgents.backendUrl')">{{ selected.backendUrl || locationOrigin }}</el-descriptions-item></el-descriptions>
      <h4>{{ t('deviceAgents.devices') }}</h4>
      <el-table :data="devices" size="small" v-loading="detailLoading"><el-table-column prop="deviceName" :label="t('deviceAgents.deviceName')" min-width="160"/><el-table-column prop="model" :label="t('deviceAgents.model')" min-width="150"/><el-table-column prop="osVersion" label="iOS" width="100"/><el-table-column prop="connectionType" :label="t('deviceAgents.connection')" width="120"/><el-table-column prop="status" :label="t('common.status')" width="130"/><el-table-column prop="lastSeenAt" :label="t('deviceAgents.lastSeen')" min-width="175"/></el-table>
      <h4>{{ t('deviceAgents.commands') }}</h4>
      <el-table :data="commands" size="small"><el-table-column prop="commandType" :label="t('deviceAgents.command')" min-width="160"/><el-table-column prop="status" :label="t('common.status')" width="120"/><el-table-column prop="resultSummary" :label="t('common.result')" min-width="220"/><el-table-column prop="errorCode" :label="t('common.error')" min-width="150"/><el-table-column prop="createdAt" :label="t('common.time')" min-width="175"/></el-table>
    </section>

    <el-dialog v-model="pairingVisible" :title="t('deviceAgents.add')" width="min(620px, 94vw)">
      <el-form label-position="top"><el-form-item label="Agent ID"><el-input v-model="pairing.agentId" maxlength="64"/></el-form-item><el-form-item :label="t('deviceAgents.deviceName')"><el-input v-model="pairing.deviceName" maxlength="128"/></el-form-item><el-form-item :label="t('deviceAgents.backendUrl')"><el-input v-model="pairing.backendUrl"/></el-form-item><el-form-item :label="t('deviceAgents.features')"><el-checkbox v-model="pairing.diagnostics">{{ t('deviceAgents.diagnostics') }}</el-checkbox><el-checkbox v-model="pairing.autostart">{{ t('deviceAgents.autostart') }}</el-checkbox></el-form-item></el-form>
      <template #footer><el-button @click="pairingVisible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="createPairing">{{ t('deviceAgents.createPairing') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="installVisible" :title="t('deviceAgents.installTitle')" width="min(760px, 94vw)">
      <el-alert :title="t('deviceAgents.installNotice')" type="info" show-icon :closable="false" />
      <p class="pairing-code">{{ pairingResult.pairingCode }}</p>
      <el-input :model-value="installCommand" type="textarea" :rows="5" readonly />
      <template #footer><el-button @click="copyInstall">{{ t('deviceAgents.copyCommand') }}</el-button><el-button type="primary" @click="installVisible=false">{{ t('common.close') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'

const { t } = useI18n()
const locationOrigin = window.location.origin
const agents = ref([]), loading = ref(false), selected = ref(null), devices = ref([]), commands = ref([])
const detailLoading = ref(false), readiness = ref({}), pairingVisible = ref(false), installVisible = ref(false)
const pairing = reactive({ agentId: '', deviceName: '', backendUrl: locationOrigin, diagnostics: true, autostart: true })
const pairingResult = reactive({ pairingCode: '', agentId: '', backendUrl: '' })
const installCommand = computed(() => `curl -fsSL ${pairingResult.backendUrl}/agent-dist/bootstrap.sh -o /tmp/base-ai-device-agent.sh\nsh /tmp/base-ai-device-agent.sh --backend-url '${pairingResult.backendUrl}' --pairing-code '${pairingResult.pairingCode}'\nrm -f /tmp/base-ai-device-agent.sh`)

// 加载全部设备 Agent，并保持当前详情选择。
async function load() { loading.value = true; try { const { data } = await http.get('/automation/device-agents', { params: { page: 1, size: 100 } }); agents.value = data?.items || []; if (selected.value) selected.value = agents.value.find(item => item.agentId === selected.value.agentId) || null } catch (error) { showHttpError(error, 'deviceAgents.loadFailed') } finally { loading.value = false } }
// 取得身份建议后打开配对表单。
async function openPairing() { try { const { data } = await http.get('/automation/device-agents/pairing/identity-suggestion'); Object.assign(pairing, { agentId: data.agentId, deviceName: data.deviceName, backendUrl: locationOrigin, diagnostics: true, autostart: true }); pairingVisible.value = true } catch (error) { showHttpError(error, 'deviceAgents.loadFailed') } }
// 创建一次性配对码并生成不含 Secret 的安装命令。
async function createPairing() { try { const features = []; if (pairing.diagnostics) features.push('READ_ONLY_DIAGNOSTICS'); if (pairing.autostart) features.push('AUTOSTART'); const { data } = await http.post('/automation/device-agents/pairing', { agentId: pairing.agentId, deviceName: pairing.deviceName, backendUrl: pairing.backendUrl, requestedFeatures: features }); Object.assign(pairingResult, data, { backendUrl: pairing.backendUrl.replace(/\/+$/, '') }); pairingVisible.value = false; installVisible.value = true; await load() } catch (error) { showHttpError(error, 'deviceAgents.pairingFailed') } }
// 复制安装命令，配对 Secret 由 Agent 领取后写入 Keychain。
async function copyInstall() { try { await navigator.clipboard.writeText(installCommand.value); ElMessage.success(t('deviceAgents.copied')) } catch { ElMessage.warning(t('deviceAgents.copyFailed')) } }
// 选择 Agent 并加载其设备、命令和诊断详情。
async function selectAgent(row) { selected.value = row; await loadDetail() }
// 并行刷新选中 Agent 的只读详情。
async function loadDetail() { if (!selected.value) return; detailLoading.value = true; try { const base = `/automation/device-agents/${encodeURIComponent(selected.value.agentId)}`; const [deviceResponse, commandResponse, readinessResponse] = await Promise.all([http.get(`${base}/devices`), http.get(`${base}/commands`), http.get(`${base}/readiness`)]); devices.value = deviceResponse.data || []; commands.value = commandResponse.data || []; readiness.value = readinessResponse.data || {} } catch (error) { showHttpError(error, 'deviceAgents.loadFailed') } finally { detailLoading.value = false } }
// 下发只读诊断、只读设备发现或 Agent 自升级命令。
async function runCommand(row, commandType) { try { await http.post('/automation/device-agents/commands', { agentId: row.agentId, commandType, commandParams: {} }); ElMessage.success(t('deviceAgents.commandAccepted')); if (selected.value?.agentId === row.agentId) await loadDetail() } catch (error) { showHttpError(error, 'deviceAgents.commandFailed') } }
// 将已配对实例设置为唯一默认 Agent。
async function setDefault(row) { try { await http.post(`/automation/device-agents/${encodeURIComponent(row.agentId)}/default`); await load() } catch (error) { showHttpError(error) } }
// 二次确认后撤销 Agent 凭据和未完成命令。
async function revoke(row) { try { await ElMessageBox.confirm(t('deviceAgents.revokeConfirm', { name: row.deviceName || row.agentId }), t('deviceAgents.revoke')); await http.post(`/automation/device-agents/${encodeURIComponent(row.agentId)}/revoke`, { reason: 'ADMIN_REVOKED' }); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
// 二次确认后删除 Agent 配置，审计记录仍由后端保留。
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.deviceName || row.agentId }), t('common.deleteConfirm')); await http.delete(`/automation/device-agents/${encodeURIComponent(row.agentId)}`); selected.value = null; await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
// 根据最近心跳和撤销状态渲染状态标签。
function onlineStatus(row) { if (row.pairingStatus === 'REVOKED') return t('deviceAgents.revoked'); return row.lastHeartbeatStatus || row.pairingStatus }
// 根据 Agent 状态选择 Element Plus 标签颜色。
function statusType(row) { return row.pairingStatus === 'REVOKED' ? 'danger' : row.lastHeartbeatStatus === 'ONLINE' ? 'success' : 'warning' }
onMounted(load)
</script>

<style scoped>
.agent-table { margin-top: 20px; }
.feature-tag { margin-left: 8px; }
.detail-card { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--el-border-color-light); }
.detail-card h4 { margin: 22px 0 10px; }
.pairing-code { text-align: center; font: 700 24px/1.5 ui-monospace, monospace; letter-spacing: 2px; }
</style>
