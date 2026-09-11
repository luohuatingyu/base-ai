<template>
  <div class="device-agent-onboarding-view">
    <el-card>
      <template #header>
        <div class="card-header">
          <span>{{ t('deviceAgentOnboarding.title') }}</span>
          <el-button link type="primary" @click="$router.push('/automation/device-agents')">
            {{ t('deviceAgentOnboarding.backToAgents') }}
          </el-button>
        </div>
      </template>

      <el-steps :active="step" align-center finish-status="success" class="onboarding-steps">
        <el-step v-for="key in STEP_KEYS" :key="key" :title="t(`deviceAgentOnboarding.steps.${key}`)" />
      </el-steps>

      <!-- 第 4 步起每个操作都要 agentId：为空时统一说明，配合按钮禁用堵掉打向 /device-agents// 的静默失败 -->
      <el-alert v-if="step >= 3 && !agentReady" type="warning" :closable="false" show-icon>
        <template #title>{{ t('deviceAgentOnboarding.existing.agentMissing') }}</template>
      </el-alert>

      <!-- 第 1 步：前置准备清单（无法自动化的物理操作，逐项确认） -->
      <section v-if="step === 0" class="step-pane">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>{{ t('deviceAgentOnboarding.prepare.hint') }}</template>
        </el-alert>
        <el-checkbox-group v-model="prepareChecked" class="prepare-list">
          <el-checkbox v-for="item in PREPARE_ITEMS" :key="item" :label="item">
            <strong>{{ t(`deviceAgentOnboarding.prepare.items.${item}`) }}</strong>
            <div class="item-hint">{{ t(`deviceAgentOnboarding.prepare.hints.${item}`) }}</div>
          </el-checkbox>
        </el-checkbox-group>
        <div class="pane-actions">
          <el-button type="primary" :disabled="prepareChecked.length < PREPARE_ITEMS.length" @click="step = 1">
            {{ t('common.next') }}
          </el-button>
        </div>
      </section>

      <!-- 第 2 步：复用已配对实例，或生成配对码与一键安装命令 -->
      <section v-if="step === 1" class="step-pane">
        <!-- 已配对实例复用分支：装过 Agent 的 Mac 不必为了走完向导再重跑一遍安装命令 -->
        <template v-if="pairedAgents.length">
          <el-alert type="info" :closable="false" show-icon>
            <template #title>{{ t('deviceAgentOnboarding.existing.hint') }}</template>
          </el-alert>
          <el-form label-width="160px">
            <el-form-item :label="t('deviceAgentOnboarding.existing.label')">
              <el-select v-model="selectedExistingAgentId" style="width: 360px" clearable
                         :placeholder="t('deviceAgentOnboarding.existing.placeholder')">
                <el-option v-for="item in pairedAgents" :key="item.agentId"
                           :label="existingAgentLabel(item)" :value="item.agentId" />
              </el-select>
              <el-button type="primary" :disabled="!selectedExistingAgentId" @click="useExistingAgent">
                {{ t('deviceAgentOnboarding.existing.use') }}
              </el-button>
            </el-form-item>
          </el-form>
          <el-divider>{{ t('deviceAgentOnboarding.existing.orNew') }}</el-divider>
        </template>

        <el-form :model="pairingForm" label-width="160px">
          <el-form-item :label="t('deviceAgents.agentId')" required>
            <el-input v-model="pairingForm.agentId" maxlength="64"
                      :placeholder="t('deviceAgents.agentIdPlaceholder')">
              <template #append>
                <el-button :loading="identitySuggestionLoading" @click="generatePairingIdentity">
                  {{ t('deviceAgents.generateAgentIdentity') }}
                </el-button>
              </template>
            </el-input>
            <div class="item-hint">{{ t('deviceAgents.agentIdHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('deviceAgents.agentDeviceName')">
            <el-input v-model="pairingForm.deviceName" maxlength="128"
                      :placeholder="t('deviceAgents.agentDeviceNamePlaceholder')" />
            <div class="item-hint">{{ t('deviceAgents.deviceMetadataOptionalHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('deviceAgents.backendUrl')">
            <el-input v-model="pairingForm.backendUrl" :placeholder="t('deviceAgents.backendUrlPlaceholder')" />
            <div class="item-hint">{{ t('deviceAgents.backendUrlHint') }}</div>
          </el-form-item>
          <el-form-item :label="t('deviceAgents.insecureEnv')">
            <el-switch v-model="pairingForm.selfSigned" @change="selfSignedTouched = true" />
            <div class="item-hint">{{ t(insecureHintKey) }}</div>
          </el-form-item>
          <el-form-item :label="t('deviceAgents.npmRegistryOption')">
            <el-input v-model="pairingForm.npmRegistry" :placeholder="t('deviceAgents.npmRegistryOptionPlaceholder')" />
            <div class="item-hint">{{ t('deviceAgents.npmRegistryOptionHint') }}</div>
          </el-form-item>
        </el-form>

        <template v-if="installCommand">
          <el-divider />
          <div class="code-label">{{ t('deviceAgents.installCommand') }}</div>
          <div class="install-command">
            <code>{{ installCommand }}</code>
            <el-button link type="primary" @click="copyText(installCommand)">
              <el-icon><DocumentCopy /></el-icon>
            </el-button>
          </div>
          <div class="item-hint">
            {{ t('deviceAgentOnboarding.pairing.commandHint', { seconds: pairingExpiresIn }) }}
          </div>
        </template>

        <div class="pane-actions">
          <el-button @click="step = 0">{{ t('common.previous') }}</el-button>
          <el-button type="primary" :loading="pairingLoading" :disabled="pairingCode !== null" @click="createPairing">
            {{ t('deviceAgentOnboarding.pairing.generate') }}
          </el-button>
          <el-button type="primary" :disabled="!installCommand" @click="enterWaitStep">
            {{ t('deviceAgentOnboarding.pairing.copied') }}
          </el-button>
        </div>
      </section>

      <!-- 第 3 步：等待 Agent 上线（自动轮询） -->
      <section v-if="step === 2" class="step-pane step-pane--center">
        <el-result v-if="!agentOnline" icon="info" :title="t('deviceAgentOnboarding.waiting.title')">
          <template #sub-title>
            <p>{{ t('deviceAgentOnboarding.waiting.hint', { agentId }) }}</p>
          </template>
        </el-result>
        <el-result v-else icon="success" :title="t('deviceAgentOnboarding.waiting.online', { agentId })" />
        <div class="pane-actions">
          <el-button @click="backToPairing">{{ t('common.previous') }}</el-button>
          <el-button type="primary" :disabled="!agentOnline" @click="enterConfigStep">{{ t('common.next') }}</el-button>
        </div>
      </section>

      <!-- 第 4 步：自动探测并保存 IDA 配置 -->
      <section v-if="step === 3" class="step-pane" v-loading="configRunning">
        <el-alert v-if="configError" type="error" :closable="false" show-icon>
          <template #title>{{ configError }}</template>
        </el-alert>

        <el-descriptions :column="1" border>
          <el-descriptions-item :label="t('deviceAgentOnboarding.config.device')">
            <div v-if="devicePoolDevices.length" class="device-list">
              <el-tag v-for="item in devicePoolDevices" :key="item.deviceId"
                      :type="item.connected ? 'success' : 'info'">
                {{ deviceLabel(item) }}
              </el-tag>
            </div>
            <template v-else>{{ t('deviceAgentOnboarding.config.pending') }}</template>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deviceAgentOnboarding.config.signing')">
            <template v-if="signingCandidates.length > 1">
              <el-select v-model="selectedSigningTeamId" style="width: 360px">
                <el-option v-for="item in signingCandidates" :key="item.teamId"
                           :label="item.label || `${item.signingIdentity} (${item.teamId})`" :value="item.teamId" />
              </el-select>
            </template>
            <template v-else>{{ resolvedSigningLabel || t('deviceAgentOnboarding.config.pending') }}</template>
          </el-descriptions-item>
          <el-descriptions-item :label="t('deviceAgentOnboarding.config.saved')">
            <el-tag :type="configSaved ? 'success' : 'info'">
              {{ configSaved ? t('deviceAgentOnboarding.config.savedYes') : t('deviceAgentOnboarding.config.savedNo') }}
            </el-tag>
          </el-descriptions-item>
        </el-descriptions>

        <div class="pane-actions">
          <el-button @click="step = 2">{{ t('common.previous') }}</el-button>
          <el-button :loading="configRunning" :disabled="!agentReady" @click="runAutoConfig">{{ t('deviceAgentOnboarding.config.retry') }}</el-button>
          <el-button type="primary" :disabled="!configSaved" @click="step = 4">{{ t('common.next') }}</el-button>
        </div>
      </section>

      <!-- 第 5 步：一键构建 IDA 到 iOS 设备 -->
      <section v-if="step === 4" class="step-pane step-pane--center">
        <el-result v-if="setupState === 'idle'" icon="info" :title="t('deviceAgentOnboarding.setup.title')">
          <template #sub-title><p>{{ t('deviceAgentOnboarding.setup.hint') }}</p></template>
        </el-result>
        <el-result v-else-if="setupState === 'running'" icon="info" :title="t('deviceAgentOnboarding.setup.running')">
          <template #sub-title><p>{{ t('deviceAgentOnboarding.setup.runningHint') }}</p></template>
        </el-result>
        <el-result v-else-if="setupState === 'success'" icon="success" :title="t('deviceAgentOnboarding.setup.success')" />
        <el-result v-else icon="error" :title="t('deviceAgentOnboarding.setup.failed')">
          <template #sub-title>
            <p class="setup-error">{{ setupError }}</p>
            <!-- 多身份阻断：原地选择签名身份，选定后自动落库并重试构建 -->
            <div v-if="setupSigningCandidates.length" class="setup-signing">
              <el-select v-model="setupSigningTeamId" style="width: 360px"
                         :placeholder="t('deviceAgentOnboarding.setup.selectSigning')">
                <el-option v-for="item in setupSigningCandidates" :key="item.teamId"
                           :label="item.label || `${item.signingIdentity} (${item.teamId})`" :value="item.teamId" />
              </el-select>
              <el-button type="primary" :disabled="!setupSigningTeamId" @click="applySetupSigning">
                {{ t('deviceAgentOnboarding.setup.applySigning') }}
              </el-button>
            </div>
            <!-- 零身份与探测失败无法在控制台自愈，给出 Mac 侧补救命令 -->
            <p v-else-if="setupBlockCode" class="setup-hint">{{ t('deviceAgentOnboarding.setup.signingRemedy') }}</p>
          </template>
        </el-result>
        <div class="pane-actions">
          <el-button :disabled="setupState === 'running'" @click="step = 3">{{ t('common.previous') }}</el-button>
          <el-button type="primary" :loading="setupState === 'running'" :disabled="!agentReady"
                     v-if="setupState !== 'success'" @click="runSetupIda">
            {{ t('deviceAgentOnboarding.setup.run') }}
          </el-button>
          <el-button type="primary" :disabled="setupState !== 'success'" @click="enterVerifyStep">
            {{ t('common.next') }}
          </el-button>
        </div>
      </section>

      <!-- 第 6 步：iOS 设备侧人工步骤 + 十项诊断自动复验 -->
      <section v-if="step === 5" class="step-pane">
        <el-alert type="info" :closable="false" show-icon>
          <template #title>{{ t('deviceAgentOnboarding.verify.hint') }}</template>
        </el-alert>
        <ol class="ios-device-steps">
          <li v-for="item in IOS_DEVICE_ITEMS" :key="item">
            <strong>{{ t(`deviceAgentOnboarding.verify.items.${item}`) }}</strong>
            <div class="item-hint">{{ t(`deviceAgentOnboarding.verify.hints.${item}`) }}</div>
          </li>
        </ol>
        <div class="pane-actions">
          <el-button type="primary" :loading="verifyRunning" :disabled="!agentReady" @click="runVerify">
            {{ t('deviceAgentOnboarding.verify.run') }}
          </el-button>
        </div>
        <div v-if="readinessChecks.length" class="readiness-grid">
          <div v-for="item in readinessChecks" :key="item.code" class="readiness-item">
            <el-tag :type="checkTagType(item.status)" size="small">{{ item.status }}</el-tag>
            <span>{{ t(`deviceAgentGuide.readiness.checks.${item.code}`) }}</span>
            <code v-if="item.version">{{ item.version }}</code>
          </div>
        </div>
        <div class="pane-actions">
          <el-button @click="step = 4">{{ t('common.previous') }}</el-button>
          <el-button type="primary" :disabled="!verifyPassed" @click="step = 6">{{ t('common.next') }}</el-button>
          <el-button link @click="step = 6">{{ t('deviceAgentOnboarding.verify.skip') }}</el-button>
        </div>
      </section>

      <!-- 第 7 步：启用功能并完成 -->
      <section v-if="step === 6" class="step-pane">
        <el-form label-width="200px">
          <el-form-item :label="t('deviceAgents.automation')">
            <el-switch v-model="finishForm.automation" />
          </el-form-item>
          <el-form-item :label="t('deviceAgents.autostart')">
            <el-switch v-model="finishForm.autostart" />
          </el-form-item>
        </el-form>
        <el-result v-if="finished" icon="success" :title="t('deviceAgentOnboarding.finish.done')">
          <template #sub-title><p>{{ t('deviceAgentOnboarding.finish.doneHint') }}</p></template>
        </el-result>
        <div class="pane-actions">
          <el-button @click="step = 5">{{ t('common.previous') }}</el-button>
          <el-button type="primary" :loading="finishSaving" :disabled="!agentReady" @click="saveFeaturesAndFinish">
            {{ t('deviceAgentOnboarding.finish.save') }}
          </el-button>
          <el-button v-if="finished" type="success" @click="$router.push('/automation/device-agents')">
            {{ t('deviceAgentOnboarding.backToAgents') }}
          </el-button>
        </div>
      </section>
    </el-card>
  </div>
</template>

<script setup>
import { ref, reactive, computed, watch, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage } from 'element-plus'
import { DocumentCopy } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'
import { appConfig, resolvePlatformBaseUrl } from '../config'
// 必须使用统一 http 客户端：自动附加 X-CSRF-Token 并解包统一响应信封
import http, { showHttpError } from '../api/http'
import { buildInstallCommand, detectSelfSignedDeployment, isPrivateOrigin } from '../utils/deviceAgentInstallCommand'
import { buildDetectedIdaConfigPayload, loadExistingIdaConfig } from '../utils/deviceAgentIdaConfig'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
// 向导内的命令下发与配置保存都需要操作权限，只读账号应看到明确提示而不是后端 403
const can = (action) => auth.hasPermission(`automation:device-agent:${action}`)
// Agent 默认地址来自平台公开配置；缺省时仍保留当前前端的子路径挂载前缀。
const platformBaseUrl = resolvePlatformBaseUrl(window.location.origin, appConfig)

// 向导步骤定义：物理准备 → 命令 → 上线 → 配置 → 构建 → 复验 → 完成
const STEP_KEYS = ['prepare', 'command', 'online', 'config', 'setup', 'verify', 'finish']
// 前置清单与 iOS 设备人工步骤：Apple 平台限制无法自动化，只能指引 + 自动复验
const PREPARE_ITEMS = ['xcode', 'usb', 'trust']
const IOS_DEVICE_ITEMS = ['developer', 'trustCert']

const route = useRoute()
// 支持 ?step=setup 直达指定步骤：环境检测页的失败项要能把用户直接送到「构建 IDA」，
// 否则引导只能落成一句"请去向导里找那一步"，用户仍要自己数到第五步
const step = ref(Math.max(0, STEP_KEYS.indexOf(String(route.query.step || ''))))
const prepareChecked = ref([])

// —— 配对与安装命令 ——
// backendUrl 为该 Agent 回连后端的公网地址，留空沿用平台全局 base-url
const pairingForm = reactive({
  agentId: '', deviceName: '', backendUrl: '', selfSigned: false, npmRegistry: ''
})
const pairingLoading = ref(false)
const identitySuggestionLoading = ref(false)
const pairingCode = ref(null)
const pairingExpiresIn = ref(600)
const agentId = ref('')
let pairingTimer = null

// 第 4 步起的每个请求都要拼进 agentId：为空时会打到 /device-agents//... 这类路径上，
// 后端匹配不到路由，页面只表现为点了没反应。统一用这个开关禁用下发入口
const agentReady = computed(() => Boolean(agentId.value))

// —— 已配对实例复用 ——
// 向导原本只服务全新配对：agentId 仅在 createPairing 成功后才有值，已经装好并配对过的 Mac
// 只能重新生成配对码、在 Mac 上重跑一遍安装命令才能走完向导；从环境检测页深链直达构建步骤时
// agentId 更是空的。这里补一条复用分支，让已配对实例可以直接接着往下走
const pairedAgents = ref([])
const selectedExistingAgentId = ref('')

/** 拉取可复用的实例：只认已配对且未吊销的，吊销实例的签名凭据已失效，下发命令必然被拒。
 *
 * 默认实例排在最前，多台 Mac 时避免选错；列表接口失败不阻断向导，退化成只能新建配对。
 */
const loadPairedAgents = async () => {
  try {
    const response = await http.get('/automation/device-agents',
      { params: { page: 1, size: 100 }, silentError: true })
    pairedAgents.value = (response.data?.items || [])
      .filter(item => item.pairingStatus === 'PAIRED' && !item.revokedAt)
      .sort((a, b) => Number(Boolean(b.isDefault)) - Number(Boolean(a.isDefault)))
  } catch {
    pairedAgents.value = []
  }
}

/** 下拉展示文案：优先给出人工设备名称，唯一Agent ID始终保留用于识别和排障。 */
const existingAgentLabel = (item) => {
  const identity = item.deviceName ? `${item.deviceName} · ${item.agentId}` : item.agentId
  return item.isDefault ? `${identity}（${t('deviceAgents.defaultAgent')}）` : identity
}

/** 复用已配对实例：不生成配对码，直接进入等待上线，由心跳轮询确认这台 Mac 当前可用。 */
const useExistingAgent = () => {
  if (!selectedExistingAgentId.value) return
  agentId.value = selectedExistingAgentId.value
  // 复用实例没有新配对码，残留的倒计时与安装命令必须清掉，否则会显示一条过期命令误导用户
  if (pairingTimer) clearInterval(pairingTimer)
  pairingCode.value = null
  enterWaitStep()
}

/** 解析深链指定的实例：显式 agentId 优先，其次默认实例。
 *
 * 环境检测页的失败项会带 ?step=setup 直达构建步骤，而那一步起的每个请求都要 agentId。
 * 解析不到实例时必须退回第 1 步并说明，否则用户会停在一个点什么都没反应的页面上。
 */
const resolveDeepLinkAgent = () => {
  if (step.value === 0) return
  const wanted = String(route.query.agentId || '')
  const matched = pairedAgents.value.find(item => item.agentId === wanted)
    || pairedAgents.value.find(item => item.isDefault)
  if (!matched) {
    step.value = 0
    ElMessage.warning(t('deviceAgentOnboarding.existing.deepLinkUnavailable'))
    return
  }
  agentId.value = matched.agentId
  selectedExistingAgentId.value = matched.agentId
}

// 自签名探测结果：平台确实暴露内部根证书时才自动开启开关，避免生成指向 404 的 --ca-file
const selfSignedDetected = ref(false)
// 用户手动拨动过开关后不再被探测结果覆盖
const selfSignedTouched = ref(false)

/**
 * 判断本次接入是否走了独立于控制台 origin 的回连地址。
 *
 * 走独立地址时控制台探不到目标地址的证书情况（跨域探测必然失败），
 * 自动开启只会生成指向 404 的 --ca-file，一律交回人工判断。
 */
const usesCustomServer = computed(() => {
  const bound = pairingForm.backendUrl.trim().replace(/\/+$/, '')
  return Boolean(bound) && bound !== platformBaseUrl
})

// 开关提示四态：绑定了独立地址时说明探测不适用；已自动开启说明来源；
// 私网但未探测到根证书时提示手动开启；其余保持通用说明
const insecureHintKey = computed(() => {
  if (usesCustomServer.value) return 'deviceAgents.insecureEnvCustomServer'
  if (selfSignedDetected.value) return 'deviceAgents.insecureEnvAutoDetected'
  if (isPrivateOrigin(platformBaseUrl)) return 'deviceAgents.insecureEnvSuggest'
  return 'deviceAgents.insecureEnvHint'
})

const installCommand = computed(() => buildInstallCommand({
  origin: platformBaseUrl,
  backendUrl: pairingForm.backendUrl,
  pairingCode: pairingCode.value,
  selfSigned: pairingForm.selfSigned,
  npmRegistry: pairingForm.npmRegistry
}))

/** 请求服务端生成同源的 Agent ID 与短码名称，生成后仍可按现场命名规则编辑。 */
const generatePairingIdentity = async () => {
  identitySuggestionLoading.value = true
  try {
    const response = await http.get('/automation/device-agents/pairing/identity-suggestion')
    pairingForm.agentId = response.data.agentId
    pairingForm.deviceName = response.data.deviceName
    ElMessage.success(t('deviceAgents.agentIdentityGenerated'))
  } catch (error) {
    showHttpError(error, 'deviceAgents.agentIdentityGenerateError')
  } finally {
    identitySuggestionLoading.value = false
  }
}

/** 创建前在页面侧拦截空身份；后端仍会重复校验，防止直接调用绕过。 */
const createPairing = async () => {
  if (!pairingForm.agentId.trim()) {
    ElMessage.warning(t('deviceAgents.agentIdRequired'))
    return
  }
  pairingLoading.value = true
  try {
    // 向导默认只申请诊断 + IDA 两项基础功能，任务执行在最后一步显式开启
    const response = await http.post('/automation/device-agents/pairing', {
      agentId: pairingForm.agentId.trim(),
      requestedFeatures: ['READ_ONLY_DIAGNOSTICS', 'APPIUM_IDA_AUTOMATION'],
      backendUrl: pairingForm.backendUrl.trim(),
      deviceName: pairingForm.deviceName.trim()
    })
    pairingCode.value = response.data.pairingCode
    agentId.value = response.data.agentId
    pairingExpiresIn.value = response.data.ttlSeconds || 600
    if (pairingTimer) clearInterval(pairingTimer)
    // 与配对码同步倒计时，到期后清空命令要求重新生成
    pairingTimer = setInterval(() => {
      pairingExpiresIn.value -= 1
      if (pairingExpiresIn.value <= 0) {
        clearInterval(pairingTimer)
        pairingCode.value = null
      }
    }, 1000)
  } catch (error) {
    showHttpError(error, 'deviceAgentOnboarding.pairing.error')
  } finally {
    pairingLoading.value = false
  }
}

const copyText = (value) => {
  navigator.clipboard.writeText(value)
  ElMessage.success(t('common.copied'))
}

// —— 上线轮询 ——
// 与 Agent 心跳节奏一致：60 秒心跳 × 3 次未上报视为离线
const ONLINE_THRESHOLD_MS = 180000
const ONLINE_POLL_INTERVAL_MS = 5000
const agentOnline = ref(false)
let onlineTimer = null
// 组件卸载后所有异步轮询必须立即失效，避免离开页面仍在请求
let alive = true

const pollOnline = async () => {
  try {
    const response = await http.get(`/automation/device-agents/${agentId.value}`, { silentError: true })
    const lastOnlineAt = response.data?.lastOnlineAt
    agentOnline.value = Boolean(lastOnlineAt) && Date.now() - new Date(lastOnlineAt).getTime() < ONLINE_THRESHOLD_MS
  } catch {
    agentOnline.value = false
  }
}

const enterWaitStep = () => {
  step.value = 2
  agentOnline.value = false
  pollOnline()
  if (onlineTimer) clearInterval(onlineTimer)
  onlineTimer = setInterval(pollOnline, ONLINE_POLL_INTERVAL_MS)
}

const backToPairing = () => {
  if (onlineTimer) clearInterval(onlineTimer)
  step.value = 1
}

// —— 命令下发与轮询（探测 2 秒/60 秒；SETUP_IDA 构建 5 秒/20 分钟） ——
const DETECT_POLL_INTERVAL_MS = 2000
const DETECT_POLL_TIMEOUT_MS = 60000
const SETUP_POLL_INTERVAL_MS = 5000
const SETUP_POLL_TIMEOUT_MS = 1200000

const sleep = (ms) => new Promise(resolve => setTimeout(resolve, ms))

// 下发命令并轮询到终态；返回 {status, resultSummary}，超时抛错
const dispatchCommand = async (commandType, intervalMs, timeoutMs, targetDeviceId = '') => {
  // 所有命令下发的唯一入口，在这里兜住 agentId 为空：否则后端收到的是一条没有归属实例的命令
  if (!agentReady.value) throw new Error(t('deviceAgentOnboarding.existing.agentMissing'))
  const created = await http.post('/automation/device-agents/commands', {
    agentId: agentId.value, commandType,
    ...(targetDeviceId ? { targetDeviceId } : {})
  })
  const commandId = created.data.id
  const deadline = Date.now() + timeoutMs
  while (alive && Date.now() < deadline) {
    await sleep(intervalMs)
    const command = (await http.get(`/automation/device-agents/commands/${commandId}`)).data
    if (command.status === 'COMPLETED' || command.status === 'FAILED'
      || command.status === 'EXPIRED' || command.status === 'CANCELLED') {
      return command
    }
  }
  throw new Error(t('deviceAgentOnboarding.commandTimeout'))
}

// —— 自动配置 IDA ——
const configRunning = ref(false)
const configError = ref('')
const configSaved = ref(false)
const devicePoolDevices = ref([])
const signingCandidates = ref([])
const selectedSigningTeamId = ref('')

const resolvedSigning = computed(() => {
  if (signingCandidates.value.length === 1) return signingCandidates.value[0]
  return signingCandidates.value.find(item => item.teamId === selectedSigningTeamId.value) || null
})
const resolvedSigningLabel = computed(() => resolvedSigning.value
  ? (resolvedSigning.value.label || `${resolvedSigning.value.signingIdentity} (${resolvedSigning.value.teamId})`) : '')

/** 展示 Agent 自动发现的设备，不把原始 UDID带到控制台。 */
const deviceLabel = (device) => {
  const name = device.deviceName || device.model || t('deviceAgents.devicePool.unnamed')
  const system = [device.platform, device.osVersion].filter(Boolean).join(' ')
  return `${name}${system ? ` · ${system}` : ''}`
}

const parseSummary = (summary) => {
  try { return JSON.parse(summary || '{}') } catch { return {} }
}

// 自动链路：读取 Agent 已同步的设备池 → 探测签名 → 唯一身份自动保存。
const runAutoConfig = async () => {
  configRunning.value = true
  configError.value = ''
  try {
    if (!can('execute')) throw new Error(t('deviceAgentOnboarding.noPermission'))
    const deviceResponse = await http.get(`/automation/device-agents/${agentId.value}/devices`)
    devicePoolDevices.value = deviceResponse.data || []
    if (!devicePoolDevices.value.some(item => item.connected)) {
      throw new Error(t('deviceAgentOnboarding.config.noDevice'))
    }

    const signingResult = await dispatchCommand('DETECT_SIGNING', DETECT_POLL_INTERVAL_MS, DETECT_POLL_TIMEOUT_MS)
    if (signingResult.status !== 'COMPLETED') throw new Error(signingResult.resultSummary || signingResult.status)
    signingCandidates.value = parseSummary(signingResult.resultSummary).identities || []
    if (!signingCandidates.value.length) throw new Error(t('deviceAgentOnboarding.config.noSigning'))

    await saveConfigIfResolved()
  } catch (error) {
    configError.value = error?.message || String(error)
  } finally {
    configRunning.value = false
  }
}

// 只落库主机级签名；后端保存后会自动排队 UPDATE_CONFIG 通知 Agent 生效。
// 自动配置与构建预检选择两个入口共用同一份请求体，避免字段漏填导致下发不一致。
// PUT 是整体替换，必须先读旧值保留用户手工设置的设备注册开关。
const persistIdaConfig = async (signing) => {
  const existingConfig = await loadExistingIdaConfig(http, agentId.value)
  const payload = buildDetectedIdaConfigPayload(signing, existingConfig)
  await http.put(`/automation/device-agents/${agentId.value}/ida-config`, payload)
  configSaved.value = true
  ElMessage.success(t('deviceAgents.configSaved'))
}

// 已发现至少一台设备且签名已确定时落库并下发 UPDATE_CONFIG。
const saveConfigIfResolved = async () => {
  if (!devicePoolDevices.value.some(item => item.connected) || !resolvedSigning.value) return
  await persistIdaConfig(resolvedSigning.value)
}

const enterConfigStep = () => {
  if (onlineTimer) clearInterval(onlineTimer)
  step.value = 3
  configSaved.value = false
  runAutoConfig()
}

// 多签名身份场景：用户在下拉里选定后自动落库，无需额外按钮。
watch(selectedSigningTeamId, async () => {
  if (configSaved.value || !resolvedSigning.value) return
  try {
    await saveConfigIfResolved()
  } catch (error) {
    showHttpError(error, 'deviceAgents.configSaveError')
  }
})

// —— 一键构建 IDA ——
const setupState = ref('idle')
const setupError = ref('')
// 构建预检阻断状态：Agent 在发起会话前判定签名未就绪时回写稳定码与候选身份
const setupBlockCode = ref('')
const setupSigningCandidates = ref([])
const setupSigningTeamId = ref('')

// 预检阻断码到补救指引文案的映射；未收录的码按原始摘要透传
const SETUP_BLOCK_MESSAGES = {
  SIGNING_IDENTITY_MISSING: 'deviceAgentOnboarding.setup.signingMissing',
  SIGNING_IDENTITY_AMBIGUOUS: 'deviceAgentOnboarding.setup.signingAmbiguous',
  SIGNING_DETECT_FAILED: 'deviceAgentOnboarding.setup.signingDetectFailed'
}

const runSetupIda = async () => {
  setupState.value = 'running'
  setupError.value = ''
  setupBlockCode.value = ''
  setupSigningCandidates.value = []
  setupSigningTeamId.value = ''
  try {
    if (!can('execute')) throw new Error(t('deviceAgentOnboarding.noPermission'))
    const connectedDevices = devicePoolDevices.value.filter(item => item.connected)
    if (!connectedDevices.length) throw new Error(t('deviceAgentOnboarding.config.noDevice'))
    for (const device of connectedDevices) {
      const result = await dispatchCommand(
        'SETUP_IDA', SETUP_POLL_INTERVAL_MS, SETUP_POLL_TIMEOUT_MS, device.deviceId)
      if (result.status === 'COMPLETED') continue
      // 旧版 Agent 不认识 SETUP_IDA：稳定标记提示重装而不是透传原始文案
      if (/UNSUPPORTED_COMMAND/.test(result.errorCode || '')) throw new Error(t('deviceAgentOnboarding.setup.unsupported'))
      // 失败原因优先取稳定 errorCode：Agent 的 resultSummary 统一是通用中文摘要，
      // 按 code 才能给出补救指引；多身份阻断场景复用探测步骤的候选列表
      const blockKey = SETUP_BLOCK_MESSAGES[result.errorCode]
      if (blockKey) {
        setupBlockCode.value = result.errorCode
        setupSigningCandidates.value = result.errorCode === 'SIGNING_IDENTITY_AMBIGUOUS'
          ? (signingCandidates.value || []) : []
        throw new Error(t(blockKey))
      }
      throw new Error(result.resultSummary || result.errorCode || result.status)
    }
    setupState.value = 'success'
  } catch (error) {
    setupState.value = 'failed'
    setupError.value = error?.message || String(error)
  }
}

// 构建步骤内选定签名身份：先落库并等 UPDATE_CONFIG 排队，再自动重试构建。
// 后端按 created_at 顺序下发命令，UPDATE_CONFIG 先于重试的 SETUP_IDA 生效
const applySetupSigning = async () => {
  const candidate = setupSigningCandidates.value.find(item => item.teamId === setupSigningTeamId.value)
  if (!candidate) return
  try {
    await persistIdaConfig(candidate)
  } catch (error) {
    showHttpError(error, 'deviceAgents.configSaveError')
    return
  }
  await runSetupIda()
}

// —— iOS 设备复验（诊断 + readiness） ——
const verifyRunning = ref(false)
const readinessChecks = ref([])
// 环境诊断和全部在线设备的 IDA 状态通过后才允许完成接入。
const verifyPassed = computed(() => {
  const connectedDevices = devicePoolDevices.value.filter(item => item.connected)
  return readinessChecks.value.length > 0
    && readinessChecks.value.every(item => item.status !== 'FAIL')
    && connectedDevices.length > 0
    && connectedDevices.every(item => item.idaStatus === 'READY')
})

const enterVerifyStep = () => {
  step.value = 5
  readinessChecks.value = []
}

const runVerify = async () => {
  verifyRunning.value = true
  try {
    if (!can('execute')) throw new Error(t('deviceAgentOnboarding.noPermission'))
    const result = await dispatchCommand('DIAGNOSTICS', DETECT_POLL_INTERVAL_MS, DETECT_POLL_TIMEOUT_MS)
    if (result.status !== 'COMPLETED') throw new Error(result.resultSummary || result.status)
    // 必须按本次配对的实例查询，避免误用另一台默认 Agent 的历史诊断。
    const readiness = (await http.get(`/automation/device-agents/${agentId.value}/readiness`)).data
    readinessChecks.value = readiness.checks || []
    const devices = await http.get(`/automation/device-agents/${agentId.value}/devices`)
    devicePoolDevices.value = devices.data || []
  } catch (error) {
    ElMessage.error(error?.message || String(error))
  } finally {
    verifyRunning.value = false
  }
}

const checkTagType = (status) => {
  if (status === 'PASS') return 'success'
  if (status === 'FAIL') return 'danger'
  if (status === 'WARN') return 'warning'
  return 'info'
}

// —— 功能开关与完成 ——
const finishForm = reactive({ automation: true, autostart: true })
const finishSaving = ref(false)
const finished = ref(false)

const saveFeaturesAndFinish = async () => {
  if (!agentReady.value) {
    ElMessage.warning(t('deviceAgentOnboarding.existing.agentMissing'))
    return
  }
  finishSaving.value = true
  try {
    await http.put(`/automation/device-agents/${agentId.value}/features`, {
      featureDiagnostics: 'ENABLED',
      featureAutomation: finishForm.automation ? 'ENABLED' : 'DISABLED',
      featureAutostart: finishForm.autostart ? 'ENABLED' : 'DISABLED'
    })
    finished.value = true
    ElMessage.success(t('deviceAgents.featuresUpdated'))
  } catch (error) {
    if (error === 'cancel') return
    showHttpError(error, 'deviceAgents.featuresUpdateError')
  } finally {
    finishSaving.value = false
  }
}

onMounted(async () => {
  // 先备好可复用实例，深链才有解析依据；列表失败时退化成只能新建配对，不阻断向导
  await loadPairedAgents()
  if (!alive) return
  resolveDeepLinkAgent()
  // 进入向导即探测平台证书类型：确认自签名后自动开启开关，用户手动拨动过则保留其选择
  const detected = await detectSelfSignedDeployment(platformBaseUrl).catch(() => false)
  if (!alive || !detected) return
  selfSignedDetected.value = true
  // 绑定了独立回连地址时探测结论不适用于目标地址，不代拨开关
  if (!selfSignedTouched.value && !usesCustomServer.value) pairingForm.selfSigned = true
})

onBeforeUnmount(() => {
  alive = false
  if (pairingTimer) clearInterval(pairingTimer)
  if (onlineTimer) clearInterval(onlineTimer)
})
</script>

<style scoped>
.card-header {
  display: flex;
  justify-content: space-between;
  align-items: center;
}
.onboarding-steps {
  margin: 8px 0 24px;
}
.step-pane {
  max-width: 860px;
  margin: 0 auto;
  display: flex;
  flex-direction: column;
  gap: 16px;
}
.step-pane--center {
  align-items: center;
}
.prepare-list {
  display: flex;
  flex-direction: column;
  gap: 12px;
}
.prepare-list :deep(.el-checkbox) {
  align-items: flex-start;
  white-space: normal;
  height: auto;
}
.item-hint {
  font-size: 12px;
  color: var(--el-text-color-secondary);
  line-height: 1.5;
}
.device-list {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
}
.pane-actions {
  display: flex;
  gap: 8px;
  justify-content: center;
  margin-top: 8px;
}
.code-label {
  font-weight: 600;
}
.install-command {
  display: flex;
  align-items: flex-start;
  gap: 8px;
  background: var(--el-fill-color-light);
  border-radius: 6px;
  padding: 10px 12px;
}
.install-command code {
  word-break: break-all;
  font-size: 12px;
  line-height: 1.6;
}
.ios-device-steps {
  margin: 0;
  padding-left: 20px;
  display: flex;
  flex-direction: column;
  gap: 10px;
}
.readiness-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}
.readiness-item {
  display: flex;
  align-items: center;
  gap: 8px;
  padding: 6px 10px;
  border: 1px solid var(--el-border-color-lighter);
  border-radius: 6px;
  font-size: 13px;
}
.readiness-item code {
  margin-left: auto;
  font-size: 12px;
  color: var(--el-text-color-secondary);
}
.setup-error {
  word-break: break-all;
}

/* 预检阻断后的原地补救区：候选选择与 Mac 侧指引 */
.setup-signing {
  display: flex;
  gap: 12px;
  justify-content: center;
  margin-top: 12px;
}

.setup-hint {
  margin-top: 12px;
  color: var(--el-text-color-regular);
  text-align: left;
}
</style>
