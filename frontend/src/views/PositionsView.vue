<template>
  <div class="panel">
    <div class="section-head"><div><h2>{{ t('positions.title') }}</h2><p>{{ t('positions.description') }}</p></div><el-button v-if="auth.hasPermission('system:position:create')" type="primary" @click="open()">{{ t('positions.add') }}</el-button></div>
    <el-table :data="rows"><el-table-column prop="code" :label="t('common.code')"/><el-table-column prop="name" :label="t('common.name')"/><el-table-column prop="sortOrder" :label="t('common.sort')"/><el-table-column :label="t('common.operation')"><template #default="s"><el-button link type="primary" @click="open(s.row)">{{ t('common.edit') }}</el-button><el-button link type="danger" @click="remove(s.row)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="total, prev, pager, next"/><el-dialog v-model="visible" :title="form.id?t('positions.edit'):t('positions.add')"><el-form label-width="90px"><el-form-item :label="t('common.code')"><el-input v-model="form.code"/></el-form-item><el-form-item :label="t('common.name')"><el-input v-model="form.name"/></el-form-item><el-form-item :label="t('common.sort')"><el-input-number v-model="form.sortOrder"/></el-form-item><el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
const { t } = useI18n(); const auth = useAuthStore(), allRows = ref([]), visible = ref(false)
const form = reactive({ id: null, code: '', name: '', sortOrder: 0, enabled: true }), query = reactive({ page: 1, size: 5 })
const total = computed(() => allRows.value.length), rows = computed(() => allRows.value.slice((query.page - 1) * query.size, query.page * query.size))
async function load() { allRows.value = (await http.get('/system/positions')).data }
/** 打开岗位窗口。 */
function open(row) { Object.assign(form, row ? { ...row } : { id: null, code: '', name: '', sortOrder: 0, enabled: true }); visible.value = true }
/** 保存岗位。 */
async function save() { form.id ? await http.put(`/system/positions/${form.id}`, form) : await http.post('/system/positions', form); visible.value = false; await load(); ElMessage.success(t('common.successSaved')) }
/** 删除岗位。 */
async function remove(row) { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm'), { type: 'warning' }); await http.delete(`/system/positions/${row.id}`); await load(); ElMessage.success(t('common.successDeleted')) }
onMounted(load)
</script>
