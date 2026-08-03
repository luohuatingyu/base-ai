<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('settings.title') }}</h2><p>{{ t('settings.description') }}</p></div>
      <el-button v-if="auth.hasPermission('system:setting:create')" type="primary" @click="open()">{{ t('settings.add') }}</el-button>
    </div>
    <div class="filter-row">
      <el-input v-model="query.configKey" clearable :placeholder="t('settings.keySearchPlaceholder')" @keyup.enter="search" />
      <el-button type="primary" @click="search">{{ t('common.query') }}</el-button>
      <el-button @click="resetSearch">{{ t('common.reset') }}</el-button>
    </div>
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="groupCode" :label="t('settings.group')" min-width="160" />
      <el-table-column :label="t('settings.key')" min-width="220"><template #default="scope">{{ shortKey(scope.row) }}</template></el-table-column>
      <el-table-column prop="name" :label="t('common.name')" min-width="180" />
      <el-table-column prop="configValue" :label="t('settings.value')" min-width="260" />
      <el-table-column :label="t('common.operation')" width="180" fixed="right">
        <template #default="scope">
          <div class="table-actions">
            <el-button link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
            <el-button v-if="!scope.row.systemManaged" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <div class="pagination-wrap"><el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="total, prev, pager, next" @current-change="load" /></div>
    <el-dialog v-model="visible" :title="form.id ? t('settings.edit') : t('settings.add')">
      <el-form label-width="90px">
        <el-form-item :label="t('settings.group')"><el-input v-model="form.groupCode" :disabled="form.systemManaged" /></el-form-item>
        <el-form-item :label="t('settings.key')"><el-input v-model="form.displayConfigKey" :disabled="form.systemManaged" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" :disabled="form.systemManaged" /></el-form-item>
        <el-form-item :label="t('settings.value')"><el-input v-model="form.configValue" :placeholder="form.sensitive && form.id ? t('settings.keepValue') : ''" /></el-form-item>
        <el-form-item :label="t('settings.sensitive')"><el-switch v-model="form.sensitive" :disabled="form.systemManaged" /></el-form-item>
        <el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled" :disabled="form.systemManaged" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible = false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const total = ref(0)
const query = reactive({ page: 1, size: 10, configKey: '' })
const visible = ref(false)
const form = reactive({ id: null, groupCode: 'system', configKey: '', displayConfigKey: '', name: '', configValue: '', sensitive: false, enabled: true, systemManaged: false })

/** 加载当前页系统参数，页码超出范围时回退到最后一页。 */
async function load() {
  const response = (await http.get('/system/settings', { params: query })).data
  rows.value = response.items
  total.value = response.total
  if (!rows.value.length && query.page > 1) {
    query.page -= 1
    await load()
  }
}

/** 按参数键从第一页开始检索系统参数。 */
async function search() {
  query.page = 1
  await load()
}

/** 清空参数键检索条件并返回第一页。 */
async function resetSearch() {
  query.configKey = ''
  query.page = 1
  await load()
}

/** 根据参数分组展示去除前缀后的短 Key。 */
function shortKey(row) {
  const prefix = `${row.groupCode}.`
  return row.configKey?.startsWith(prefix) ? row.configKey.slice(prefix.length) : row.configKey
}

/** 根据当前用户身份决定编辑窗口是否回显敏感值。 */
function editableValue(row) {
  return row.sensitive && !auth.isAdmin ? '' : row.configValue
}

/** 打开参数窗口并准备短 Key 展示值。 */
function open(row) {
  const value = row
    ? { ...row, displayConfigKey: shortKey(row), configValue: editableValue(row) }
    : { id: null, groupCode: 'system', configKey: '', displayConfigKey: '', name: '', configValue: '', sensitive: false, enabled: true, systemManaged: false }
  Object.assign(form, value)
  visible.value = true
}

/** 保存参数时将短 Key 还原为完整 Key。 */
async function save() {
  const payload = { ...form }
  delete payload.displayConfigKey
  if (!form.systemManaged) payload.configKey = form.groupCode && form.groupCode !== 'system' ? `${form.groupCode}.${form.displayConfigKey}` : form.displayConfigKey
  if (form.id) await http.put(`/system/settings/${form.id}`, payload)
  else await http.post('/system/settings', payload)
  visible.value = false
  await load()
  ElMessage.success(t('common.successSaved'))
}

/** 删除非系统托管参数。 */
async function remove(row) {
  await ElMessageBox.confirm(t('common.confirmDelete', { name: shortKey(row) }), t('common.deleteConfirm'), { type: 'warning' })
  await http.delete(`/system/settings/${row.id}`)
  await load()
  ElMessage.success(t('common.successDeleted'))
}

onMounted(load)
</script>
