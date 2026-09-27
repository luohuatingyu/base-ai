<template>
  <div class="package-adjustment-page">
    <section class="panel intro-panel">
      <div class="section-head">
        <div>
          <span class="eyebrow">{{ t('packageAdjustment.eyebrow') }}</span>
          <h2>{{ t('packageAdjustment.title') }}</h2>
          <p>{{ t('packageAdjustment.description') }}</p>
        </div>
      </div>
      <el-alert :title="t('packageAdjustment.securityNotice')" type="warning" :closable="false" show-icon />
    </section>

    <section class="panel">
      <div class="section-head">
        <div><h3>{{ t('packageAdjustment.inputTitle') }}</h3><p>{{ t('packageAdjustment.inputHint') }}</p></div>
        <el-button type="primary" :loading="parsing" :disabled="!token.trim()" @click="parseToken">{{ t('packageAdjustment.parse') }}</el-button>
      </div>
      <el-input v-model="token" type="textarea" :rows="7" resize="vertical" :placeholder="t('packageAdjustment.tokenPlaceholder')" autocomplete="off" />
    </section>

    <section v-if="parsed" class="panel">
      <div class="section-head"><div><h3>{{ t('packageAdjustment.resultTitle') }}</h3><p>{{ t('packageAdjustment.resultHint') }}</p></div></div>
      <el-descriptions :column="2" border>
        <el-descriptions-item :label="t('packageAdjustment.email')">{{ parsed.payload.email || t('packageAdjustment.notSet') }}</el-descriptions-item>
        <el-descriptions-item :label="t('packageAdjustment.accountId')">{{ parsed.auth.chatgpt_account_id || t('packageAdjustment.notSet') }}</el-descriptions-item>
        <el-descriptions-item :label="t('packageAdjustment.userId')">{{ parsed.auth.chatgpt_user_id || t('packageAdjustment.notSet') }}</el-descriptions-item>
        <el-descriptions-item :label="t('packageAdjustment.algorithm')">{{ parsed.header.alg || t('packageAdjustment.notSet') }}</el-descriptions-item>
        <el-descriptions-item :label="t('packageAdjustment.issuedAt')">{{ formatTimestamp(parsed.payload.iat) || t('packageAdjustment.notSet') }}</el-descriptions-item>
        <el-descriptions-item :label="t('packageAdjustment.expiresAt')">{{ formatTimestamp(parsed.payload.exp) || t('packageAdjustment.notSet') }}</el-descriptions-item>
      </el-descriptions>
      <el-alert class="key-notice" :title="t('packageAdjustment.keyNotice')" type="info" :closable="false" show-icon />
      <el-form class="signing-form" label-position="top">
        <el-form-item :label="t('packageAdjustment.privateKey')">
          <el-input v-model="privateKey" type="textarea" :rows="5" resize="vertical" :placeholder="t('packageAdjustment.privateKeyPlaceholder')" autocomplete="off" spellcheck="false" />
        </el-form-item>
      </el-form>
      <el-form class="plan-form" label-position="top">
        <el-form-item :label="t('packageAdjustment.planType')">
          <el-input v-model="planType" :placeholder="t('packageAdjustment.planPlaceholder')" maxlength="80" show-word-limit @keyup.enter="generateToken" />
        </el-form-item>
        <el-button type="primary" :loading="generating" :disabled="!planType.trim() || !privateKey.trim()" @click="generateToken">{{ t('packageAdjustment.generate') }}</el-button>
      </el-form>
    </section>

    <section v-if="generatedToken" class="panel">
      <div class="section-head"><div><h3>{{ t('packageAdjustment.generatedTitle') }}</h3><p>{{ t('packageAdjustment.generatedHint') }}</p></div><el-button @click="copyGenerated">{{ t('packageAdjustment.copy') }}</el-button></div>
      <el-input v-model="generatedToken" type="textarea" :rows="9" readonly />
      <el-alert class="signature-alert" :title="t('packageAdjustment.signatureReady')" type="success" :closable="false" show-icon />
    </section>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { buildTokenWithPlan, formatJwtTimestamp, parseJwtToken } from '../utils/jwtToken'

const { t, locale } = useI18n()
const token = ref('')
const parsed = ref(null)
const planType = ref('')
const privateKey = ref('')
const generatedToken = ref('')
const parsing = ref(false)
const generating = ref(false)

/** 解析输入 Token 并回显套餐字段，所有凭证只保留在当前页面内存中。 */
function parseToken() {
  parsing.value = true
  try {
    parsed.value = parseJwtToken(token.value)
    planType.value = parsed.value.planType
    generatedToken.value = ''
    if (!planType.value) ElMessage.warning(t('packageAdjustment.planMissing'))
    else ElMessage.success(t('packageAdjustment.parseSuccess'))
  } catch (error) {
    parsed.value = null
    generatedToken.value = ''
    ElMessage.error(error.message || t('packageAdjustment.parseFailed'))
  } finally {
    parsing.value = false
  }
}

/** 使用页面内存中的 issuer 私钥重新签名套餐字段。 */
async function generateToken() {
  generating.value = true
  try {
    generatedToken.value = await buildTokenWithPlan(parsed.value, planType.value, privateKey.value)
    ElMessage.success(t('packageAdjustment.generateSuccess'))
  } catch (error) {
    ElMessage.error(error.message || t('packageAdjustment.generateFailed'))
  } finally {
    generating.value = false
  }
}

/** 复制生成结果，失败时保留文本框内容供用户手动复制。 */
async function copyGenerated() {
  try {
    await navigator.clipboard.writeText(generatedToken.value)
    ElMessage.success(t('packageAdjustment.copied'))
  } catch {
    ElMessage.error(t('packageAdjustment.copyFailed'))
  }
}

/** 按当前界面语言格式化 JWT 时间戳。 */
function formatTimestamp(value) {
  return formatJwtTimestamp(value, locale.value)
}
</script>

<style scoped>
.package-adjustment-page { display: grid; gap: 18px; }
.eyebrow { color: var(--el-color-primary); font-size: 12px; letter-spacing: .12em; font-weight: 700; }
.intro-panel :deep(.el-alert) { margin-top: 16px; }
.key-notice { margin-top: 18px; }
.signing-form { margin-top: 18px; }
.plan-form { display: flex; align-items: flex-end; gap: 14px; margin-top: 20px; }
.plan-form .el-form-item { flex: 1; margin-bottom: 0; }
.signature-alert { margin-top: 16px; }
@media (max-width: 720px) {
  .plan-form { align-items: stretch; flex-direction: column; }
  .plan-form .el-form-item { margin-bottom: 0; }
}
</style>
