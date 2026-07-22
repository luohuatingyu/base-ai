<template>
  <div class="panel">
    <div class="section-head"><div><h2>{{ t('departments.title') }}</h2><p>{{ t('departments.description') }}</p></div><el-button v-if="auth.hasPermission('system:department:create')" type="primary" @click="open()">{{ t('departments.add') }}</el-button></div>
    <el-table :data="treeRows" row-key="id" default-expand-all><el-table-column prop="name" :label="t('common.name')"/><el-table-column prop="code" :label="t('common.code')"/><el-table-column prop="sortOrder" :label="t('common.sort')"/><el-table-column :label="t('common.operation')"><template #default="s"><el-button link @click="open(null,s.row.id)">{{ t('departments.addChild') }}</el-button><el-button link type="primary" @click="open(s.row)">{{ t('common.edit') }}</el-button><el-button link type="danger" @click="remove(s.row)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table>
    <el-dialog v-model="visible" :title="form.id?t('departments.edit'):t('departments.add')"><el-form label-width="90px"><el-form-item :label="t('departments.parent')"><el-select v-model="form.parentId" clearable><el-option v-for="item in rows" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item><el-form-item :label="t('common.code')"><el-input v-model="form.code"/></el-form-item><el-form-item :label="t('common.name')"><el-input v-model="form.name"/></el-form-item><el-form-item :label="t('common.sort')"><el-input-number v-model="form.sortOrder"/></el-form-item><el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { buildTree } from '../utils/tree'

const { t } = useI18n()
const auth = useAuthStore(), rows = ref([]), visible = ref(false)
const treeRows = computed(() => buildTree(rows.value))
const form = reactive({ id: null, parentId: null, code: '', name: '', sortOrder: 0, enabled: true })
async function load() { rows.value = (await http.get('/system/departments')).data }
/** 打开部门窗口。 */
function open(row, parentId) { Object.assign(form, row ? { ...row } : { id: null, parentId: parentId || null, code: '', name: '', sortOrder: 0, enabled: true }); visible.value = true }
/** 保存部门。 */
async function save() { form.id ? await http.put(`/system/departments/${form.id}`, form) : await http.post('/system/departments', form); visible.value = false; await load(); ElMessage.success(t('common.successSaved')) }
/** 删除部门。 */
async function remove(row) { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm'), { type: 'warning' }); await http.delete(`/system/departments/${row.id}`); await load(); ElMessage.success(t('common.successDeleted')) }
onMounted(load)
</script>
