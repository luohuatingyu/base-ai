<template>
  <div class="panel api-trigger-security">
    <div class="section-head">
      <div>
        <h2>{{ t('apiTriggerSecurity.title') }}</h2>
        <p>{{ t('apiTriggerSecurity.description') }}</p>
      </div>
      <el-button v-if="auth.hasPermission('automation:api-trigger-security:update')" type="primary" @click="save">
        {{ t('common.save') }}
      </el-button>
    </div>

    <el-alert :title="t('apiTriggerSecurity.defaultPolicy')" type="info" :closable="false" show-icon />
    <el-alert v-if="hasAnyRule" :title="t('apiTriggerSecurity.anyWarning')" type="warning" :closable="false" show-icon />

    <el-form label-position="top" class="security-form">
      <el-form-item :label="t('apiTriggerSecurity.allowedHosts')">
        <div class="host-rule-editor">
          <div v-for="(rule, index) in hostRuleRows" :key="index" class="host-rule-editor-row">
            <el-select v-model="rule.type" class="host-rule-type" @change="changeRuleType(rule)">
              <el-option v-for="type in hostRuleTypes" :key="type" :label="t(`apiTriggerSecurity.ruleTypes.${type}`)" :value="type" />
            </el-select>
            <el-input v-if="rule.type !== 'ANY'" v-model="rule.value" :placeholder="t('apiTriggerSecurity.ruleValuePlaceholder')" />
            <div v-else class="any-rule-value">{{ t('apiTriggerSecurity.anyRuleValue') }}</div>
            <el-button link type="danger" @click="deleteHostRule(index)">{{ t('apiTriggerSecurity.deleteRule') }}</el-button>
          </div>
          <el-button plain type="primary" @click="addHostRule">+ {{ t('apiTriggerSecurity.addRule') }}</el-button>
        </div>
        <div class="form-help">{{ t('apiTriggerSecurity.allowedHostsHelp') }}</div>
      </el-form-item>
      <el-form-item :label="t('apiTriggerSecurity.allowLoopback')">
        <el-switch v-model="form.allowLoopback" />
        <span class="switch-help">{{ t('apiTriggerSecurity.allowLoopbackHelp') }}</span>
      </el-form-item>
      <el-form-item :label="t('apiTriggerSecurity.allowPrivateNetwork')">
        <el-switch v-model="form.allowPrivateNetwork" />
        <span class="switch-help">{{ t('apiTriggerSecurity.allowPrivateNetworkHelp') }}</span>
      </el-form-item>
    </el-form>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { createHostRule, HOST_RULE_TYPES, normalizeHostRules, toHostRuleRows } from '../utils/hostRules'

const { t } = useI18n()
const auth = useAuthStore()
const form = reactive({ allowLoopback: true, allowPrivateNetwork: false })
const hostRuleTypes = HOST_RULE_TYPES
const hostRuleRows = ref([createHostRule()])
const hasAnyRule = computed(() => normalizeHostRules(hostRuleRows.value).some(rule => rule.type === 'ANY'))

/** 新增一条默认精确匹配规则。 */
function addHostRule() {
  hostRuleRows.value.push(createHostRule())
}

/** 删除规则行，全部删除后保留一条空白输入。 */
function deleteHostRule(index) {
  hostRuleRows.value.splice(index, 1)
  if (!hostRuleRows.value.length) hostRuleRows.value.push(createHostRule())
}

/** 切换为任意 Host 时清除不再使用的规则值。 */
function changeRuleType(rule) {
  if (rule.type === 'ANY') rule.value = null
}

/** 加载当前生效配置并转换为便于编辑的逐行文本。 */
async function load() {
  const { data } = await http.get('/automation/api-trigger-security')
  hostRuleRows.value = toHostRuleRows(data.hostRules)
  form.allowLoopback = Boolean(data.allowLoopback)
  form.allowPrivateNetwork = Boolean(data.allowPrivateNetwork)
}

/** 保存配置；完全放开公网和私网时要求管理员再次确认。 */
async function save() {
  const hostRules = normalizeHostRules(hostRuleRows.value)
  if (hostRules.some(rule => rule.type === 'ANY') && form.allowLoopback && form.allowPrivateNetwork) {
    await ElMessageBox.confirm(t('apiTriggerSecurity.fullAccessConfirm'), t('apiTriggerSecurity.riskTitle'), { type: 'warning' })
  }
  const { data } = await http.put('/automation/api-trigger-security', {
    hostRules,
    allowLoopback: form.allowLoopback,
    allowPrivateNetwork: form.allowPrivateNetwork
  })
  hostRuleRows.value = toHostRuleRows(data.hostRules)
  form.allowLoopback = Boolean(data.allowLoopback)
  form.allowPrivateNetwork = Boolean(data.allowPrivateNetwork)
  ElMessage.success(t('common.successSaved'))
}

onMounted(load)
</script>

<style scoped>
.api-trigger-security { display: grid; gap: 18px; }
.security-form { max-width: 760px; }
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
