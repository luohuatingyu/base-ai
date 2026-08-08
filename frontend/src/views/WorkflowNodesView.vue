<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('workflowNodes.title') }}</h2><p>{{ t('workflowNodes.description') }}</p></div>
      <el-button v-if="auth.hasPermission('workflow:node:create')" type="primary" @click="open()">{{ t('workflowNodes.add') }}</el-button>
    </div>
    <div class="node-template-filters" aria-label="workflow node filters">
      <div class="node-template-filter-level">
        <strong>{{ t('workflowNodes.source') }}</strong>
        <el-radio-group v-model="selectedSource" class="node-template-filter-options">
          <el-radio-button v-for="source in sources" :key="source" :value="source">{{ t(`workflowCatalog.sources.${source}`) }}</el-radio-button>
        </el-radio-group>
      </div>
      <div class="node-template-filter-level">
        <strong>{{ t('workflowNodes.category') }}</strong>
        <el-radio-group v-model="selectedCategory" class="node-template-filter-options">
          <el-radio-button v-for="category in categories" :key="category" :value="category">{{ t(`workflowCatalog.categories.${category}`) }}</el-radio-button>
        </el-radio-group>
      </div>
    </div>
    <section class="node-template-group">
      <div class="node-template-group-head"><div><h3>{{ t(`workflowCatalog.categories.${selectedCategory}`) }}</h3><p>{{ t('workflowNodes.filteredDescription', { source: t(`workflowCatalog.sources.${selectedSource}`) }) }}</p></div><el-tag round>{{ filteredRows.length }}</el-tag></div>
      <div v-if="filteredRows.length" class="node-template-grid">
        <article v-for="row in filteredRows" :key="row.id" class="node-template-card" :class="[`node-template-card--${row.nodeType.toLowerCase()}`, { disabled: !row.enabled }]">
          <button type="button" class="node-template-card-main" :disabled="!auth.hasPermission('workflow:node:update')" @click="open(row)">
            <span class="node-template-icon">{{ templateIcon(row) }}</span>
            <span class="node-template-summary"><strong>{{ templateText(row, 'name') }}</strong></span>
            <el-tag size="small" :type="row.enabled ? 'success' : 'info'">{{ row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag>
            <span class="node-template-type">{{ t(`workflowCatalog.categories.${row.functionalCategory}`) }} · {{ t(`workflowCatalog.sources.${row.source}`) }}</span>
            <small>{{ templateText(row, 'description') || t('workflowNodes.noDescription') }}</small>
          </button>
          <div class="node-template-actions">
            <el-button v-if="auth.hasPermission('workflow:node:update')" link type="primary" @click="open(row)">{{ t('common.edit') }}</el-button>
            <el-button v-if="!row.systemTemplate && auth.hasPermission('workflow:node:delete')" link type="danger" @click="remove(row)">{{ t('common.delete') }}</el-button>
          </div>
        </article>
      </div>
      <el-empty v-else :description="t('workflowNodes.emptyGroup')" :image-size="56" />
    </section>

    <el-dialog v-model="visible" :title="form.id ? t('workflowNodes.edit') : t('workflowNodes.add')" width="min(980px, 94vw)" top="5vh">
      <el-form label-width="120px">
        <el-form-item :label="t('common.code')"><el-input v-model="form.code" :disabled="form.systemTemplate" /></el-form-item>
        <el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('common.type')"><el-select v-model="form.nodeType" class="full" :disabled="form.systemTemplate" @change="syncDefaultCategory"><el-option v-for="type in nodeTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item :label="t('workflowNodes.source')" required><el-select v-model="form.source" class="full"><el-option v-for="source in sources" :key="source" :label="t(`workflowCatalog.sources.${source}`)" :value="source" /></el-select></el-form-item>
        <el-form-item :label="t('workflowNodes.category')" required><el-select v-model="form.functionalCategory" class="full"><el-option v-for="category in categories" :key="category" :label="t(`workflowCatalog.categories.${category}`)" :value="category" /></el-select></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item :label="t('workflowNodes.defaultConfig')"><WorkflowNodeConfigEditor v-model="form.config" :node-type="form.nodeType" /></el-form-item>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import WorkflowNodeConfigEditor from '../components/WorkflowNodeConfigEditor.vue'
import { cloneConfig, WORKFLOW_NODE_TYPES } from '../utils/workflowNodeConfig'
import { defaultTemplateCategory, filterWorkflowTemplates, localizedTemplateText, normalizeTemplateMetadata, WORKFLOW_TEMPLATE_CATEGORIES, WORKFLOW_TEMPLATE_SOURCES } from '../utils/workflowTemplateCatalog'

const { t, te } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const visible = ref(false)
const nodeTypes = WORKFLOW_NODE_TYPES
const sources = WORKFLOW_TEMPLATE_SOURCES
const categories = WORKFLOW_TEMPLATE_CATEGORIES
const selectedSource = ref('SYSTEM')
const selectedCategory = ref('BASIC')
const form = reactive(emptyForm())
/** 返回同时匹配必选来源和功能分类的节点，并保留停用模板供管理员维护。 */
const filteredRows = computed(() => filterWorkflowTemplates(rows.value, selectedSource.value, selectedCategory.value, true))

/** 按当前界面语言返回系统模板文案，自定义模板保留管理员录入内容。 */
function templateText(template, field) {
  return localizedTemplateText(template, field, t, te)
}

/** 使用当前语言的功能名称生成卡片图标文字。 */
function templateIcon(template) { return templateText(template, 'name').trim().slice(0, 2).toUpperCase() || '·' }

/** 加载未作废节点模板。 */
async function load() { rows.value = (await http.get('/workflow/nodes')).data || [] }
/** 打开新增或编辑表单，并隔离默认配置副本。 */
function open(row) { Object.assign(form, emptyForm(), normalizeTemplateMetadata(row), { config: cloneConfig(row?.config) }); visible.value = true }
/** 节点类型变化时切换到该原生能力的推荐功能分类。 */
function syncDefaultCategory(nodeType) { form.functionalCategory = defaultTemplateCategory(nodeType) }
/** 校验必填字段并保存模板。 */
async function save() {
  if (!form.name.trim() || !form.code.trim() || !sources.includes(form.source) || !categories.includes(form.functionalCategory)) {
    ElMessage.warning(t('workflowNodes.required')); return
  }
  const command = { code: form.code, name: form.name, nodeType: form.nodeType, description: form.description,
    config: cloneConfig(form.config), enabled: form.enabled, source: form.source, functionalCategory: form.functionalCategory }
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
function emptyForm() { return { id: null, code: '', name: '', nodeType: 'LLM', description: '', config: {}, enabled: true,
  systemTemplate: false, source: 'SYSTEM', functionalCategory: defaultTemplateCategory('LLM') } }
onMounted(load)
</script>

<style scoped>
.node-template-filters { display: grid; gap: 14px; margin-bottom: 24px; padding: 18px; border: 1px solid #dfe6f0; border-radius: 14px; background: #f8faff; }
.node-template-filter-level { display: grid; grid-template-columns: 92px minmax(0, 1fr); gap: 14px; align-items: start; }
.node-template-filter-level strong { padding-top: 7px; color: #344054; }
.node-template-filter-options { display: flex; flex-wrap: wrap; gap: 8px; }
.node-template-filter-options :deep(.el-radio-button__inner) { border: 1px solid #dcdfe6; border-radius: 8px; box-shadow: none; }
.node-template-filter-options :deep(.el-radio-button:first-child .el-radio-button__inner), .node-template-filter-options :deep(.el-radio-button:last-child .el-radio-button__inner) { border-radius: 8px; }
.node-template-group-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; margin-bottom: 14px; }
.node-template-group-head h3 { margin: 0 0 5px; }
.node-template-group-head p { margin: 0; color: var(--app-muted); }
.node-template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 14px; }
.node-template-card { overflow: hidden; border: 1px solid #dfe6f0; border-radius: 14px; background: #fff; box-shadow: 0 7px 20px rgb(31 53 91 / 7%); transition: border-color .2s ease, transform .2s ease, box-shadow .2s ease; }
.node-template-card:hover { border-color: #9eb8ef; transform: translateY(-2px); box-shadow: 0 12px 26px rgb(31 53 91 / 12%); }
.node-template-card.disabled { opacity: .68; }
.node-template-card-main { display: grid; width: 100%; grid-template-columns: 44px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 16px; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.node-template-card-main:disabled { cursor: default; }
.node-template-icon { display: grid; grid-row: span 2; place-items: center; width: 44px; height: 44px; border-radius: 12px; color: #315ea8; background: #eaf1ff; font-size: 12px; font-weight: 800; }
.node-template-card--agent .node-template-icon, .node-template-card--llm .node-template-icon, .node-template-card--question_classifier .node-template-icon, .node-template-card--parameter_extractor .node-template-icon { color: #6d4cc7; background: #f0ebff; }
.node-template-card--condition .node-template-icon, .node-template-card--switch .node-template-icon, .node-template-card--loop .node-template-icon, .node-template-card--iteration .node-template-icon { color: #9a6700; background: #fff5d6; }
.node-template-summary { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.node-template-summary strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-template-type, .node-template-card-main small { color: var(--app-muted); font-size: 12px; }
.node-template-type { grid-column: 2 / -1; }
.node-template-card-main small { grid-column: 1 / -1; min-height: 36px; line-height: 1.5; }
.node-template-actions { display: flex; justify-content: flex-end; gap: 4px; padding: 8px 14px; border-top: 1px solid #edf1f6; }
:deep(.el-dialog__body) { max-height: 76vh; overflow-y: auto; }
:deep(.workflow-config-editor) { width: 100%; }
@media (max-width: 600px) {
  .node-template-filter-level { grid-template-columns: 1fr; gap: 8px; }
  .node-template-filter-level strong { padding-top: 0; }
  .node-template-grid { grid-template-columns: 1fr; }
}
</style>
