<template>
  <div class="panel api-trigger-security">
    <div class="section-head">
      <div>
        <h2>{{ t('apiTriggerSecurity.title') }}</h2>
        <p>{{ t('apiTriggerSecurity.description') }}</p>
      </div>
      <span v-if="loaded && canUpdate" class="auto-save-status" :class="`is-${saveStatus}`">
        {{ t(`apiTriggerSecurity.autoSaveStatus.${saveStatus}`) }}
      </span>
    </div>

    <div v-loading="loading" class="security-content">
      <template v-if="loaded">
        <el-alert :title="t('apiTriggerSecurity.defaultPolicy')" type="info" :closable="false" show-icon />
        <el-alert v-if="hasAnyRule" :title="t('apiTriggerSecurity.anyWarning')" type="warning" :closable="false" show-icon />

        <el-form label-position="top" class="security-form" :disabled="!canUpdate">
          <el-form-item :label="t('apiTriggerSecurity.allowedHosts')">
            <div class="host-rule-editor">
              <div v-for="(rule, index) in hostRuleRows" :key="index" class="host-rule-editor-row">
                <el-select v-model="rule.type" class="host-rule-type" @change="changeRuleType(rule)">
                  <el-option v-for="type in hostRuleTypes" :key="type" :label="t(`apiTriggerSecurity.ruleTypes.${type}`)" :value="type" />
                </el-select>
                <el-input v-if="rule.type !== 'ANY'" v-model="rule.value" :placeholder="t('apiTriggerSecurity.ruleValuePlaceholder')" @input="scheduleAutomaticSave()" />
                <div v-else class="any-rule-value">{{ t('apiTriggerSecurity.anyRuleValue') }}</div>
                <el-button link type="danger" @click="deleteHostRule(index)">{{ t('apiTriggerSecurity.deleteRule') }}</el-button>
              </div>
              <el-button plain type="primary" @click="addHostRule">+ {{ t('apiTriggerSecurity.addRule') }}</el-button>
            </div>
            <div class="form-help">{{ t('apiTriggerSecurity.allowedHostsHelp') }}</div>
          </el-form-item>
          <el-form-item :label="t('apiTriggerSecurity.allowLoopback')">
            <el-switch v-model="form.allowLoopback" @change="scheduleAutomaticSave(true)" />
            <span class="switch-help">{{ t('apiTriggerSecurity.allowLoopbackHelp') }}</span>
          </el-form-item>
          <el-form-item :label="t('apiTriggerSecurity.allowPrivateNetwork')">
            <el-switch v-model="form.allowPrivateNetwork" @change="scheduleAutomaticSave(true)" />
            <span class="switch-help">{{ t('apiTriggerSecurity.allowPrivateNetworkHelp') }}</span>
          </el-form-item>
        </el-form>
      </template>
      <el-button v-else-if="!loading" @click="load">{{ t('common.refresh') }}</el-button>
    </div>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { createHostRule, createLatestAutoSaver, HOST_RULE_TYPES, normalizeHostRules, toHostRuleRows } from '../utils/hostRules'

const { t } = useI18n()
const auth = useAuthStore()
const form = reactive({ allowLoopback: true, allowPrivateNetwork: false })
const hostRuleTypes = HOST_RULE_TYPES
const hostRuleRows = ref([createHostRule()])
const loading = ref(true)
const loaded = ref(false)
const saveStatus = ref('saved')
const effectiveConfiguration = ref(null)
const canUpdate = computed(() => auth.hasPermission('automation:api-trigger-security:update'))
const hasAnyRule = computed(() => normalizeHostRules(hostRuleRows.value).some(rule => rule.type === 'ANY'))
const autoSaver = createLatestAutoSaver(persistConfiguration)

/** 将接口数据规范化为可比较、可提交的安全配置快照。 */
function normalizeConfiguration(configuration) {
  return {
    hostRules: normalizeHostRules(configuration?.hostRules),
    allowLoopback: Boolean(configuration?.allowLoopback),
    allowPrivateNetwork: Boolean(configuration?.allowPrivateNetwork)
  }
}

/** 读取编辑器当前值并生成不受后续输入影响的提交快照。 */
function currentConfiguration() {
  return normalizeConfiguration({
    hostRules: hostRuleRows.value,
    allowLoopback: form.allowLoopback,
    allowPrivateNetwork: form.allowPrivateNetwork
  })
}

/** 比较两个已规范化快照，避免重复提交未发生语义变化的配置。 */
function configurationsEqual(left, right) {
  return JSON.stringify(left) === JSON.stringify(right)
}

/** 判断配置是否同时放开任意 Host、回环地址和其他私有网络。 */
function isFullAccess(configuration) {
  return configuration.allowLoopback && configuration.allowPrivateNetwork
    && configuration.hostRules.some(rule => rule.type === 'ANY')
}

/** 使用已生效快照替换编辑器内容，供加载、响应规范化和失败恢复使用。 */
function replaceEditor(configuration) {
  hostRuleRows.value = toHostRuleRows(configuration.hostRules)
  form.allowLoopback = configuration.allowLoopback
  form.allowPrivateNetwork = configuration.allowPrivateNetwork
}

/** 新增一条默认精确匹配规则。 */
function addHostRule() {
  hostRuleRows.value.push(createHostRule())
}

/** 删除规则行，全部删除后保留一条空白输入。 */
function deleteHostRule(index) {
  hostRuleRows.value.splice(index, 1)
  if (!hostRuleRows.value.length) hostRuleRows.value.push(createHostRule())
  scheduleAutomaticSave(true)
}

/** 切换为任意 Host 时清除不再使用的规则值。 */
function changeRuleType(rule) {
  if (rule.type === 'ANY') rule.value = null
  scheduleAutomaticSave(true)
}

/** 根据控件类型调度自动保存；文本输入防抖，离散修改立即处理。 */
function scheduleAutomaticSave(immediate = false) {
  if (!loaded.value || !canUpdate.value) return
  const configuration = currentConfiguration()
  if (configurationsEqual(configuration, effectiveConfiguration.value)) return
  autoSaver.schedule(configuration, immediate)
}

/** 判断异常是否来自管理员主动取消高风险确认。 */
function isConfirmationCancelled(error) {
  return error === 'cancel' || error === 'close' || error?.action === 'cancel' || error?.action === 'close'
}

/** 串行保存配置；确认取消或请求失败时清理后续修改并恢复最近生效值。 */
async function persistConfiguration(configuration) {
  if (configurationsEqual(configuration, effectiveConfiguration.value)) return
  saveStatus.value = 'saving'
  try {
    if (isFullAccess(configuration) && !isFullAccess(effectiveConfiguration.value)) {
      await ElMessageBox.confirm(t('apiTriggerSecurity.fullAccessConfirm'), t('apiTriggerSecurity.riskTitle'), { type: 'warning' })
    }
    const { data } = await http.put('/automation/api-trigger-security', configuration)
    const savedConfiguration = normalizeConfiguration(data)
    effectiveConfiguration.value = savedConfiguration
    if (configurationsEqual(currentConfiguration(), configuration)) replaceEditor(savedConfiguration)
    saveStatus.value = 'saved'
  } catch (error) {
    autoSaver.clear()
    replaceEditor(effectiveConfiguration.value)
    if (isConfirmationCancelled(error)) {
      saveStatus.value = 'saved'
      return
    }
    saveStatus.value = 'error'
    ElMessage.error(t('apiTriggerSecurity.autoSaveFailed'))
  }
}

/** 加载当前生效配置并转换为便于编辑的逐行文本。 */
async function load() {
  loading.value = true
  loaded.value = false
  try {
    const { data } = await http.get('/automation/api-trigger-security')
    effectiveConfiguration.value = normalizeConfiguration(data)
    replaceEditor(effectiveConfiguration.value)
    saveStatus.value = 'saved'
    loaded.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
onBeforeUnmount(() => autoSaver.dispose())
</script>

<style scoped>
.api-trigger-security { display: grid; gap: 18px; }
.security-content { display: grid; gap: 18px; min-height: 160px; align-content: start; }
.security-form { max-width: 760px; }
.auto-save-status { color: var(--el-text-color-secondary); font-size: 14px; }
.auto-save-status.is-saving { color: var(--el-color-primary); }
.auto-save-status.is-error { color: var(--el-color-danger); }
.host-rule-editor { display: grid; gap: 10px; width: 100%; }
.host-rule-editor-row { display: grid; grid-template-columns: 150px minmax(0, 1fr) auto; gap: 10px; align-items: center; }
.host-rule-type { width: 100%; }
.any-rule-value { color: var(--el-text-color-secondary); padding: 0 12px; }
.form-help, .switch-help { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.switch-help { margin-left: 12px; }
@media (max-width: 640px) {
  .host-rule-editor-row { grid-template-columns: 1fr; }
}
</style>
