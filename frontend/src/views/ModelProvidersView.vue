<template>
  <div class="panel">
    <div class="section-head">
      <div>
        <h2>{{ t('providers.title') }}</h2>
        <p>{{ t('providers.description') }}</p>
      </div>
      <el-button v-if="auth.hasPermission('ai:model:provider:create')" type="primary" @click="open()">
        {{ t('providers.add') }}
      </el-button>
    </div>

    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('common.code')" min-width="160" />
      <el-table-column prop="name" :label="t('common.name')" min-width="180" />
      <el-table-column prop="baseUrl" :label="t('providers.baseUrl')" min-width="300" />
      <el-table-column prop="concurrencyLevel" :label="t('providers.concurrencyLevel')" min-width="180" />
      <el-table-column prop="concurrencyLimit" :label="t('providers.concurrency')" min-width="160" />
      <el-table-column :label="t('common.operation')" width="240" fixed="right">
        <template #default="scope">
          <div class="table-actions">
            <el-button v-if="auth.isAdmin" link type="primary" @click="viewKeys(scope.row)">
              {{ t('providers.viewKeys') }}
            </el-button>
            <el-button v-if="auth.hasPermission('ai:model:provider:update')" link type="primary" @click="open(scope.row)">
              {{ t('common.edit') }}
            </el-button>
            <el-button link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('providers.edit') : t('providers.add')" width="620px">
      <el-form label-width="100px">
        <el-form-item :label="t('common.code')"><el-input v-model="form.code" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item label="Base URL"><el-input v-model="form.baseUrl" /></el-form-item>
        <el-form-item :label="t('providers.apiKeys')">
          <div v-for="(_, index) in keyRows" :key="index" class="api-key-editor-row">
            <el-input v-model="keyRows[index]" :placeholder="t('providers.keyPlaceholder')" />
            <el-button link type="danger" @click="deleteKey(index)">{{ t('providers.deleteKey') }}</el-button>
          </div>
          <el-button plain type="primary" @click="addKey">+ {{ t('providers.addKey') }}</el-button>
        </el-form-item>
        <el-form-item :label="t('providers.concurrencyLevel')">
          <el-select v-model="form.concurrencyLevel">
            <el-option :label="t('providers.provider')" value="PROVIDER" />
            <el-option label="API Key" value="API_KEY" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('providers.concurrency')"><el-input-number v-model="form.concurrencyLimit" :min="1" /></el-form-item>
        <el-form-item :label="t('providers.timeout')"><el-input-number v-model="form.timeoutSeconds" :min="1" /></el-form-item>
        <el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>

    <el-dialog v-model="keysVisible" :title="t('providers.viewKeys')" width="520px" @closed="clearKeys">
      <div v-for="(key, index) in keysContent" :key="key" class="api-key-row">
        <code>{{ index + 1 }}. {{ key }}</code>
        <el-button link type="primary" @click="copyKey(key)">{{ t('providers.copyKey') }}</el-button>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { copyApiKey, normalizeApiKeys, serializeApiKeyRows, splitApiKeys } from '../utils/apiKeys'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const visible = ref(false)
const keysVisible = ref(false)
const keysContent = ref([])
const keyRows = ref([''])
const form = reactive(defaultForm())

/** 加载模型供应商列表；接口只返回脱敏后的密钥状态。 */
async function load() {
  rows.value = (await http.get('/models/providers')).data
}

/** 通过当前管理员密码二次验证后读取并规范化指定供应商的 API Key。 */
async function loadKeys(id, password) {
  const response = await http.post(`/models/providers/${id}/api-keys`, { password })
  return normalizeApiKeys(response.data.apiKeys)
}

/** 打开编辑窗口时从不自动回读已保存的供应商密钥。 */
function open(row) {
  Object.assign(form, defaultForm(), row || {}, { apiKeys: '' })
  keyRows.value = ['']
  visible.value = true
}

/** 新增一行可填写的 API Key。 */
function addKey() {
  keyRows.value.push('')
}

/** 删除指定的 API Key 输入行。 */
function deleteKey(index) {
  keyRows.value.splice(index, 1)
}

/** 二次验证后查看供应商的明文 API Key，并在关闭窗口时清除内存副本。 */
async function viewKeys(row) {
  try {
    const password = await requestRevealPassword()
    keysContent.value = splitApiKeys(await loadKeys(row.id, password))
    keysVisible.value = true
  } catch (error) {
    if (error !== 'cancel' && error !== 'close') showHttpError(error)
  }
}

/** 弹出当前管理员密码输入框，避免仅凭被劫持会话导出长期凭据。 */
async function requestRevealPassword() {
  const { value } = await ElMessageBox.prompt(t('secretReveal.passwordPrompt'), t('secretReveal.title'), {
    inputType: 'password',
    inputAttributes: { autocomplete: 'current-password' },
    inputValidator: value => value?.trim() ? true : t('secretReveal.passwordRequired'),
    confirmButtonText: t('common.confirm'),
    cancelButtonText: t('common.cancel')
  })
  return value
}

/** 关闭查看窗口后清除浏览器内存中的供应商 API Key。 */
function clearKeys() {
  keysContent.value = []
}

/** 复制单个 API Key 并提示操作结果。 */
async function copyKey(key) {
  try {
    await copyApiKey(key)
    ElMessage.success(t('providers.keyCopied'))
  } catch {
    ElMessage.error(t('providers.copyFailed'))
  }
}

/** 保存供应商；编辑时留空密钥代表保留现有加密值。 */
async function save() {
  form.apiKeys = serializeApiKeyRows(keyRows.value)
  if (form.id) await http.put(`/models/providers/${form.id}`, form)
  else await http.post('/models/providers', form)
  visible.value = false
  await load()
  ElMessage.success(t('common.successSaved'))
}

/** 删除供应商前进行交互确认。 */
async function remove(row) {
  await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm'), { type: 'warning' })
  await http.delete(`/models/providers/${row.id}`)
  await load()
  ElMessage.success(t('common.successDeleted'))
}

/** 创建不含已保存 API Key 的供应商默认表单。 */
function defaultForm() {
  return { id: null, code: '', name: '', baseUrl: '', apiKeys: '', concurrencyLimit: 4, concurrencyLevel: 'PROVIDER', timeoutSeconds: 60, enabled: true }
}

onMounted(load)
</script>
