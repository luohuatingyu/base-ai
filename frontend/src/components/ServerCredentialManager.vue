<template>
  <el-dialog :model-value="modelValue" :title="t('serverCredentials.title')" width="min(1050px, 95vw)" @update:model-value="$emit('update:modelValue', $event)">
    <div class="credential-hero">
      <el-icon class="credential-hero-icon"><Key /></el-icon>
      <div><h3>{{ t('serverCredentials.title') }}</h3><p class="credential-description">{{ t('serverCredentials.description') }}</p></div>
    </div>
    <div class="credential-toolbar">
      <div class="credential-summary"><strong>{{ rows.length }}</strong><span>{{ t('serverCredentials.total') }}</span></div>
      <div class="credential-actions">
      <el-button @click="load">{{ t('common.refresh') }}</el-button>
      <el-button v-if="auth.hasPermission('operations:server:create')" type="primary" @click="open()">{{ t('serverCredentials.add') }}</el-button>
      </div>
    </div>
    <div class="credential-filters">
      <el-input v-model="query" clearable :placeholder="t('serverCredentials.search')" :aria-label="t('serverCredentials.search')" />
      <el-select v-model="typeFilter" :aria-label="t('serverCredentials.type')">
        <el-option :label="t('serverCredentials.allTypes')" value="" />
        <el-option v-for="type in ['PASSWORD', 'KEY', 'RSA']" :key="type" :label="typeLabel(type)" :value="type" />
      </el-select>
    </div>
    <el-table :data="filteredRows" v-loading="loading" class="credential-table" :empty-text="t('serverCredentials.empty')" max-height="440">
      <el-table-column prop="label" :label="t('serverCredentials.label')" min-width="160" />
      <el-table-column :label="t('serverCredentials.type')" width="150"><template #default="{ row }"><el-tag :type="row.type === 'KEY' ? 'primary' : row.type === 'PASSWORD' ? 'success' : 'warning'" effect="light">{{ typeLabel(row.type) }}</el-tag></template></el-table-column>
      <el-table-column :label="t('servers.username')" min-width="120"><template #default="{ row }">{{ row.type === 'KEY' ? '—' : row.username || '—' }}</template></el-table-column>
      <el-table-column :label="t('serverCredentials.materials')" min-width="170">
        <template #default="{ row }">
          <el-tag v-if="row.hasPrivateKey">{{ t('servers.privateKey') }}</el-tag>
          <el-tag v-if="row.hasPassword">{{ t('servers.password') }}</el-tag>
          <el-tag v-if="row.publicKey">{{ t('serverCredentials.publicKey') }}</el-tag>
          <el-tag v-if="row.certificate">{{ t('serverCredentials.certificate') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="100"><template #default="{ row }"><el-tag :type="row.enabled ? 'success' : 'info'" effect="plain">{{ t(row.enabled ? 'common.enabled' : 'common.disabled') }}</el-tag></template></el-table-column>
      <el-table-column :label="t('common.operation')" min-width="210">
        <template #default="{ row }">
          <el-button v-if="auth.hasPermission('operations:server:update')" link @click="open(row)">{{ t('common.edit') }}</el-button>
          <el-button v-if="isAdmin" link @click="reveal(row)">{{ t('serverCredentials.reveal') }}</el-button>
          <el-button v-if="auth.hasPermission('operations:server:delete')" link type="danger" @click="remove(row)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="editing" append-to-body :title="t(form.id ? 'serverCredentials.edit' : 'serverCredentials.add')" width="min(920px, 94vw)" @close="resetMaterialState" @closed="clearForm">
      <el-form label-position="top" @submit.prevent="save" class="credential-form">
        <div class="form-section"><div class="section-title">{{ t('servers.basicSection') }}</div><div class="section-grid"><el-form-item :label="t('serverCredentials.label')" required><el-input v-model="form.label" maxlength="120" :placeholder="t('serverCredentials.labelHint')" /></el-form-item><el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" :aria-label="t('common.status')" inline-prompt :active-text="t('common.enabled')" :inactive-text="t('common.disabled')" /></el-form-item></div></div>
        <div class="form-section">
          <div class="section-title">{{ t('serverCredentials.type') }}</div>
          <el-alert v-if="originalType === 'RSA'" :title="t('serverCredentials.legacyHint')" type="warning" show-icon :closable="false" />
          <div class="type-cards">
            <button v-for="type in ['PASSWORD', 'KEY']" :key="type" type="button" :aria-pressed="form.type === type" :class="['type-card', { selected: form.type === type }]" @click="changeType(type)">
              <el-icon><Key v-if="type === 'KEY'" /><Lock v-else /></el-icon>
              <strong>{{ typeLabel(type) }}</strong><span>{{ t(type === 'KEY' ? 'serverCredentials.keyHint' : 'serverCredentials.passwordHint') }}</span>
            </button>
          </div>
          <p class="field-help">{{ t('serverCredentials.switchHint') }}</p>
        </div>
        <div class="form-section">
        <div class="section-title">{{ t('serverCredentials.details') }}</div>
        <el-form-item v-if="form.type === 'PASSWORD'" :label="t('servers.username')" required><el-input v-model="form.username" maxlength="64" autocomplete="off" /></el-form-item>
        <el-form-item v-if="form.type === 'PASSWORD'" :label="t('servers.password')" required><el-input v-model="form.password" type="password" show-password maxlength="1024" autocomplete="new-password" :placeholder="keepHint" /></el-form-item>
        <div v-if="form.type === 'KEY'" class="key-upload" @dragover.prevent @drop.prevent="readKey">
          <el-icon><UploadFilled /></el-icon>
          <strong>{{ t('serverCredentials.importHint') }}</strong>
          <span>{{ t('serverCredentials.localFileHint') }}</span>
          <label class="file-picker">{{ t('servers.selectPrivateKeyFile') }}<input type="file" :aria-label="t('servers.selectPrivateKeyFile')" @change="readKey" /></label>
          <span v-if="readingFile" role="status">{{ t('serverCredentials.reading') }}</span>
          <span v-else-if="fileName" role="status">{{ fileName }} · {{ fileBytes }} B · {{ t('serverCredentials.imported') }}</span>
          <span v-if="fileError" role="alert" class="material-error">{{ fileError }}</span>
        </div>
        <template v-if="form.type === 'KEY'">
          <el-form-item v-for="field in materialFields" :key="field.name" class="material-field" :label="t(field.label)" :required="field.name === 'privateKey'">
            <div class="material-editor">
              <div class="material-toolbar">
                <span :class="{ 'material-error': byteLength(form[field.name]) > 32768 }">{{ byteLength(form[field.name]) }} / 32768 B</span>
                <el-button link type="primary" @click="expandedField = field.name">{{ t('serverCredentials.expand') }}</el-button>
              </div>
              <el-input v-model="form[field.name]" type="textarea" :autosize="{ minRows: field.name === 'privateKey' ? 8 : 4, maxRows: 16 }" resize="vertical" wrap="soft" spellcheck="false" autocomplete="off" :aria-label="t(field.label)" :placeholder="field.name === 'privateKey' ? keepHint : ''" @input="invalidateFileRead(field.name)" />
              <p v-if="byteLength(form[field.name]) > 32768" role="alert" class="material-error">{{ t('serverCredentials.tooLarge') }}</p>
            </div>
          </el-form-item>
        </template>
        <el-form-item v-if="form.type === 'KEY'" :label="t('servers.passphrase')"><el-input v-model="form.passphrase" type="password" show-password maxlength="1024" autocomplete="new-password" :placeholder="keepHint" /></el-form-item>
        <template v-if="form.type === 'KEY'">
          <p class="field-help">{{ t('serverCredentials.certificateHint') }}</p>
        </template>
        </div>
        <el-alert v-if="form.id" :title="t('serverCredentials.rotationHint')" type="warning" :closable="false" />
      </el-form>
      <template #footer><el-button @click="editing = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
    <el-dialog :model-value="!!expandedField" append-to-body fullscreen :title="t(materialFields.find(field => field.name === expandedField)?.label || 'serverCredentials.details')" @update:model-value="expandedField = ''">
      <div v-if="expandedField" class="expanded-material">
        <div class="material-toolbar"><span :class="{ 'material-error': byteLength(form[expandedField]) > 32768 }">{{ byteLength(form[expandedField]) }} / 32768 B</span><span>{{ t('serverCredentials.originalText') }}</span></div>
        <el-input v-model="form[expandedField]" type="textarea" wrap="soft" spellcheck="false" autocomplete="off" :aria-label="t(materialFields.find(field => field.name === expandedField).label)" @input="invalidateFileRead(expandedField)" />
        <p v-if="byteLength(form[expandedField]) > 32768" role="alert" class="material-error">{{ t('serverCredentials.tooLarge') }}</p>
      </div>
      <template #footer><el-button type="primary" @click="expandedField = ''">{{ t('serverCredentials.collapse') }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="secretVisible" append-to-body :title="t('serverCredentials.reveal')" width="min(760px, 94vw)" @closed="secrets = null">
      <el-alert :title="t('serverCredentials.secretHint')" type="warning" :closable="false" />
      <el-form v-if="secrets" label-position="top">
        <el-form-item v-if="secrets.privateKey" :label="t('servers.privateKey')"><el-input :model-value="secrets.privateKey" type="textarea" :rows="6" readonly /></el-form-item>
        <el-form-item v-if="secrets.password" :label="t('servers.password')"><el-input :model-value="secrets.password" type="password" show-password readonly /></el-form-item>
        <el-form-item v-if="secrets.passphrase" :label="t('servers.passphrase')"><el-input :model-value="secrets.passphrase" type="password" show-password readonly /></el-form-item>
      </el-form>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
import { Key, Lock, UploadFilled } from '@element-plus/icons-vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { readPrivateKeyFile } from '../utils/serverCredentials'

const props = defineProps({ modelValue: Boolean })
const emit = defineEmits(['update:modelValue', 'changed'])
const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const query = ref('')
const typeFilter = ref('')
const originalType = ref('')
const expandedField = ref('')
const fileName = ref('')
const fileBytes = ref(0)
const fileError = ref('')
const readingFile = ref(false)
let fileReadVersion = 0
const materialFields = [
  { name: 'privateKey', label: 'servers.privateKey' },
  { name: 'publicKey', label: 'serverCredentials.publicKey' },
  { name: 'certificate', label: 'serverCredentials.certificate' },
]
const filteredRows = computed(() => rows.value.filter(row =>
  (!typeFilter.value || row.type === typeFilter.value)
  && [row.label, row.username].some(value => (value || '').toLowerCase().includes(query.value.trim().toLowerCase()))))
const loading = ref(false)
const editing = ref(false)
const saving = ref(false)
const secretVisible = ref(false)
const secrets = ref(null)
const form = reactive(emptyForm())
const isAdmin = computed(() => auth.user?.roles?.includes('ADMIN'))
const keepHint = computed(() => form.id ? t('servers.savedCredentialHint') : '')

/** 创建只在内存中保存敏感输入的凭据表单。 */
function emptyForm() {
  return { id: null, label: '', type: 'PASSWORD', username: '', publicKey: '', privateKey: '', certificate: '', password: '', passphrase: '', enabled: true }
}

/** 关闭编辑弹窗后清除敏感输入。 */
function clearForm() { resetMaterialState(); Object.assign(form, emptyForm(), { hasPrivateKey: false, hasPassword: false }); originalType.value = '' }

/** 按 UTF-8 字节数提示上限，不截断或重新格式化原文。 */
function byteLength(value) {
  return new TextEncoder().encode(value || '').length
}

/** 关闭或切换时使未完成的文件读取失效并清理展示状态。 */
function resetMaterialState() {
  fileReadVersion++
  expandedField.value = ''
  fileName.value = ''
  fileBytes.value = 0
  fileError.value = ''
  readingFile.value = false
}

/** 手动编辑优先于未完成的文件读取，避免覆盖用户的新输入。 */
function invalidateFileRead(field) {
  if (field !== 'privateKey') return
  fileReadVersion++
  readingFile.value = false
  fileName.value = ''
  fileError.value = ''
}

/** 打开表单，列表数据不包含任何秘密。 */
function open(row) { clearForm(); Object.assign(form, row || {}); if (form.type === 'KEY') form.username = ''; originalType.value = row?.type || ''; editing.value = true }

/** 展示明确的认证类型，历史材料不会被误标为账号密码。 */
function typeLabel(type) {
  return t(type === 'KEY' ? 'serverCredentials.keyType' : type === 'PASSWORD' ? 'serverCredentials.passwordType' : 'serverCredentials.legacyType')
}

/** 切换类型时丢弃不适用的临时字段，旧密文由后端在引用校验后处理。 */
function changeType(type) {
  resetMaterialState()
  form.type = type
  if (type === 'KEY') { form.password = ''; form.username = '' }
  else { form.privateKey = ''; form.publicKey = ''; form.certificate = ''; form.passphrase = '' }
}

/** 拉取脱敏列表，失败不保留过期的可选凭据。 */
async function load() {
  loading.value = true
  try { rows.value = (await http.get('/server-credentials')).data || [] }
  catch (error) { rows.value = []; showHttpError(error) }
  finally { loading.value = false }
}

/** 读取本地文件并遵循后端材料大小限制。 */
async function readKey(event) {
  const file = event.dataTransfer?.files?.[0] || event.target.files?.[0]
  if (!event.dataTransfer) event.target.value = ''
  if (!file) return
  if (!editing.value || form.type !== 'KEY') return
  const version = ++fileReadVersion
  readingFile.value = true
  fileError.value = ''
  try {
    if (file.size > 32768) throw new Error('PRIVATE_KEY_FILE_TOO_LARGE')
    const content = await readPrivateKeyFile(file)
    if (byteLength(content) > 32768) throw new Error('PRIVATE_KEY_FILE_TOO_LARGE')
    if (version !== fileReadVersion) return
    form.privateKey = content
    fileName.value = file.name
    fileBytes.value = byteLength(content)
  } catch (error) {
    if (version !== fileReadVersion) return
    fileError.value = t(error.message === 'PRIVATE_KEY_FILE_EMPTY' ? 'serverCredentials.emptyFile'
      : error.message === 'PRIVATE_KEY_FILE_TOO_LARGE' ? 'serverCredentials.tooLarge' : 'serverCredentials.readFailed')
  } finally {
    if (version === fileReadVersion) readingFile.value = false
  }
}

/** 保存完成后通知服务器刷新下拉选项。 */
async function save() {
  if (saving.value) return
  if (readingFile.value) return
  if (form.type === 'KEY' && ['privateKey', 'publicKey', 'certificate'].some(field => byteLength(form[field]) > 32768)) return ElMessage.warning(t('serverCredentials.tooLarge'))
  if (!form.label.trim()) return ElMessage.warning(t('serverCredentials.required'))
  if (!['KEY', 'PASSWORD'].includes(form.type)
    || (form.type === 'PASSWORD' && (!form.username.trim() || ((!form.password.trim() || form.password === '******') && !form.hasPassword)))
    || (form.type === 'KEY' && ((!form.privateKey.trim() || form.privateKey === '******') && !form.hasPrivateKey))) return ElMessage.warning(t('serverCredentials.required'))
  saving.value = true
  try {
    const body = { ...form, username: form.type === 'PASSWORD' ? form.username : '', password: form.type === 'PASSWORD' ? form.password : '',
      privateKey: form.type === 'KEY' ? form.privateKey : '', publicKey: form.type === 'KEY' ? form.publicKey : '',
      certificate: form.type === 'KEY' ? form.certificate : '', passphrase: form.type === 'KEY' ? form.passphrase : '' }
    if (form.id) await http.put(`/server-credentials/${form.id}`, body)
    else await http.post('/server-credentials', body)
    editing.value = false
    clearForm()
    await load()
    emit('changed')
  } catch (error) { showHttpError(error) }
  finally { saving.value = false }
}

/** 先确认删除，再由后端检查所有引用关系。 */
async function remove(row) {
  try { await ElMessageBox.confirm(t('serverCredentials.deleteConfirm', { label: row.label }), t('common.delete'), { type: 'warning' }) }
  catch { return }
  try { await http.delete(`/server-credentials/${row.id}`); await load(); emit('changed') }
  catch (error) { showHttpError(error) }
}

/** 显式请求管理员明文视图，不写入列表或浏览器存储。 */
async function reveal(row) {
  try { secrets.value = (await http.post(`/server-credentials/${row.id}/secret`)).data; secretVisible.value = true }
  catch (error) { showHttpError(error) }
}

/** 管理入口打开时刷新，关闭时清理子弹窗与秘密。 */
watch(() => props.modelValue, value => {
  if (value) load()
  else { editing.value = false; secretVisible.value = false; secrets.value = null; clearForm() }
})
</script>

<style scoped>
.credential-hero { display: flex; gap: 18px; align-items: center; padding: 24px; margin-bottom: 20px; border-radius: 14px; background: var(--el-color-primary-light-9); }
.credential-hero h3 { margin: 0 0 8px; font-size: 22px; }
.credential-hero .credential-description { margin: 0; }
.credential-hero-icon { font-size: 36px; color: var(--el-color-primary); }
.credential-filters { display: grid; grid-template-columns: 1fr 190px; gap: 12px; margin-bottom: 18px; }
.field-help { color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.6; }
.key-upload { display: flex; flex-direction: column; align-items: center; gap: 10px; padding: 22px; margin-bottom: 20px; border: 1px dashed var(--el-color-primary); border-radius: 10px; background: var(--el-color-primary-light-9); overflow-wrap: anywhere; text-align: center; }
.key-upload > .el-icon { font-size: 30px; color: var(--el-color-primary); }
.file-picker { position: relative; padding: 8px 16px; border-radius: 6px; background: var(--el-color-primary); color: white; }
.file-picker input { position: absolute; inset: 0; opacity: 0; width: 100%; cursor: pointer; }
.file-picker:focus-within { outline: 2px solid var(--el-color-primary); outline-offset: 3px; }
.material-editor { width: 100%; min-width: 0; }
.material-field :deep(.el-form-item__content) { display: block; min-width: 0; }
.material-toolbar { display: flex; flex-wrap: wrap; justify-content: space-between; gap: 10px; padding: 8px 0; color: var(--el-text-color-secondary); font-size: 12px; }
.material-editor :deep(textarea), .expanded-material :deep(textarea) { font-family: ui-monospace, SFMono-Regular, Consolas, monospace; line-height: 1.65; white-space: pre-wrap; overflow-wrap: anywhere; word-break: break-all; }
.material-error { color: var(--el-color-danger); }
.expanded-material { width: 100%; min-width: 0; }
.expanded-material :deep(textarea) { height: calc(100dvh - 225px); min-height: 160px; resize: none; overflow: auto; }
.type-card:focus-visible { outline: 3px solid var(--el-color-primary); outline-offset: 3px; }
.credential-description { margin: 0 0 18px; color: var(--el-text-color-secondary); line-height: 1.6; }
.credential-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; padding: 14px 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-lighter); }
.credential-summary { display: flex; align-items: baseline; gap: 6px; color: var(--el-text-color-secondary); }
.credential-summary strong { color: var(--el-text-color-primary); font-size: 24px; }
.credential-actions { display: flex; gap: 10px; }
.credential-actions .el-button + .el-button { margin-left: 0; }
.credential-table { border-radius: 10px; overflow: hidden; }
.credential-table :deep(.el-table__cell) { padding: 13px 0; }
.credential-table :deep(.el-tag) { margin: 2px 4px 2px 0; }
.credential-form { padding: 4px 6px 0; max-height: 65vh; overflow-y: auto; }
.form-section { margin-bottom: 20px; padding: 16px 0; border-bottom: 1px solid var(--el-border-color-lighter); }
.section-title { margin-bottom: 14px; color: var(--el-text-color-primary); font-weight: 650; font-size: 15px; }
.section-grid { display: grid; grid-template-columns: 1fr 140px; gap: 18px; }
.section-grid .el-form-item { margin-bottom: 0; }
.type-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.type-card { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 15px; border: 1px solid var(--el-border-color); border-radius: 10px; background: var(--el-bg-color); color: var(--el-text-color-secondary); text-align: left; cursor: pointer; transition: .2s; }
.type-card strong { color: var(--el-text-color-primary); font-size: 15px; }
.type-card:hover, .type-card.selected { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); box-shadow: 0 0 0 1px var(--el-color-primary); }
@media (max-width: 640px) { .credential-toolbar { align-items: stretch; flex-direction: column; } .credential-actions { justify-content: flex-end; } }
@media (max-width: 560px) { .section-grid, .type-cards, .credential-filters { grid-template-columns: 1fr; } }
</style>
