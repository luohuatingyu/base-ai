<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('workflowNodes.title') }}</h2><p>{{ t('workflowNodes.description') }}</p></div>
      <el-button v-if="auth.hasPermission('workflow:node:create')" type="primary" @click="open()">{{ t('workflowNodes.add') }}</el-button>
    </div>
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('common.code')" min-width="130" />
      <el-table-column prop="name" :label="t('common.name')" min-width="150" />
      <el-table-column prop="nodeType" :label="t('common.type')" width="130" />
      <el-table-column prop="description" :label="t('common.description')" min-width="220" />
      <el-table-column :label="t('workflowNodes.source')" width="110"><template #default="scope">{{ scope.row.systemTemplate ? t('workflowNodes.system') : t('workflowNodes.custom') }}</template></el-table-column>
      <el-table-column :label="t('common.status')" width="100"><template #default="scope">{{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}</template></el-table-column>
      <el-table-column :label="t('common.operation')" width="170" fixed="right"><template #default="scope"><div class="table-actions">
        <el-button v-if="auth.hasPermission('workflow:node:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
        <el-button v-if="!scope.row.systemTemplate && auth.hasPermission('workflow:node:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
      </div></template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('workflowNodes.edit') : t('workflowNodes.add')" width="min(680px, 94vw)">
      <el-form label-width="120px">
        <el-form-item :label="t('common.code')"><el-input v-model="form.code" :disabled="form.systemTemplate" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('common.type')"><el-select v-model="form.nodeType" class="full" :disabled="form.systemTemplate"><el-option v-for="type in nodeTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item :label="t('workflowNodes.defaultConfig')"><el-input v-model="form.configText" type="textarea" :rows="12" spellcheck="false" /></el-form-item>
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
const nodeTypes = ['LLM', 'HTTP', 'AGENT', 'CONDITION', 'ITERATION', 'LOOP']
const form = reactive(emptyForm())

/** 加载未作废节点模板。 */
async function load() { rows.value = (await http.get('/workflow/nodes')).data || [] }
/** 打开新增或编辑表单，并格式化默认配置。 */
function open(row) { Object.assign(form, emptyForm(), row || {}, { configText: JSON.stringify(row?.config || {}, null, 2) }); visible.value = true }
/** 校验 JSON 并保存模板。 */
async function save() {
  let config
  try { config = JSON.parse(form.configText || '{}') } catch { ElMessage.warning(t('workflowNodes.invalidJson')); return }
  if (!form.name.trim() || !form.code.trim()) { ElMessage.warning(t('workflowNodes.required')); return }
  const command = { code: form.code, name: form.name, nodeType: form.nodeType, description: form.description, config, enabled: form.enabled }
  try {
    if (form.id) await http.put(`/workflow/nodes/${form.id}`, command)
    else await http.post('/workflow/nodes', command)
    visible.value = false; await load(); ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
}
/** 软删除用户模板并保留历史版本快照。 */
async function remove(row) {
  try {
    await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm'))
    await http.delete(`/workflow/nodes/${row.id}`); await load(); ElMessage.success(t('common.successDeleted'))
  } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) }
}
/** 创建隔离的空表单。 */
function emptyForm() { return { id: null, code: '', name: '', nodeType: 'LLM', description: '', configText: '{}', enabled: true, systemTemplate: false } }
onMounted(load)
</script>
