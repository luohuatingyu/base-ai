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
        <el-form-item :label="t('providers.baseUrl')"><el-input v-model="form.baseUrl" /></el-form-item>
        <el-form-item :label="t('providers.imageUrl')"><el-input v-model="form.imageUrl" :disabled="form.imageSameAsBase" /><el-checkbox v-model="form.imageSameAsBase">{{ t('providers.sameAsBase') }}</el-checkbox></el-form-item>
        <el-form-item :label="t('providers.videoUrl')"><el-input v-model="form.videoUrl" :disabled="form.videoSameAsImage" :placeholder="videoEndpoint" /><el-checkbox v-model="form.videoSameAsImage">{{ t('providers.sameAsImage') }}</el-checkbox></el-form-item>
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
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { copyApiKey, normalizeApiKeys, serializeApiKeyRows, splitApiKeys, toApiKeyRows } from '../utils/apiKeys'

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

/** 在当前管理员权限范围内读取并规范化指定供应商的 API Key。 */
async function loadKeys(id) {
  const response = await http.post(`/models/providers/${id}/api-keys`)
  return normalizeApiKeys(response.data.apiKeys)
}

/** 打开编辑窗口；管理员编辑已有供应商时回显密钥，其他情况保持空白。 */
async function open(row) {
  Object.assign(form, defaultForm(), row || {}, { apiKeys: '', imageSameAsBase: row?.imageSameAsBase ?? true, videoSameAsImage: row?.videoSameAsImage ?? true })
  keyRows.value = ['']
  if (row?.id && auth.isAdmin) {
    try {
      keyRows.value = toApiKeyRows(await loadKeys(row.id))
    } catch (error) {
      showHttpError(error)
    }
  }
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

/** 在管理员权限范围内查看供应商的明文 API Key，并在关闭窗口时清除内存副本。 */
async function viewKeys(row) {
  try {
    keysContent.value = splitApiKeys(await loadKeys(row.id))
    keysVisible.value = true
  } catch (error) {
    showHttpError(error)
  }
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
  if (!hasProviderEndpoint()) { ElMessage.error(t('providers.endpointRequired')); return }
  if (form.videoSameAsImage && !String(imageEndpoint.value || '').trim()) { ElMessage.error(t('providers.syncSourceRequired')); return }
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
  return { id: null, code: '', name: '', baseUrl: '', imageUrl: '', videoUrl: '', imageSameAsBase: true, videoSameAsImage: true, apiKeys: '', concurrencyLimit: 4, concurrencyLevel: 'PROVIDER', timeoutSeconds: 60, enabled: true }
}

/** 返回联动后的图片和视频地址。 */
const imageEndpoint = computed(() => form.imageSameAsBase ? form.baseUrl : form.imageUrl)
const videoEndpoint = computed(() => form.videoSameAsImage ? imageEndpoint.value : form.videoUrl)
/** 三类地址至少有一个有效输入。 */
function hasProviderEndpoint() { return [form.baseUrl, form.imageUrl, form.videoUrl].some(value => String(value || '').trim()) }

onMounted(load)
</script>
