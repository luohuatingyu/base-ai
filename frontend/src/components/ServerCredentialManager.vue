<template>
  <el-dialog :model-value="modelValue" :title="t('serverCredentials.title')" width="min(1050px, 95vw)" @update:model-value="$emit('update:modelValue', $event)">
    <p class="credential-description">{{ t('serverCredentials.description') }}</p>
    <div class="credential-toolbar">
      <div class="credential-summary"><strong>{{ rows.length }}</strong><span>条凭据</span></div>
      <div class="credential-actions">
      <el-button @click="load">{{ t('common.refresh') }}</el-button>
      <el-button v-if="auth.hasPermission('operations:server:create')" type="primary" @click="open()">{{ t('serverCredentials.add') }}</el-button>
      </div>
    </div>
    <el-table :data="rows" v-loading="loading" class="credential-table" empty-text="暂无凭据">
      <el-table-column prop="label" :label="t('serverCredentials.label')" min-width="160" />
      <el-table-column :label="t('serverCredentials.type')" width="120"><template #default="{ row }"><el-tag :type="row.type === 'KEY' ? 'warning' : 'success'" effect="light">{{ row.type === 'KEY' ? '秘钥' : '账号密码' }}</el-tag></template></el-table-column>
      <el-table-column prop="username" :label="t('servers.username')" min-width="120" />
      <el-table-column :label="t('serverCredentials.materials')" min-width="170">
        <template #default="{ row }">
          <el-tag>{{ row.type === 'KEY' ? t('servers.privateKey') : t('servers.password') }}</el-tag>
          <el-tag v-if="row.publicKey">{{ t('serverCredentials.publicKey') }}</el-tag>
          <el-tag v-if="row.certificate">{{ t('serverCredentials.certificate') }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column :label="t('common.status')" width="100"><template #default="{ row }">{{ t(row.enabled ? 'common.enabled' : 'common.disabled') }}</template></el-table-column>
      <el-table-column :label="t('common.operation')" min-width="210">
        <template #default="{ row }">
          <el-button v-if="auth.hasPermission('operations:server:update')" link @click="open(row)">{{ t('common.edit') }}</el-button>
          <el-button v-if="isAdmin" link @click="reveal(row)">{{ t('serverCredentials.reveal') }}</el-button>
          <el-button v-if="auth.hasPermission('operations:server:delete')" link type="danger" @click="remove(row)">{{ t('common.delete') }}</el-button>
        </template>
      </el-table-column>
    </el-table>
    <el-dialog v-model="editing" append-to-body :title="t(form.id ? 'serverCredentials.edit' : 'serverCredentials.add')" width="min(760px, 94vw)" @closed="clearForm">
      <el-form label-position="top" @submit.prevent="save" class="credential-form">
        <div class="form-section"><div class="section-title">基本信息</div><div class="section-grid"><el-form-item :label="t('serverCredentials.label')" required><el-input v-model="form.label" maxlength="120" placeholder="例如：生产环境跳板机" /></el-form-item><el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" inline-prompt active-text="启用" inactive-text="停用" /></el-form-item></div></div>
        <div class="form-section"><div class="section-title">认证方式</div><div class="type-cards"><button type="button" :class="['type-card', { selected: form.type === 'PASSWORD' }]" @click="form.type = 'PASSWORD'"><strong>账号密码</strong><span>使用用户名和登录密码认证</span></button><button type="button" :class="['type-card', { selected: form.type === 'KEY' }]" @click="form.type = 'KEY'"><strong>秘钥</strong><span>使用 SSH 私钥进行安全认证</span></button></div></div>
        <el-form-item :label="t('servers.username')"><el-input v-model="form.username" maxlength="64" autocomplete="off" /></el-form-item>
        <el-form-item v-if="form.type === 'PASSWORD'" :label="t('servers.password')"><el-input v-model="form.password" type="password" show-password maxlength="1024" autocomplete="new-password" :placeholder="keepHint" /></el-form-item>
        <el-form-item v-if="form.type === 'KEY'" :label="t('servers.privateKey')">
          <input type="file" :aria-label="t('servers.selectPrivateKeyFile')" @change="readKey" />
          <el-input v-model="form.privateKey" type="textarea" :rows="4" maxlength="32768" autocomplete="off" :placeholder="keepHint" />
        </el-form-item>
        <el-form-item v-if="form.type === 'KEY'" :label="t('servers.passphrase')"><el-input v-model="form.passphrase" type="password" show-password maxlength="1024" autocomplete="new-password" :placeholder="keepHint" /></el-form-item>
        <el-alert v-if="form.id" :title="t('serverCredentials.rotationHint')" type="warning" :closable="false" />
      </el-form>
      <template #footer><el-button @click="editing = false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="secretVisible" append-to-body :title="t('serverCredentials.reveal')" width="min(760px, 94vw)" @closed="secrets = null">
      <el-alert :title="t('serverCredentials.secretHint')" type="warning" :closable="false" />
      <el-form v-if="secrets" label-position="top">
        <el-form-item :label="t('servers.privateKey')"><el-input :model-value="secrets.privateKey" type="textarea" :rows="6" readonly /></el-form-item>
        <el-form-item :label="t('servers.password')"><el-input :model-value="secrets.password" type="password" show-password readonly /></el-form-item>
        <el-form-item :label="t('servers.passphrase')"><el-input :model-value="secrets.passphrase" type="password" show-password readonly /></el-form-item>
      </el-form>
    </el-dialog>
  </el-dialog>
</template>

<script setup>
import { computed, reactive, ref, watch } from 'vue'
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
function clearForm() { Object.assign(form, emptyForm()) }

/** 打开表单，列表数据不包含任何秘密。 */
function open(row) { clearForm(); Object.assign(form, row || {}); editing.value = true }

/** 拉取脱敏列表，失败不保留过期的可选凭据。 */
async function load() {
  loading.value = true
  try { rows.value = (await http.get('/server-credentials')).data || [] }
  catch (error) { rows.value = []; showHttpError(error) }
  finally { loading.value = false }
}

/** 读取本地文件并遵循后端材料大小限制。 */
async function readKey(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  try {
    const content = await readPrivateKeyFile(file)
    if (new TextEncoder().encode(content).length > 32768) throw new Error('size')
    form.privateKey = content
  } catch { ElMessage.warning(t('serverCredentials.fileInvalid')) }
}

/** 保存完成后通知服务器刷新下拉选项。 */
async function save() {
  if (saving.value) return
  if (!form.label.trim() || (form.password && !form.username.trim())) return ElMessage.warning(t('serverCredentials.required'))
  saving.value = true
  try {
    const body = { ...form }
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
.credential-description { margin: 0 0 18px; color: var(--el-text-color-secondary); line-height: 1.6; }
.credential-toolbar { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 16px; padding: 14px 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-lighter); }
.credential-summary { display: flex; align-items: baseline; gap: 6px; color: var(--el-text-color-secondary); }
.credential-summary strong { color: var(--el-text-color-primary); font-size: 24px; }
.credential-actions { display: flex; gap: 10px; }
.credential-actions .el-button + .el-button { margin-left: 0; }
.credential-table { border-radius: 10px; overflow: hidden; }
.credential-table :deep(.el-table__cell) { padding: 13px 0; }
.credential-table :deep(.el-tag) { margin: 2px 4px 2px 0; }
.credential-form { padding-top: 4px; }
.form-section { margin-bottom: 22px; padding: 18px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: var(--el-fill-color-extra-light); }
.section-title { margin-bottom: 14px; color: var(--el-text-color-primary); font-weight: 650; font-size: 15px; }
.section-grid { display: grid; grid-template-columns: 1fr 140px; gap: 18px; }
.section-grid .el-form-item { margin-bottom: 0; }
.type-cards { display: grid; grid-template-columns: 1fr 1fr; gap: 12px; }
.type-card { display: flex; flex-direction: column; align-items: flex-start; gap: 6px; padding: 15px; border: 1px solid var(--el-border-color); border-radius: 10px; background: var(--el-bg-color); color: var(--el-text-color-secondary); text-align: left; cursor: pointer; transition: .2s; }
.type-card strong { color: var(--el-text-color-primary); font-size: 15px; }
.type-card:hover, .type-card.selected { border-color: var(--el-color-primary); background: var(--el-color-primary-light-9); box-shadow: 0 0 0 1px var(--el-color-primary); }
@media (max-width: 640px) { .credential-toolbar { align-items: stretch; flex-direction: column; } .credential-actions { justify-content: flex-end; } }
@media (max-width: 560px) { .section-grid, .type-cards { grid-template-columns: 1fr; } }
</style>
