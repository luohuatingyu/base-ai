<template>
  <div class="panel agent-guide">
    <div class="guide-toolbar">
      <el-button :icon="ArrowLeft" @click="router.push('/automation/device-agents')">
        {{ t('deviceAgentGuide.backToAgents') }}
      </el-button>
      <span class="guide-scope"><el-icon><Lock /></el-icon>{{ t('deviceAgentGuide.readOnly') }}</span>
    </div>

    <section class="guide-hero">
      <div>
        <span class="guide-eyebrow">MAC AGENT SETUP</span>
        <h1>{{ t('deviceAgentGuide.title') }}</h1>
        <p>{{ t('deviceAgentGuide.description') }}</p>
        <div class="hero-actions">
          <el-button type="primary" @click="router.push('/automation/device-agents/onboarding')">
            {{ t('deviceAgentGuide.openOnboarding') }}
          </el-button>
          <el-button @click="router.push('/automation/device-agents')">
            {{ t('deviceAgentGuide.openAgents') }}
          </el-button>
        </div>
      </div>
      <div class="agent-figure" role="img" :aria-label="t('deviceAgentGuide.figureAria')">
        <span><el-icon><Monitor /></el-icon></span>
        <strong>Mac Agent</strong>
        <small>Python 3.12 · Xcode · Appium · XCUITest</small>
      </div>
    </section>

    <section class="setup-section">
      <div class="section-title">
        <span>01</span>
        <div><h2>{{ t('deviceAgentGuide.setup.title') }}</h2><p>{{ t('deviceAgentGuide.setup.description') }}</p></div>
      </div>
      <div class="setup-grid">
        <article>
          <b>1</b>
          <h3>{{ t('deviceAgentGuide.setup.pairTitle') }}</h3>
          <p>{{ t('deviceAgentGuide.setup.pairDescription') }}</p>
          <el-button link type="primary" @click="router.push('/automation/device-agents/onboarding')">
            {{ t('deviceAgentGuide.openOnboarding') }}
          </el-button>
        </article>
        <article>
          <b>2</b>
          <h3>{{ t('deviceAgentGuide.setup.installTitle') }}</h3>
          <p>{{ t('deviceAgentGuide.setup.installDescription') }}</p>
          <div class="requirement-list">
            <span>macOS</span><span>Python 3.12</span><span>Full Xcode</span><span>Appium + XCUITest</span>
          </div>
        </article>
        <article>
          <b>3</b>
          <h3>{{ t('deviceAgentGuide.setup.defaultTitle') }}</h3>
          <p>{{ t('deviceAgentGuide.setup.defaultDescription') }}</p>
          <code>{{ t('deviceAgentGuide.setup.defaultPath') }}</code>
        </article>
        <article>
          <b>4</b>
          <h3>{{ t('deviceAgentGuide.setup.checkTitle') }}</h3>
          <p>{{ t('deviceAgentGuide.setup.checkDescription') }}</p>
          <el-button link type="primary" :loading="triggeringDiagnostics" @click="triggerDiagnostics">
            {{ t('deviceAgentGuide.readiness.triggerDiagnostics') }}
          </el-button>
        </article>
      </div>
    </section>

    <section class="commands-section">
      <div class="section-title">
        <span>02</span>
        <div><h2>{{ t('deviceAgentGuide.dependencies.title') }}</h2><p>{{ t('deviceAgentGuide.dependencies.description') }}</p></div>
      </div>
      <div class="command-groups">
        <article>
          <h3>{{ t('deviceAgentGuide.dependencies.xcodeTitle') }}</h3>
          <p>{{ t('deviceAgentGuide.dependencies.xcodeDescription') }}</p>
          <div v-for="command in xcodeCommands" :key="command" class="command-row">
            <code>{{ command }}</code>
            <el-button text :icon="CopyDocument" @click="copyText(command)" />
          </div>
        </article>
        <article>
          <h3>{{ t('deviceAgentGuide.dependencies.appiumTitle') }}</h3>
          <p>{{ t('deviceAgentGuide.dependencies.appiumDescription') }}</p>
          <div v-for="command in installCommands" :key="command" class="command-row">
            <code>{{ command }}</code>
            <el-button text :icon="CopyDocument" @click="copyText(command)" />
          </div>
        </article>
      </div>
      <el-alert type="warning" :closable="false" show-icon :title="t('deviceAgentGuide.dependencies.fallbackTitle')">
        {{ t('deviceAgentGuide.dependencies.fallbackDescription') }}
      </el-alert>
    </section>

    <section v-loading="readinessLoading" class="readiness-panel">
      <div class="readiness-head">
        <div>
          <span class="readiness-kicker">{{ t('deviceAgentGuide.readiness.kicker') }}</span>
          <h2>{{ t('deviceAgentGuide.readiness.title') }}</h2>
          <p>{{ t('deviceAgentGuide.readiness.description') }}</p>
          <p class="readiness-source">{{ readinessSourceHint }}</p>
        </div>
        <div class="readiness-actions">
          <el-button type="primary" :icon="VideoPlay" :loading="triggeringDiagnostics"
                     :disabled="!defaultAgentId || !can('execute')" @click="triggerDiagnostics">
            {{ t('deviceAgentGuide.readiness.triggerDiagnostics') }}
          </el-button>
          <el-button :icon="Refresh" :loading="readinessLoading" @click="loadReadiness">
            {{ t('deviceAgentGuide.readiness.refresh') }}
          </el-button>
        </div>
      </div>
      <div class="readiness-summary" :class="`readiness-summary--${readiness.status.toLowerCase()}`">
        <span class="readiness-score">{{ passedChecks }}/{{ readinessChecks.length }}</span>
        <div>
          <strong>{{ t(`deviceAgentGuide.readiness.summary.${readiness.status}`) }}</strong>
          <small>{{ readiness.checkedAt ? `${t('deviceAgentGuide.readiness.lastChecked')} ${formatDate(readiness.checkedAt)}` : t('deviceAgentGuide.readiness.neverChecked') }}</small>
        </div>
        <el-tag :type="readinessType(readiness.status)" effect="dark">
          {{ t(`deviceAgentGuide.readiness.status.${readiness.status}`) }}
        </el-tag>
      </div>
      <el-alert v-if="readiness.stale" type="warning" :closable="false" show-icon>
        <template #title>{{ t('deviceAgentGuide.readiness.stale') }}</template>
        {{ t('deviceAgentGuide.readiness.staleHint') }}
      </el-alert>
      <div class="diagnostic-command">
        <div><strong>{{ t('deviceAgentGuide.readiness.runCommand') }}</strong><small>{{ t('deviceAgentGuide.readiness.runCommandHint') }}</small></div>
        <code>{{ diagnosticCommand }}</code>
        <el-button plain :icon="CopyDocument" @click="copyText(diagnosticCommand)">{{ t('deviceAgentGuide.copy') }}</el-button>
      </div>
      <div class="readiness-grid">
        <article v-for="item in readinessChecks" :key="item.code" :class="`readiness-check readiness-check--${item.status.toLowerCase()}`">
          <span class="check-state" aria-hidden="true"></span>
          <div>
            <strong>{{ t(`deviceAgentGuide.readiness.checks.${item.code}`) }}</strong>
            <small :class="{ 'check-reason': item.guidance.reason }">{{ item.guidance.reason || item.message || t(`deviceAgentGuide.readiness.hints.${item.code}`) }}</small>
            <p v-if="item.guidance.action" class="check-action">
              <span>{{ item.guidance.action }}</span>
              <el-button v-if="item.guidance.actionLabel" link type="primary" size="small" @click="runCheckAction(item.guidance.target)">
                {{ item.guidance.actionLabel }}
              </el-button>
            </p>
          </div>
          <div class="check-result">
            <el-tag :type="readinessType(item.status)" size="small">{{ t(`deviceAgentGuide.readiness.status.${item.status}`) }}</el-tag>
            <code v-if="item.version">{{ item.version }}</code>
          </div>
        </article>
      </div>
    </section>
  </div>
</template>

<script setup>
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { useRouter } from 'vue-router'
import { useI18n } from 'vue-i18n'
import { ElMessage } from 'element-plus'
import { ArrowLeft, CopyDocument, Lock, Monitor, Refresh, VideoPlay } from '@element-plus/icons-vue'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const router = useRouter()
const auth = useAuthStore()
// 诊断命令下发需要执行权限，只读账号只看最近一次上报结果
const can = (action) => auth.hasPermission(`operations:device-agent:${action}`)
const readinessLoading = ref(false)
const triggeringDiagnostics = ref(false)
const defaultAgentId = ref('')
const diagnosticNames = ['MACOS', 'PYTHON', 'XCODE', 'DEVICECTL']
const diagnosticCommand = '~/Library/Application\\ Support/BaseAI/DeviceAgent/venv/bin/python -m device_agent.main diagnose'
const xcodeCommands = ['xcode-select -p', 'xcodebuild -version', 'sudo xcode-select -s /Applications/Xcode.app/Contents/Developer', 'xcodebuild -runFirstLaunch']
const installCommands = ['npm install -g appium', 'appium driver install xcuitest', 'appium driver doctor xcuitest']
const readiness = ref({ status: 'UNKNOWN', stale: true, checkedAt: null, checks: [] })
let readinessRefreshTimer = null

const REASON_ACTION_TARGETS = {
  DEVICE_NOT_CONFIGURED: 'GOTO_WDA_CONFIG',
  BUNDLE_ID_NOT_CONFIGURED: 'GOTO_WDA_CONFIG',
  DEVICE_NOT_FOUND: 'GOTO_WDA_CONFIG',
  DEVICE_NOT_CONNECTED: 'GOTO_WDA_CONFIG',
  APP_NOT_INSTALLED_WDA: 'GOTO_BUILD_WDA',
}
const EMPTY_GUIDANCE = { reason: '', action: '', actionLabel: '', target: '' }

const readinessSourceHint = computed(() => defaultAgentId.value
  ? t('deviceAgentGuide.readiness.defaultAgentHint', { agentId: defaultAgentId.value })
  : t('deviceAgentGuide.readiness.defaultAgentMissing'))

const readinessChecks = computed(() => {
  const current = new Map((readiness.value.checks || []).map(item => [item.code, item]))
  const reported = current.size > 0
  return diagnosticNames.map(code => {
    const item = current.get(code) || { code, status: 'UNKNOWN', message: '' }
    return { ...item, guidance: checkGuidance(item, reported) }
  })
})
const passedChecks = computed(() => readinessChecks.value.filter(item => item.status === 'PASS').length)

/** 解析单个检查项的原因、动作文案与控制台跳转目标。 */
function checkGuidance(item, reported) {
  if (item.status === 'PASS') return EMPTY_GUIDANCE
  if (!reported) return EMPTY_GUIDANCE
  return buildGuidance(item.code, item.code)
}

/** 由归因键与动作键组装可安全展示的诊断引导。 */
function buildGuidance(reasonKey, actionKey) {
  const target = REASON_ACTION_TARGETS[actionKey] || ''
  return {
    reason: reasonKey ? translateOrEmpty(`deviceAgentGuide.readiness.reasons.${reasonKey}`) : '',
    action: translateOrEmpty(`deviceAgentGuide.readiness.actions.${actionKey}`),
    actionLabel: target ? translateOrEmpty(`deviceAgentGuide.readiness.actionLabels.${target}`) : '',
    target,
  }
}

/** 缺失的文案键返回空串，避免把内部键名暴露到页面。 */
function translateOrEmpty(key) {
  const text = t(key)
  return text === key ? '' : text
}

/** 执行诊断项对应的控制台跳转，不为 Mac 或 iOS 设备手工操作伪造按钮。 */
function runCheckAction(target) {
  if (target === 'GOTO_BUILD_WDA') router.push({ path: '/automation/device-agents/onboarding', query: { step: 'setup' } })
  else if (target === 'GOTO_WDA_CONFIG') router.push({ path: '/automation/device-agents', query: { open: 'wda-config' } })
  else if (target === 'GOTO_AGENT_LIST') router.push({ path: '/automation/device-agents' })
}

/** 查找当前默认且有效的 Agent，检测和诊断下发始终绑定同一实例。 */
async function resolveDefaultAgent() {
  const response = await http.get('/automation/device-agents', { params: { page: 1, size: 100 } })
  const agents = response.data?.items || []
  const selected = agents.find(item => item.isDefault && item.pairingStatus === 'PAIRED' && !item.revokedAt)
  defaultAgentId.value = selected?.agentId || ''
  return defaultAgentId.value
}

/** 加载默认 Agent 最近一次经签名上报的脱敏环境诊断。 */
async function loadReadiness() {
  readinessLoading.value = true
  try {
    const agentId = await resolveDefaultAgent()
    if (!agentId) {
      readiness.value = { status: 'UNKNOWN', stale: true, checkedAt: null, checks: [] }
      return
    }
    readiness.value = (await http.get(`/automation/device-agents/${agentId}/readiness`)).data
  } catch (error) {
    showHttpError(error)
  } finally {
    readinessLoading.value = false
  }
}

/** 向默认 Agent 下发诊断命令，并在 Agent 有时间领取命令后刷新结果。 */
async function triggerDiagnostics() {
  triggeringDiagnostics.value = true
  try {
    const agentId = defaultAgentId.value || await resolveDefaultAgent()
    if (!agentId) {
      ElMessage.warning(t('deviceAgentGuide.readiness.defaultAgentMissing'))
      return
    }
    await http.post('/automation/device-agents/commands', { agentId, commandType: 'DIAGNOSTICS' })
    ElMessage.success(t('deviceAgentGuide.readiness.diagnosticsTriggered'))
    clearTimeout(readinessRefreshTimer)
    readinessRefreshTimer = setTimeout(loadReadiness, 3000)
  } catch (error) {
    showHttpError(error)
  } finally {
    triggeringDiagnostics.value = false
  }
}

/** 映射诊断状态至稳定的 Element Plus 标签颜色。 */
function readinessType(status) {
  if (status === 'PASS') return 'success'
  if (status === 'WARN' || status === 'UNKNOWN') return 'warning'
  return 'danger'
}

/** 格式化后端记录的检测时间，不显示设备或签名敏感信息。 */
function formatDate(value) { return value ? new Date(value).toLocaleString() : '' }

/** 复制环境检查命令，页面不持久化剪贴板内容。 */
async function copyText(value) {
  try {
    await navigator.clipboard.writeText(value)
    ElMessage.success(t('deviceAgentGuide.copied'))
  } catch {
    ElMessage.error(t('deviceAgentGuide.copyFailed'))
  }
}

onMounted(loadReadiness)

/** 离开指南页时取消延迟刷新，避免卸载后的页面继续请求。 */
onUnmounted(() => clearTimeout(readinessRefreshTimer))
</script>

<style scoped>
.agent-guide{max-width:1180px;margin:0 auto;padding-bottom:34px;overflow:hidden}.guide-toolbar{display:flex;align-items:center;justify-content:space-between;gap:16px;padding:18px 24px;border-bottom:1px solid var(--app-border)}.guide-scope{display:inline-flex;align-items:center;gap:6px;color:var(--app-muted);font-size:12px}.guide-hero{display:grid;grid-template-columns:minmax(0,1fr) 320px;gap:42px;align-items:center;padding:44px 48px;background:linear-gradient(135deg,#f7faff,#fff 58%,#f2fbf8)}.guide-eyebrow{display:inline-flex;padding:5px 10px;border-radius:999px;background:#eaf1ff;color:#315fcb;font-size:11px;font-weight:700;letter-spacing:.08em}.guide-hero h1{margin:15px 0 10px;color:#18243a;font-size:34px}.guide-hero p{max-width:680px;margin:0;color:#5e6b80;font-size:15px;line-height:1.8}.hero-actions{display:flex;gap:10px;margin-top:22px}.agent-figure{display:flex;min-height:190px;flex-direction:column;align-items:center;justify-content:center;gap:10px;border:1px solid #dce5f3;border-top:4px solid #315fcb;border-radius:18px;background:#fff;box-shadow:0 16px 38px rgba(39,64,105,.1)}.agent-figure>span{display:grid;width:62px;height:62px;place-items:center;border-radius:17px;background:#eaf1ff;color:#315fcb;font-size:32px}.agent-figure strong{font-size:18px}.agent-figure small{color:var(--app-muted);font-size:11px}.setup-section,.commands-section{margin:30px 24px 0}.section-title{display:flex;align-items:flex-start;gap:12px;margin-bottom:16px}.section-title>span{display:grid;width:34px;height:34px;flex:0 0 auto;place-items:center;border-radius:10px;background:#edf3ff;color:#315fcb;font-size:11px;font-weight:800}.section-title h2,.section-title p{margin:0}.section-title h2{font-size:20px}.section-title p{margin-top:4px;color:var(--app-muted);font-size:12px}.setup-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.setup-grid article{position:relative;padding:18px 18px 16px 56px;border:1px solid var(--app-border);border-radius:12px;background:#fff}.setup-grid article>b{position:absolute;top:18px;left:18px;display:grid;width:26px;height:26px;place-items:center;border-radius:8px;background:#eaf1ff;color:#315fcb;font-size:11px}.setup-grid h3,.setup-grid p{margin:0}.setup-grid h3{font-size:15px}.setup-grid p{margin-top:7px;color:var(--app-muted);font-size:12px;line-height:1.65}.setup-grid code{display:block;margin-top:10px;padding:7px 9px;border-radius:6px;background:#f1f5fb;color:#31558f;font-size:10px}.requirement-list{display:flex;flex-wrap:wrap;gap:6px;margin-top:10px}.requirement-list span{padding:4px 7px;border-radius:6px;background:#edf8f4;color:#20745d;font-size:10px}.command-groups{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.command-groups article{overflow:hidden;border:1px solid var(--app-border);border-radius:12px;background:#172033;color:#e9efff}.command-groups h3,.command-groups p{margin:0;padding-right:16px;padding-left:16px}.command-groups h3{padding-top:16px;font-size:14px}.command-groups p{padding-top:5px;padding-bottom:10px;color:#b6c2d6;font-size:11px;line-height:1.55}.command-row{display:flex;align-items:center;justify-content:space-between;gap:10px;min-height:42px;padding:0 12px;border-top:1px solid rgba(255,255,255,.08)}.command-row code{overflow:auto;color:#e9efff;font:11px/1.5 ui-monospace,SFMono-Regular,Menlo,monospace;white-space:nowrap}.command-row :deep(.el-button){color:#a9bfe9}.commands-section>.el-alert{margin-top:12px}.readiness-panel{margin:30px 24px 0;padding:22px;border:1px solid #dbe5f3;border-radius:14px;background:linear-gradient(145deg,#f8fbff,#fff)}.readiness-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.readiness-actions{display:flex;gap:8px}.readiness-kicker{color:#315fcb;font-size:10px;font-weight:800;letter-spacing:.08em}.readiness-head h2,.readiness-head p{margin:0}.readiness-head h2{margin-top:5px;font-size:20px}.readiness-head p{max-width:720px;margin-top:5px;color:var(--app-muted);font-size:12px;line-height:1.6}.readiness-head .readiness-source{margin-top:9px;padding:7px 10px;border-radius:7px;background:#eef4ff;color:#41537a;font-size:11px}.readiness-summary{display:flex;align-items:center;gap:12px;margin-top:18px;padding:14px;border:1px solid #dfe7f3;border-radius:10px;background:#fff}.readiness-score{display:grid;width:46px;height:46px;flex:0 0 auto;place-items:center;border-radius:12px;background:#f0f4fb;color:#405272;font-size:12px;font-weight:800}.readiness-summary--pass .readiness-score{background:#e4f5ee;color:#17805f}.readiness-summary--warn .readiness-score,.readiness-summary--unknown .readiness-score{background:#fff5df;color:#a96809}.readiness-summary--fail .readiness-score{background:#feeceb;color:#c63d37}.readiness-summary>div{min-width:0;flex:1}.readiness-summary strong,.readiness-summary small{display:block}.readiness-summary small{margin-top:4px;color:var(--app-muted);font-size:11px}.readiness-panel>.el-alert{margin-top:10px}.diagnostic-command{display:grid;grid-template-columns:minmax(160px,.75fr) minmax(240px,1fr) auto;align-items:center;gap:12px;margin-top:12px;padding:12px;border-radius:9px;background:#f2f6fc}.diagnostic-command strong,.diagnostic-command small{display:block}.diagnostic-command strong{font-size:12px}.diagnostic-command small{margin-top:3px;color:var(--app-muted);font-size:10px;line-height:1.5}.diagnostic-command code{overflow:auto;padding:8px;border-radius:6px;background:#fff;color:#31558f;font-size:10px;white-space:nowrap}.readiness-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:9px;margin-top:12px}.readiness-check{display:grid;grid-template-columns:9px minmax(0,1fr) auto;align-items:center;gap:10px;padding:11px;border:1px solid #e1e7ef;border-radius:9px;background:#fff}.check-state{width:9px;height:9px;border-radius:50%;background:#d6a342}.readiness-check--pass .check-state{background:#1b9b78}.readiness-check--fail .check-state{background:#d54b44}.readiness-check>div:nth-child(2){min-width:0}.readiness-check strong,.readiness-check small{display:block}.readiness-check strong{font-size:12px}.readiness-check small{margin-top:3px;overflow:hidden;color:var(--app-muted);font-size:10px;line-height:1.4;text-overflow:ellipsis;white-space:nowrap}.readiness-check small.check-reason{overflow:visible;color:#a33f38;white-space:normal}.readiness-check--warn small.check-reason{color:#8a6212}.check-action{display:flex;align-items:center;flex-wrap:wrap;gap:6px;margin:5px 0 0;color:#41537a;font-size:10px;line-height:1.5}.check-action .el-button{height:auto;padding:0;font-size:10px}.check-result{display:flex;align-items:flex-end;flex-direction:column;gap:4px}.check-result code{max-width:100px;overflow:hidden;color:#68778d;font-size:9px;text-overflow:ellipsis;white-space:nowrap}@media(max-width:850px){.guide-hero{grid-template-columns:1fr}.agent-figure{min-height:150px}.setup-grid,.command-groups,.readiness-grid{grid-template-columns:1fr}.readiness-head{align-items:stretch;flex-direction:column}.diagnostic-command{grid-template-columns:1fr}}@media(max-width:560px){.guide-toolbar{align-items:flex-start;flex-direction:column;padding:14px 16px}.guide-hero{padding:30px 18px}.guide-hero h1{font-size:27px}.hero-actions{align-items:stretch;flex-direction:column}.setup-section,.commands-section,.readiness-panel{margin-right:16px;margin-left:16px}.readiness-panel{padding:16px}.readiness-summary{align-items:flex-start;flex-wrap:wrap}.readiness-summary>.el-tag{margin-left:58px}}
</style>
