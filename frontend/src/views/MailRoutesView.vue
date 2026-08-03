<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('mailRoutes.title') }}</h2><p>{{ t('mailRoutes.description') }}</p></div>
      <el-button v-if="auth.hasPermission('mail:route:create')" type="primary" @click="open()">{{ t('mailRoutes.add') }}</el-button>
    </div>
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="businessCode" :label="t('mailRoutes.businessCode')" min-width="210" />
      <el-table-column prop="name" :label="t('common.name')" min-width="160" />
      <el-table-column prop="accountName" :label="t('mailRoutes.account')" min-width="180" />
      <el-table-column :label="t('mailRoutes.toAddresses')" min-width="220"><template #default="scope">{{ scope.row.toAddresses.join(', ') }}</template></el-table-column>
      <el-table-column :label="t('common.status')" width="110"><template #default="scope">{{ !scope.row.configured ? t('mailRoutes.pendingConfiguration') : (scope.row.enabled ? t('common.enabled') : t('common.disabled')) }}</template></el-table-column>
      <el-table-column :label="t('common.operation')" width="180" fixed="right">
        <template #default="scope"><div class="table-actions">
          <el-button v-if="auth.hasPermission('mail:route:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
          <el-button v-if="auth.hasPermission('mail:route:delete') && scope.row.businessCode !== 'DEFAULT'" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
        </div></template>
      </el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('mailRoutes.edit') : t('mailRoutes.add')" width="min(640px, 94vw)">
      <el-form :model="form" label-width="125px">
        <el-form-item :label="t('mailRoutes.businessCode')"><el-input v-model="form.businessCode" :disabled="form.businessCode === 'DEFAULT'" :placeholder="t('mailRoutes.businessPlaceholder')" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" :disabled="form.businessCode === 'DEFAULT'" /></el-form-item>
        <el-form-item :label="t('mailRoutes.account')"><el-select v-model="form.accountId" class="full"><el-option v-for="item in accounts" :key="item.id" :label="`${item.name} (${item.code})`" :value="item.id" /></el-select></el-form-item>
        <el-form-item :label="t('mailRoutes.toAddresses')"><div class="mail-address-editor"><div v-for="(_, index) in toRows" :key="`to-${index}`" class="api-key-editor-row"><el-input v-model="toRows[index]" :placeholder="t('mailRoutes.addressPlaceholder')" /><el-button link type="danger" @click="deleteAddress(toRows, index)">{{ t('mailRoutes.deleteAddress') }}</el-button></div><el-button plain type="primary" @click="addAddress(toRows)">+ {{ t('mailRoutes.addRecipient') }}</el-button></div></el-form-item>
        <el-form-item :label="t('mailRoutes.ccAddresses')"><div class="mail-address-editor"><div v-for="(_, index) in ccRows" :key="`cc-${index}`" class="api-key-editor-row"><el-input v-model="ccRows[index]" :placeholder="t('mailRoutes.addressPlaceholder')" /><el-button link type="danger" @click="deleteAddress(ccRows, index)">{{ t('mailRoutes.deleteAddress') }}</el-button></div><el-button plain type="primary" @click="addAddress(ccRows)">+ {{ t('mailRoutes.addCc') }}</el-button></div></el-form-item>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" :disabled="form.businessCode === 'DEFAULT'" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { normalizeMailAddressRows, toMailAddressRows } from '../utils/mailAddresses'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const accounts = ref([])
const visible = ref(false)
const form = reactive(defaultForm())
const toRows = ref([''])
const ccRows = ref([''])

/** 并行加载邮件路由和启用邮箱选项。 */
async function load() {
  const [routes, options] = await Promise.all([http.get('/mail/routes'), http.get('/mail/account-options')])
  rows.value = routes.data || []; accounts.value = options.data || []
}
/** 打开新增或编辑窗口并转换收件人列表。 */
function open(row) {
  Object.assign(form, defaultForm(), row || {})
  if (form.businessCode === 'DEFAULT') form.enabled = true
  toRows.value = toMailAddressRows(row?.toAddresses)
  ccRows.value = toMailAddressRows(row?.ccAddresses)
  visible.value = true
}
/** 新增一个独立邮箱输入行。 */
function addAddress(rows) { rows.push('') }
/** 删除指定邮箱输入行，并保留一条可继续填写的空行。 */
function deleteAddress(rows, index) { rows.splice(index, 1); if (!rows.length) rows.push('') }
/** 保存具体业务或 DEFAULT 通用邮件路由。 */
async function save() {
  const payload = { ...form, toAddresses: normalizeMailAddressRows(toRows.value), ccAddresses: normalizeMailAddressRows(ccRows.value) }
  if (payload.businessCode === 'DEFAULT') payload.enabled = true
  if (!payload.businessCode.trim() || !payload.name.trim() || !payload.accountId || !payload.toAddresses.length) {
    ElMessage.warning(t('mailRoutes.required')); return
  }
  if (form.id) await http.put(`/mail/routes/${form.id}`, payload)
  else await http.post('/mail/routes', payload)
  visible.value = false; await load(); ElMessage.success(t('common.successSaved'))
}
/** 删除非 DEFAULT 邮件路由。 */
async function remove(row) {
  await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm'))
  await http.delete(`/mail/routes/${row.id}`); await load(); ElMessage.success(t('common.successDeleted'))
}
/** 创建空邮件路由表单。 */
function defaultForm() { return { id: null, businessCode: '', name: '', accountId: null, enabled: true } }
onMounted(load)
</script>

<style scoped>
.mail-address-editor { width: 100%; }
</style>
