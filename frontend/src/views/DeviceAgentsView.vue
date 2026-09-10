<template>
  <div class="panel device-agents">
    <div class="section-head">
      <div><h2>{{ t('deviceAgents.title') }}</h2><p>{{ t('deviceAgents.description') }}</p></div>
      <div class="table-actions"><el-button @click="load">{{ t('common.refresh') }}</el-button><el-button v-if="can('create')" type="primary" @click="openPairing">{{ t('deviceAgents.add') }}</el-button></div>
    </div>
    <el-alert :title="t('deviceAgents.automationNotice')" type="warning" show-icon :closable="false" />
    <el-table :data="agents" v-loading="loading" class="agent-table" @row-click="selectAgent">
      <el-table-column prop="deviceName" :label="t('deviceAgents.deviceName')" min-width="170" />
      <el-table-column prop="agentId" label="Agent ID" min-width="190" />
      <el-table-column :label="t('common.status')" width="120"><template #default="scope"><el-tag :type="statusType(scope.row)">{{ onlineStatus(scope.row) }}</el-tag></template></el-table-column>
      <el-table-column :label="t('deviceAgents.features')" min-width="210"><template #default="scope"><el-tag v-if="scope.row.featureAutomation === 'ENABLED'" type="success" size="small">WDA</el-tag><el-tag v-if="scope.row.featureDiagnostics === 'ENABLED'" class="feature-tag" size="small">{{ t('deviceAgents.diagnostics') }}</el-tag><el-tag v-if="scope.row.featureAutostart === 'ENABLED'" class="feature-tag" size="small">{{ t('deviceAgents.autostart') }}</el-tag></template></el-table-column>
      <el-table-column :label="t('deviceAgents.version')" min-width="150"><template #default="scope"><div>{{ scope.row.lastAgentVersion || '-' }}</div><small>XCUITest {{ scope.row.lastXcuitestDriverVersion || '-' }}</small></template></el-table-column>
      <el-table-column prop="lastOnlineAt" :label="t('deviceAgents.lastOnline')" min-width="175" />
      <el-table-column :label="t('common.operation')" width="365" fixed="right"><template #default="scope"><div class="table-actions" @click.stop><el-button link type="primary" @click="selectAgent(scope.row)">{{ t('common.detail') }}</el-button><el-button v-if="can('execute')" link @click="runHostCommand(scope.row, 'DIAGNOSTICS')">{{ t('deviceAgents.diagnostics') }}</el-button><el-button v-if="can('execute')" link @click="runHostCommand(scope.row, 'DETECT_DEVICE')">{{ t('deviceAgents.detect') }}</el-button><el-dropdown trigger="click"><el-button link>{{ t('deviceAgents.more') }}</el-button><template #dropdown><el-dropdown-menu><el-dropdown-item v-if="can('update')" @click="openFeatures(scope.row)">{{ t('deviceAgents.featureConfig') }}</el-dropdown-item><el-dropdown-item v-if="can('update')" @click="setDefault(scope.row)">{{ t('deviceAgents.setDefault') }}</el-dropdown-item><el-dropdown-item v-if="can('execute')" @click="runHostCommand(scope.row, 'UPGRADE')">{{ t('deviceAgents.upgrade') }}</el-dropdown-item><el-dropdown-item v-if="can('delete')" divided @click="revoke(scope.row)">{{ t('deviceAgents.revoke') }}</el-dropdown-item><el-dropdown-item v-if="can('delete')" @click="remove(scope.row)">{{ t('common.delete') }}</el-dropdown-item></el-dropdown-menu></template></el-dropdown></div></template></el-table-column>
    </el-table>

    <section v-if="selected" class="detail-card">
      <div class="section-head"><div><h3>{{ selected.deviceName || selected.agentId }}</h3><p>{{ t('deviceAgents.detailDescription') }}</p></div><div class="table-actions"><el-button v-if="can('update')" @click="openWdaConfig">{{ t('deviceAgents.wdaConfig') }}</el-button><el-button @click="loadDetail">{{ t('common.refresh') }}</el-button></div></div>
      <el-descriptions :column="3" border>
        <el-descriptions-item label="Agent ID">{{ selected.agentId }}</el-descriptions-item>
        <el-descriptions-item :label="t('deviceAgents.readiness')">{{ readiness.status || 'UNKNOWN' }}</el-descriptions-item>
        <el-descriptions-item :label="t('deviceAgents.backendUrl')">{{ selected.backendUrl || locationOrigin }}</el-descriptions-item>
        <el-descriptions-item :label="t('deviceAgents.launchMode')">{{ wdaConfig.launchMode || 'XCODEBUILD' }}</el-descriptions-item>
        <el-descriptions-item label="Appium">{{ wdaConfig.appiumServerUrl || 'http://127.0.0.1:4723' }}</el-descriptions-item>
        <el-descriptions-item :label="t('deviceAgents.basePort')">{{ wdaConfig.baseWdaLocalPort || 8100 }}</el-descriptions-item>
      </el-descriptions>

      <div class="subsection-head"><h4>{{ t('deviceAgents.registry') }}</h4><div v-if="can('execute')" class="table-actions"><el-button size="small" type="success" @click="registryAction('ONLINE')">{{ t('deviceAgents.online') }}</el-button><el-button size="small" @click="registryAction('OFFLINE')">{{ t('deviceAgents.offline') }}</el-button><el-button size="small" type="warning" :disabled="registry.desiredState !== 'ONLINE'" @click="registryAction('RECREATE')">{{ t('deviceAgents.recreate') }}</el-button></div></div>
      <el-descriptions :column="4" border size="small">
        <el-descriptions-item :label="t('deviceAgents.desiredState')">{{ registry.desiredState || 'OFFLINE' }}</el-descriptions-item>
        <el-descriptions-item :label="t('deviceAgents.observedState')"><el-tag :type="registry.observedState === 'ONLINE' ? 'success' : registry.observedState === 'ERROR' ? 'danger' : 'info'">{{ registry.observedState || 'NOT_INSTALLED' }}</el-tag></el-descriptions-item>
        <el-descriptions-item :label="t('deviceAgents.registryPort')"><el-button v-if="can('update')" link type="primary" @click="editRegistryPort">{{ registry.effectivePort || 42314 }}</el-button><span v-else>{{ registry.effectivePort || 42314 }}</span></el-descriptions-item>
        <el-descriptions-item :label="t('deviceAgents.tunnels')">{{ registry.tunnelCount || 0 }}</el-descriptions-item>
      </el-descriptions>
      <el-alert v-if="registry.lastErrorCode" class="inline-alert" :title="registry.lastErrorCode" type="error" show-icon :closable="false" />

      <div class="subsection-head"><h4>{{ t('deviceAgents.devices') }}</h4><span>{{ t('deviceAgents.deviceCount', { count: devices.length }) }}</span></div>
      <el-table :data="devices" size="small" v-loading="detailLoading">
        <el-table-column prop="deviceName" :label="t('deviceAgents.deviceName')" min-width="150" />
        <el-table-column prop="model" :label="t('deviceAgents.model')" min-width="140" />
        <el-table-column prop="osVersion" label="iOS" width="90" />
        <el-table-column prop="connectionType" :label="t('deviceAgents.connection')" width="105" />
        <el-table-column :label="t('deviceAgents.wdaState')" width="130"><template #default="scope"><el-tag :type="scope.row.wdaStatus === 'READY' ? 'success' : scope.row.wdaStatus === 'ERROR' ? 'danger' : 'info'">{{ scope.row.wdaRunning ? 'RUNNING' : scope.row.wdaStatus }}</el-tag></template></el-table-column>
        <el-table-column :label="t('deviceAgents.wdaPort')" width="100"><template #default="scope"><el-button v-if="can('update')" link type="primary" @click="editDevicePort(scope.row)">{{ scope.row.wdaLocalPort || '-' }}</el-button><span v-else>{{ scope.row.wdaLocalPort || '-' }}</span></template></el-table-column>
        <el-table-column :label="t('common.operation')" width="190" fixed="right"><template #default="scope"><div v-if="can('execute')" class="table-actions"><el-button link type="primary" :disabled="!scope.row.connected" @click="runDeviceCommand(scope.row, 'SETUP_WDA')">{{ t('deviceAgents.setupWda') }}</el-button><el-button link type="success" :disabled="!scope.row.connected || scope.row.wdaStatus !== 'READY'" @click="runDeviceCommand(scope.row, 'START_WDA')">{{ t('deviceAgents.startWda') }}</el-button></div></template></el-table-column>
      </el-table>

      <h4>{{ t('deviceAgents.commands') }}</h4>
      <el-table :data="commands" size="small"><el-table-column prop="commandType" :label="t('deviceAgents.command')" min-width="150"/><el-table-column :label="t('deviceAgents.target')" min-width="135"><template #default="scope">{{ shortId(scope.row.targetDeviceId) }}</template></el-table-column><el-table-column prop="status" :label="t('common.status')" width="110"/><el-table-column prop="resultSummary" :label="t('common.result')" min-width="210"/><el-table-column prop="errorCode" :label="t('common.error')" min-width="145"/><el-table-column :label="t('common.operation')" width="90"><template #default="scope"><el-button v-if="can('execute') && ['PENDING', 'LEASED'].includes(scope.row.status)" link type="danger" @click="cancelCommand(scope.row)">{{ t('common.cancel') }}</el-button></template></el-table-column></el-table>
    </section>

    <el-dialog v-model="pairingVisible" :title="t('deviceAgents.add')" width="min(650px, 94vw)">
      <el-steps :active="0" finish-status="success" simple><el-step :title="t('deviceAgents.pairStep')"/><el-step :title="t('deviceAgents.installStep')"/><el-step :title="t('deviceAgents.verifyStep')"/></el-steps>
      <el-form label-position="top" class="dialog-form"><el-form-item label="Agent ID"><el-input v-model="pairing.agentId" maxlength="64"/></el-form-item><el-form-item :label="t('deviceAgents.deviceName')"><el-input v-model="pairing.deviceName" maxlength="128"/></el-form-item><el-form-item :label="t('deviceAgents.backendUrl')"><el-input v-model="pairing.backendUrl"/></el-form-item><el-form-item :label="t('deviceAgents.features')"><el-checkbox v-model="pairing.diagnostics">{{ t('deviceAgents.diagnostics') }}</el-checkbox><el-checkbox v-model="pairing.automation">{{ t('deviceAgents.automation') }}</el-checkbox><el-checkbox v-model="pairing.autostart">{{ t('deviceAgents.autostart') }}</el-checkbox></el-form-item></el-form>
      <template #footer><el-button @click="pairingVisible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="createPairing">{{ t('deviceAgents.createPairing') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="installVisible" :title="t('deviceAgents.installTitle')" width="min(800px, 94vw)">
      <el-steps :active="1" finish-status="success" simple><el-step :title="t('deviceAgents.pairStep')"/><el-step :title="t('deviceAgents.installStep')"/><el-step :title="t('deviceAgents.verifyStep')"/></el-steps>
      <el-alert class="inline-alert" :title="t('deviceAgents.installNotice')" type="info" show-icon :closable="false" />
      <ul class="prerequisites"><li>{{ t('deviceAgents.requirementMac') }}</li><li>{{ t('deviceAgents.requirementXcode') }}</li><li>{{ t('deviceAgents.requirementDevice') }}</li><li>{{ t('deviceAgents.requirementSudo') }}</li></ul>
      <p class="pairing-code">{{ pairingResult.pairingCode }}</p>
      <el-input :model-value="installCommand" type="textarea" :rows="5" readonly />
      <template #footer><el-button @click="copyInstall">{{ t('deviceAgents.copyCommand') }}</el-button><el-button type="primary" @click="installVisible=false">{{ t('common.close') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="wdaVisible" :title="t('deviceAgents.wdaConfig')" width="min(680px, 94vw)">
      <el-form label-position="top" class="config-grid"><el-form-item label="Xcode Team ID"><el-input v-model="wdaForm.xcodeOrgId" maxlength="10"/></el-form-item><el-form-item label="Signing Identity"><el-input v-model="wdaForm.xcodeSigningId" maxlength="128"/></el-form-item><el-form-item label="WDA Bundle ID"><el-input v-model="wdaForm.updatedWdaBundleId" maxlength="255"/></el-form-item><el-form-item :label="t('deviceAgents.launchMode')"><el-select v-model="wdaForm.launchMode"><el-option label="Xcode Build" value="XCODEBUILD"/><el-option label="Preinstalled" value="PREINSTALLED"/><el-option label="WDA URL" value="URL"/></el-select></el-form-item><el-form-item v-if="wdaForm.launchMode === 'URL'" label="WDA URL"><el-input v-model="wdaForm.wdaUrl"/></el-form-item><el-form-item label="Appium URL"><el-input v-model="wdaForm.appiumServerUrl"/></el-form-item><el-form-item :label="t('deviceAgents.basePort')"><el-input-number v-model="wdaForm.baseWdaLocalPort" :min="1024" :max="65535"/></el-form-item><el-form-item><el-checkbox v-model="wdaForm.allowProvisioningDeviceRegistration">{{ t('deviceAgents.allowRegistration') }}</el-checkbox></el-form-item></el-form>
      <template #footer><el-button @click="wdaVisible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="saveWdaConfig">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="featuresVisible" :title="t('deviceAgents.featureConfig')" width="min(480px, 94vw)">
      <el-form label-position="top"><el-form-item :label="t('deviceAgents.features')"><el-checkbox v-model="featureForm.diagnostics">{{ t('deviceAgents.diagnostics') }}</el-checkbox><el-checkbox v-model="featureForm.automation">{{ t('deviceAgents.automation') }}</el-checkbox><el-checkbox v-model="featureForm.autostart">{{ t('deviceAgents.autostart') }}</el-checkbox></el-form-item></el-form>
      <template #footer><el-button @click="featuresVisible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="saveFeatures">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const locationOrigin = window.location.origin
const agents = ref([]), loading = ref(false), selected = ref(null), devices = ref([]), commands = ref([])
const detailLoading = ref(false), readiness = ref({}), wdaConfig = ref({}), registry = ref({})
const pairingVisible = ref(false), installVisible = ref(false), wdaVisible = ref(false), featuresVisible = ref(false)
const pairing = reactive({ agentId: '', deviceName: '', backendUrl: locationOrigin, diagnostics: true, automation: true, autostart: true })
const pairingResult = reactive({ pairingCode: '', agentId: '', backendUrl: '' })
const wdaForm = reactive({ xcodeOrgId: '', xcodeSigningId: 'Apple Development', updatedWdaBundleId: '', allowProvisioningDeviceRegistration: false, launchMode: 'XCODEBUILD', wdaUrl: '', appiumServerUrl: 'http://127.0.0.1:4723', baseWdaLocalPort: 8100 })
const featureForm = reactive({ diagnostics: true, automation: true, autostart: true })
const installCommand = computed(() => `curl -fsSL ${shellQuote(`${pairingResult.backendUrl}/agent-dist/bootstrap.sh`)} -o /tmp/base-ai-device-agent.sh\nsh /tmp/base-ai-device-agent.sh --backend-url ${shellQuote(pairingResult.backendUrl)} --pairing-code ${shellQuote(pairingResult.pairingCode)}\nrm -f /tmp/base-ai-device-agent.sh`)

// 判断当前用户是否拥有设备 Agent 的细粒度操作权限。
function can(action) { return auth.hasPermission(`operations:device-agent:${action}`) }
// 加载全部设备 Agent，并保持当前详情选择。
async function load() { loading.value = true; try { const { data } = await http.get('/automation/device-agents', { params: { page: 1, size: 100 } }); agents.value = data?.items || []; if (selected.value) selected.value = agents.value.find(item => item.agentId === selected.value.agentId) || null } catch (error) { showHttpError(error, 'deviceAgents.loadFailed') } finally { loading.value = false } }
// 取得身份建议后打开包含自动化能力的配对表单。
async function openPairing() { try { const { data } = await http.get('/automation/device-agents/pairing/identity-suggestion'); Object.assign(pairing, { agentId: data.agentId, deviceName: data.deviceName, backendUrl: locationOrigin, diagnostics: true, automation: true, autostart: true }); pairingVisible.value = true } catch (error) { showHttpError(error, 'deviceAgents.loadFailed') } }
// 创建一次性配对码并生成不含 Secret 的安装命令。
async function createPairing() { try { const features = []; if (pairing.diagnostics) features.push('READ_ONLY_DIAGNOSTICS'); if (pairing.automation) features.push('APPIUM_WDA_AUTOMATION'); if (pairing.autostart) features.push('AUTOSTART'); const { data } = await http.post('/automation/device-agents/pairing', { agentId: pairing.agentId, deviceName: pairing.deviceName, backendUrl: pairing.backendUrl, requestedFeatures: features }); Object.assign(pairingResult, data, { backendUrl: pairing.backendUrl.replace(/\/+$/, '') }); pairingVisible.value = false; installVisible.value = true; await load() } catch (error) { showHttpError(error, 'deviceAgents.pairingFailed') } }
// 复制安装命令，配对 Secret 由 Agent 领取后写入 Keychain。
async function copyInstall() { try { await navigator.clipboard.writeText(installCommand.value); ElMessage.success(t('deviceAgents.copied')) } catch { ElMessage.warning(t('deviceAgents.copyFailed')) } }
// 选择 Agent 并加载其设备、命令、诊断、WDA 和 Registry 详情。
async function selectAgent(row) { selected.value = row; await loadDetail() }
// 并行刷新选中 Agent 的完整自动化详情。
async function loadDetail() { if (!selected.value) return; detailLoading.value = true; try { const base = `/automation/device-agents/${encodeURIComponent(selected.value.agentId)}`; const responses = await Promise.all([http.get(`${base}/devices`), http.get(`${base}/commands`), http.get(`${base}/readiness`), http.get(`${base}/wda-config`), http.get(`${base}/registry`)]); devices.value = responses[0].data || []; commands.value = responses[1].data || []; readiness.value = responses[2].data || {}; wdaConfig.value = responses[3].data || {}; registry.value = responses[4].data || {} } catch (error) { showHttpError(error, 'deviceAgents.loadFailed') } finally { detailLoading.value = false } }
// 下发主机级诊断、发现或升级命令。
async function runHostCommand(row, commandType) { await dispatchCommand({ agentId: row.agentId, commandType, commandParams: {} }) }
// 向选中的匿名设备下发 WDA 安装或启动命令。
async function runDeviceCommand(device, commandType) { await dispatchCommand({ agentId: selected.value.agentId, targetDeviceId: device.deviceId, commandType, commandParams: {} }) }
// 统一投递命令并刷新命令列表。
async function dispatchCommand(payload) { try { await http.post('/automation/device-agents/commands', payload); ElMessage.success(t('deviceAgents.commandAccepted')); if (selected.value?.agentId === payload.agentId) await loadDetail() } catch (error) { showHttpError(error, 'deviceAgents.commandFailed') } }
// 取消仍在等待或租赁中的命令。
async function cancelCommand(command) { try { await http.post(`/automation/device-agents/commands/${command.id}/cancel`); await loadDetail() } catch (error) { showHttpError(error, 'deviceAgents.commandFailed') } }
// 将已配对实例设置为唯一默认 Agent。
async function setDefault(row) { try { await http.post(`/automation/device-agents/${encodeURIComponent(row.agentId)}/default`); await load() } catch (error) { showHttpError(error) } }
// 打开并填充功能开关对话框。
function openFeatures(row) { selected.value = row; Object.assign(featureForm, { diagnostics: row.featureDiagnostics === 'ENABLED', automation: row.featureAutomation === 'ENABLED', autostart: row.featureAutostart === 'ENABLED' }); featuresVisible.value = true }
// 保存诊断、自动化和自启动功能状态。
async function saveFeatures() { try { const state = value => value ? 'ENABLED' : 'DISABLED'; await http.put(`/automation/device-agents/${encodeURIComponent(selected.value.agentId)}/features`, { featureDiagnostics: state(featureForm.diagnostics), featureAutomation: state(featureForm.automation), featureAutostart: state(featureForm.autostart) }); featuresVisible.value = false; await load() } catch (error) { showHttpError(error) } }
// 打开 WDA 签名和本地服务配置对话框。
function openWdaConfig() { const signing = wdaConfig.value.signingConfig || {}; Object.assign(wdaForm, { xcodeOrgId: signing.xcodeOrgId || '', xcodeSigningId: signing.xcodeSigningId || 'Apple Development', updatedWdaBundleId: signing.updatedWdaBundleId || '', allowProvisioningDeviceRegistration: Boolean(signing.allowProvisioningDeviceRegistration), launchMode: wdaConfig.value.launchMode || 'XCODEBUILD', wdaUrl: wdaConfig.value.wdaUrl || '', appiumServerUrl: wdaConfig.value.appiumServerUrl || 'http://127.0.0.1:4723', baseWdaLocalPort: wdaConfig.value.baseWdaLocalPort || 8100 }); wdaVisible.value = true }
// 加密保存 WDA 配置并触发 Agent 热加载。
async function saveWdaConfig() { try { const signingConfig = wdaForm.xcodeOrgId || wdaForm.xcodeSigningId || wdaForm.updatedWdaBundleId ? { xcodeOrgId: wdaForm.xcodeOrgId || null, xcodeSigningId: wdaForm.xcodeSigningId || null, updatedWdaBundleId: wdaForm.updatedWdaBundleId || null, allowProvisioningDeviceRegistration: wdaForm.allowProvisioningDeviceRegistration } : null; await http.put(`/automation/device-agents/${encodeURIComponent(selected.value.agentId)}/wda-config`, { signingConfig, launchMode: wdaForm.launchMode, wdaUrl: wdaForm.launchMode === 'URL' ? wdaForm.wdaUrl : null, appiumServerUrl: wdaForm.appiumServerUrl, baseWdaLocalPort: wdaForm.baseWdaLocalPort }); wdaVisible.value = false; await loadDetail() } catch (error) { showHttpError(error) } }
// 修改单设备 WDA 端口，留空可恢复自动分配。
async function editDevicePort(device) { try { const { value } = await ElMessageBox.prompt(t('deviceAgents.portPrompt'), t('deviceAgents.wdaPort'), { inputValue: String(device.wdaLocalPort || ''), inputValidator: validOptionalPort, inputErrorMessage: t('deviceAgents.portInvalid') }); await http.put(`/automation/device-agents/${encodeURIComponent(selected.value.agentId)}/devices/${device.deviceId}/wda-port`, { wdaLocalPort: value === '' ? null : Number(value) }); await loadDetail() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
// 修改 Registry 端口覆盖值，留空恢复平台默认端口。
async function editRegistryPort() { try { const { value } = await ElMessageBox.prompt(t('deviceAgents.registryPortPrompt'), t('deviceAgents.registryPort'), { inputValue: registry.value.portOverride == null ? '' : String(registry.value.portOverride), inputValidator: validOptionalPort, inputErrorMessage: t('deviceAgents.portInvalid') }); await http.put(`/automation/device-agents/${encodeURIComponent(selected.value.agentId)}/registry`, { portOverride: value === '' ? null : Number(value) }); await loadDetail() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
// 下发 Registry 固定生命周期动作。
async function registryAction(action) { try { await http.post(`/automation/device-agents/${encodeURIComponent(selected.value.agentId)}/registry/actions`, { action }); ElMessage.success(t('deviceAgents.commandAccepted')); await loadDetail() } catch (error) { showHttpError(error, 'deviceAgents.commandFailed') } }
// 二次确认后撤销 Agent 凭据和未完成命令。
async function revoke(row) { try { await ElMessageBox.confirm(t('deviceAgents.revokeConfirm', { name: row.deviceName || row.agentId }), t('deviceAgents.revoke')); await http.post(`/automation/device-agents/${encodeURIComponent(row.agentId)}/revoke`, { reason: 'ADMIN_REVOKED' }); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
// 二次确认后删除 Agent 配置，审计记录仍由后端保留。
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.deviceName || row.agentId }), t('common.deleteConfirm')); await http.delete(`/automation/device-agents/${encodeURIComponent(row.agentId)}`); selected.value = null; await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
// 根据最近心跳和撤销状态渲染状态标签。
function onlineStatus(row) { if (row.pairingStatus === 'REVOKED') return t('deviceAgents.revoked'); return row.lastHeartbeatStatus || row.pairingStatus }
// 根据 Agent 状态选择 Element Plus 标签颜色。
function statusType(row) { return row.pairingStatus === 'REVOKED' ? 'danger' : row.lastHeartbeatStatus === 'ONLINE' ? 'success' : 'warning' }
// 缩短匿名设备摘要以便命令列表显示。
function shortId(value) { return value ? `${value.slice(0, 8)}…` : t('deviceAgents.hostTarget') }
// 校验可留空的非特权 TCP 端口完整边界。
function validOptionalPort(value) { return value === '' || /^\d+$/.test(value) && Number(value) >= 1024 && Number(value) <= 65535 }
// 对安装命令参数进行 POSIX Shell 单引号转义。
function shellQuote(value) { return `'${String(value || '').replaceAll("'", `'"'"'`)}'` }
onMounted(load)
</script>

<style scoped>
.agent-table { margin-top: 20px; }
.feature-tag { margin-left: 6px; }
.detail-card { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--el-border-color-light); }
.detail-card h4 { margin: 22px 0 10px; }
.subsection-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 20px; }
.subsection-head h4 { margin: 0 0 10px; }
.inline-alert { margin: 16px 0; }
.pairing-code { text-align: center; font: 700 24px/1.5 ui-monospace, monospace; letter-spacing: 2px; }
.dialog-form { margin-top: 20px; }
.config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 18px; }
.prerequisites { line-height: 1.9; color: var(--el-text-color-regular); }
@media (max-width: 720px) { .config-grid { grid-template-columns: 1fr; } }
</style>
