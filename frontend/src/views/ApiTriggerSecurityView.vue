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
    <el-alert v-if="hasWildcard" :title="t('apiTriggerSecurity.wildcardWarning')" type="warning" :closable="false" show-icon />

    <el-form label-position="top" class="security-form">
      <el-form-item :label="t('apiTriggerSecurity.allowedHosts')">
        <el-input v-model="form.allowedHosts" type="textarea" :rows="8" :placeholder="t('apiTriggerSecurity.allowedHostsPlaceholder')" />
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
import { computed, onMounted, reactive } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const form = reactive({ allowedHosts: '', allowLoopback: true, allowPrivateNetwork: false })
const hasWildcard = computed(() => parseAllowedHosts().includes('*'))

/** 将逗号或换行分隔的输入转换为 Host 规则列表。 */
function parseAllowedHosts() {
  return [...new Set(form.allowedHosts.split(/[\n,]/).map(item => item.trim()).filter(Boolean))]
}

/** 加载当前生效配置并转换为便于编辑的逐行文本。 */
async function load() {
  const { data } = await http.get('/automation/api-trigger-security')
  form.allowedHosts = (data.allowedHosts || []).join('\n')
  form.allowLoopback = Boolean(data.allowLoopback)
  form.allowPrivateNetwork = Boolean(data.allowPrivateNetwork)
}

/** 保存配置；完全放开公网和私网时要求管理员再次确认。 */
async function save() {
  const allowedHosts = parseAllowedHosts()
  if (allowedHosts.includes('*') && form.allowLoopback && form.allowPrivateNetwork) {
    await ElMessageBox.confirm(t('apiTriggerSecurity.fullAccessConfirm'), t('apiTriggerSecurity.riskTitle'), { type: 'warning' })
  }
  const { data } = await http.put('/automation/api-trigger-security', {
    allowedHosts,
    allowLoopback: form.allowLoopback,
    allowPrivateNetwork: form.allowPrivateNetwork
  })
  form.allowedHosts = (data.allowedHosts || []).join('\n')
  form.allowLoopback = Boolean(data.allowLoopback)
  form.allowPrivateNetwork = Boolean(data.allowPrivateNetwork)
  ElMessage.success(t('common.successSaved'))
}

onMounted(load)
</script>

<style scoped>
.api-trigger-security { display: grid; gap: 18px; }
.security-form { max-width: 760px; }
.form-help, .switch-help { color: var(--el-text-color-secondary); font-size: 13px; line-height: 1.6; }
.switch-help { margin-left: 12px; }
</style>
