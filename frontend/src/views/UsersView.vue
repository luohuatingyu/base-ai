<template>
  <div class="panel">
    <div class="section-head"><div><h2>{{ t('users.title') }}</h2><p>{{ t('users.description') }}</p></div><el-button v-if="auth.hasPermission('system:user:create')" type="primary" @click="open()">{{ t('users.add') }}</el-button></div>
    <el-form inline><el-form-item><el-input v-model="query.keyword" :placeholder="t('users.keyword')" clearable @keyup.enter="load"/></el-form-item><el-form-item><el-select v-model="query.enabled" clearable :placeholder="t('users.allStatus')" style="width:130px"><el-option :label="t('common.enabled')" :value="true"/><el-option :label="t('common.disabled')" :value="false"/></el-select></el-form-item><el-button @click="load">{{ t('common.query') }}</el-button></el-form>
    <el-table :data="rows"><el-table-column prop="username" :label="t('common.account')"/><el-table-column prop="displayName" :label="t('common.name')"/><el-table-column :label="t('users.department')"><template #default="s">{{ departmentName(s.row.departmentId) }}</template></el-table-column><el-table-column :label="t('common.status')"><template #default="s"><el-tag :type="s.row.enabled?'success':'info'">{{ s.row.enabled?t('common.enabled'):t('common.disabled') }}</el-tag></template></el-table-column><el-table-column :label="t('common.operation')" width="150"><template #default="s"><el-button v-if="auth.hasPermission('system:user:update')" link type="primary" @click="open(s.row)">{{ t('common.edit') }}</el-button><el-button v-if="auth.hasPermission('system:user:delete')" link type="danger" @click="remove(s.row)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table>
    <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="total, prev, pager, next" @current-change="load"/>
    <el-dialog v-model="visible" :title="form.id?t('users.edit'):t('users.add')" width="580px"><el-form label-width="90px"><el-form-item :label="t('common.account')"><el-input v-model="form.username"/></el-form-item><el-form-item :label="t('users.displayName')"><el-input v-model="form.displayName"/></el-form-item><el-form-item :label="t('users.password')"><el-input v-model="form.password" type="password" show-password :placeholder="form.id?t('users.keepPassword'):''"/></el-form-item><el-form-item :label="t('users.department')"><el-select v-model="form.departmentId" clearable><el-option v-for="item in departments" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item><el-form-item :label="t('users.position')"><el-select v-model="form.positionIds" multiple><el-option v-for="item in positions" :key="item.id" :label="item.name" :value="item.id"/></el-select></el-form-item><el-form-item :label="t('users.role')"><el-select v-model="form.roleIds" multiple><el-option v-for="role in roles" :key="role.id" :label="role.name" :value="role.id"/></el-select></el-form-item><el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled"/></el-form-item></el-form><template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template></el-dialog>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
const { t } = useI18n(); const auth = useAuthStore(), rows = ref([]), roles = ref([]), departments = ref([]), positions = ref([]), total = ref(0), visible = ref(false)
const query = reactive({ keyword: '', enabled: null, page: 1, size: 5 }); const form = reactive({ id: null, username: '', displayName: '', password: '', enabled: true, departmentId: null, roleIds: [], positionIds: [] })
async function load() { const response = await http.get('/system/users', { params: query }); rows.value = response.data.items; total.value = response.data.total }
async function loadOptions() { [roles.value, departments.value, positions.value] = await Promise.all(['/system/roles', '/system/departments', '/system/positions'].map(url => http.get(url).then(r => r.data))) }
/** 打开用户编辑窗口。 */
function open(row) { Object.assign(form, row ? { ...row, password: '', roleIds: [...row.roleIds], positionIds: [...row.positionIds] } : { id: null, username: '', displayName: '', password: '', enabled: true, departmentId: null, roleIds: [], positionIds: [] }); visible.value = true }
/** 保存用户并刷新列表。 */
async function save() { try { form.id ? await http.put(`/system/users/${form.id}`, form) : await http.post('/system/users', form); visible.value = false; await load(); ElMessage.success(t('common.successSaved')) } catch (e) { ElMessage.error(e.response?.data?.message || t('common.saveFailed')) } }
/** 删除用户前进行二次确认。 */
async function remove(row) { await ElMessageBox.confirm(t('users.confirmDelete', { name: row.username }), t('common.deleteConfirm'), { type: 'warning' }); await http.delete(`/system/users/${row.id}`); await load(); ElMessage.success(t('common.successDeleted')) }
function departmentName(id) { return departments.value.find(item => item.id === id)?.name || '-' }
onMounted(async () => { await loadOptions(); await load() })
</script>
