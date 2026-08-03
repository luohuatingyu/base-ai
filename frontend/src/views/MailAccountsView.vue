<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('mailAccounts.title') }}</h2><p>{{ t('mailAccounts.description') }}</p></div>
      <el-button v-if="auth.hasPermission('mail:account:create')" type="primary" @click="open()">{{ t('mailAccounts.add') }}</el-button>
    </div>
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('mailAccounts.code')" min-width="140" />
      <el-table-column prop="name" :label="t('common.name')" min-width="160" />
      <el-table-column prop="host" :label="t('mailAccounts.host')" min-width="220" />
      <el-table-column prop="port" :label="t('mailAccounts.port')" width="90" />
      <el-table-column prop="username" :label="t('mailAccounts.username')" min-width="220" />
      <el-table-column prop="fromAddress" :label="t('mailAccounts.fromAddress')" min-width="220" />
      <el-table-column prop="tlsMode" :label="t('mailAccounts.tlsMode')" width="120" />
      <el-table-column :label="t('common.status')" width="100"><template #default="scope">{{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}</template></el-table-column>
      <el-table-column :label="t('common.operation')" width="180" fixed="right">
        <template #default="scope"><div class="table-actions">
          <el-button v-if="auth.hasPermission('mail:account:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
          <el-button v-if="auth.hasPermission('mail:account:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
        </div></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('mailAccounts.edit') : t('mailAccounts.add')" width="min(620px, 94vw)" @closed="clearPassword">
      <el-form :model="form" label-width="125px">
        <el-form-item :label="t('mailAccounts.code')"><el-input v-model="form.code" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('mailAccounts.host')"><el-input v-model="form.host" /></el-form-item>
        <el-form-item :label="t('mailAccounts.port')"><el-input-number v-model="form.port" :min="1" :max="65535" /></el-form-item>
        <el-form-item :label="t('mailAccounts.username')"><el-input v-model="form.username" /></el-form-item>
        <el-form-item :label="t('mailAccounts.fromAddress')"><el-input v-model="form.fromAddress" /></el-form-item>
        <el-form-item :label="t('mailAccounts.tlsMode')"><el-select v-model="form.tlsMode" class="full"><el-option v-for="mode in tlsModes" :key="mode" :label="mode" :value="mode" /></el-select></el-form-item>
        <el-form-item :label="t('mailAccounts.password')"><el-input v-model="form.password" type="password" show-password autocomplete="new-password" :placeholder="form.id ? t('mailAccounts.keepPassword') : ''" /></el-form-item>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const visible = ref(false)
const tlsModes = ['NONE', 'STARTTLS', 'SSL']
const form = reactive(defaultForm())

/** 查询全部 SMTP 邮箱账户，响应不包含密码。 */
async function load() { rows.value = (await http.get('/mail/accounts')).data || [] }
/** 读取指定邮箱账户的管理员可见明文密码。 */
async function loadPassword(id) { return (await http.get(`/mail/accounts/${id}/password`)).data.password }
/** 打开新增或编辑窗口，仅系统管理员编辑已有账户时回显密码。 */
async function open(row) {
  Object.assign(form, defaultForm(), row || {}, { password: row && auth.isAdmin ? await loadPassword(row.id) : '' })
  visible.value = true
}
/** 关闭编辑窗口后清除浏览器内存中的邮箱密码。 */
function clearPassword() { form.password = '' }
/** 保存邮箱账户，新建要求密码，编辑留空保留已有密码。 */
async function save() {
  if (!form.code.trim() || !form.name.trim() || !form.host.trim() || !form.username.trim() || !form.fromAddress.trim() || (!form.id && !form.password)) {
    ElMessage.warning(t('mailAccounts.required')); return
  }
  try {
    if (form.id) await http.put(`/mail/accounts/${form.id}`, form)
    else await http.post('/mail/accounts', form)
    visible.value = false; await load(); ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
}
/** 删除未被邮件路由使用的邮箱账户。 */
async function remove(row) {
  try {
    await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm'))
    await http.delete(`/mail/accounts/${row.id}`); await load(); ElMessage.success(t('common.successDeleted'))
  } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) }
}
/** 创建不包含任何已保存密码的空表单。 */
function defaultForm() { return { id: null, code: '', name: '', host: '', port: 587, username: '', fromAddress: '', tlsMode: 'STARTTLS', password: '', enabled: true } }
onMounted(load)
</script>
