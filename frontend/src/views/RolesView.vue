<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('roles.title') }}</h2><p>{{ t('roles.description') }}</p></div>
      <el-button v-if="auth.hasPermission('system:role:create')" type="primary" @click="open()">{{ t('roles.add') }}</el-button>
    </div>
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('common.code')" min-width="160" />
      <el-table-column :label="t('common.name')" min-width="180">
        <template #default="s">{{ localizeRoleName(s.row, t) }}</template>
      </el-table-column>
      <el-table-column prop="dataScope" :label="t('roles.scope')" min-width="160" />
      <el-table-column :label="t('common.status')" width="110">
        <template #default="scope">{{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}</template>
      </el-table-column>
      <el-table-column :label="t('common.operation')" width="180" fixed="right">
        <template #default="scope">
          <div class="table-actions">
            <el-button v-if="auth.hasPermission('system:role:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
            <el-button v-if="auth.hasPermission('system:role:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
          </div>
        </template>
      </el-table-column>
    </el-table>
    <el-pagination v-model:current-page="query.page" :page-size="query.size" :total="total" layout="total, prev, pager, next" />

    <el-dialog v-model="visible" class="role-editor-dialog" :title="form.id ? t('roles.edit') : t('roles.add')" width="760px">
      <el-form label-width="100px">
        <el-form-item :label="t('common.code')"><el-input v-model="form.code" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" /></el-form-item>
        <el-form-item :label="t('roles.scope')">
          <el-select v-model="form.dataScope">
            <el-option v-for="item in scopes" :key="item.value" :label="item.label" :value="item.value" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="form.dataScope === 'CUSTOM'" :label="t('roles.customDepartments')">
          <el-select v-model="form.departmentIds" multiple>
            <el-option v-for="item in departments" :key="item.id" :label="localizeDepartmentName(item, t)" :value="item.id" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('roles.permissions')">
          <div class="role-permission-field">
            <p class="role-permission-help">{{ t('roles.permissionHelp') }}</p>
            <el-tree
              ref="permissionTree"
              class="role-permission-tree"
              :data="permissionTreeData"
              node-key="id"
              show-checkbox
              check-strictly
              default-expand-all
              :expand-on-click-node="false"
              @check-change="handlePermissionCheck"
            >
              <template #default="{ data }">
                <span class="role-permission-node">
                  <span>{{ data.label }}</span>
                  <el-tag size="small" effect="plain">{{ t(`menus.types.${data.type.toLowerCase()}`) }}</el-tag>
                  <code v-if="data.permission">{{ data.permission }}</code>
                </span>
              </template>
            </el-tree>
          </div>
        </el-form-item>
        <el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="visible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { localizeRoleName, localizeDepartmentName } from '../utils/localization'
import { localizeMenuName } from '../utils/navigation'
import {
  buildRolePermissionTree,
  normalizeRolePermissionIds,
  updateRolePermissionSelection
} from '../utils/rolePermissions'

const { t } = useI18n()
const auth = useAuthStore()
const allRoles = ref([])
const menus = ref([])
const departments = ref([])
const visible = ref(false)
const permissionTree = ref(null)
const syncingTree = ref(false)
const scopes = computed(() => [
  { value: 'ALL', label: t('roles.allData') },
  { value: 'DEPARTMENT', label: t('roles.department') },
  { value: 'DEPARTMENT_AND_CHILDREN', label: t('roles.departmentChildren') },
  { value: 'SELF', label: t('roles.self') },
  { value: 'CUSTOM', label: t('roles.customDepartments') }
])
const permissionTreeData = computed(() => buildRolePermissionTree(menus.value, menu => localizeMenuName(menu, t)))
const emptyRole = () => ({ id: null, code: '', name: '', description: '', dataScope: 'ALL', enabled: true, menuIds: [], departmentIds: [] })
const form = reactive(emptyRole())
const query = reactive({ page: 1, size: 5 })
const total = computed(() => allRoles.value.length)
const rows = computed(() => allRoles.value.slice((query.page - 1) * query.size, query.page * query.size))

/** 加载角色及权限树所需的全部基础数据。 */
async function load() {
  [allRoles.value, menus.value, departments.value] = await Promise.all(
    ['/system/roles', '/system/menus', '/system/departments'].map(url => http.get(url).then(response => response.data))
  )
}

/** 将权限 ID 同步到树组件，避免程序化联动再次触发业务处理。 */
async function syncPermissionTree(menuIds) {
  syncingTree.value = true
  form.menuIds = normalizeRolePermissionIds(menus.value, menuIds)
  await nextTick()
  permissionTree.value?.setCheckedKeys(form.menuIds)
  await nextTick()
  syncingTree.value = false
}

/** 打开角色编辑窗口并按树形层级回显权限。 */
async function open(row) {
  Object.assign(form, row
    ? { ...row, menuIds: [...row.menuIds], departmentIds: [...row.departmentIds] }
    : emptyRole())
  visible.value = true
  await syncPermissionTree(form.menuIds)
}

/** 按精确依赖规则处理权限勾选，按钮必须同时拥有所属页面。 */
async function handlePermissionCheck(node, checked) {
  if (syncingTree.value) return
  const updated = updateRolePermissionSelection(menus.value, form.menuIds, node.id, checked)
  if (checked && !updated.includes(node.id)) ElMessage.warning(t('roles.buttonPageRequired'))
  await syncPermissionTree(updated)
}

/** 保存角色及树中当前选中的权限。 */
async function save() {
  try {
    form.menuIds = permissionTree.value?.getCheckedKeys() || form.menuIds
    form.id
      ? await http.put(`/system/roles/${form.id}`, form)
      : await http.post('/system/roles', form)
    visible.value = false
    await load()
    ElMessage.success(t('common.successSaved'))
  } catch (error) {
    ElMessage.error(error.response?.data?.message || t('common.saveFailed'))
  }
}

/** 删除未使用角色。 */
async function remove(row) {
  await ElMessageBox.confirm(t('common.confirmDelete', { name: localizeRoleName(row, t) }), t('common.deleteConfirm'), { type: 'warning' })
  await http.delete(`/system/roles/${row.id}`)
  await load()
  ElMessage.success(t('common.successDeleted'))
}

onMounted(load)
</script>
