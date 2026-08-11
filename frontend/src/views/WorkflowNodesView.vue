<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('workflowNodes.title') }}</h2><p>{{ t('workflowNodes.description') }}</p></div>
      <router-link to="/workflow/node-docs"><el-button>{{ t('workflowNodeDocs.title') }}</el-button></router-link>
      <el-button v-if="selectedSource === 'SYSTEM' && auth.hasPermission('workflow:node:create')" type="primary" @click="open()">{{ t('workflowNodes.add') }}</el-button>
      <el-button v-else-if="selectedSource !== 'SYSTEM' && auth.hasPermission('workflow:node:import')" type="primary" @click="openMarketplace">{{ t('workflowNodes.importFrom', { source: t(`workflowCatalog.sources.${selectedSource}`) }) }}</el-button>
    </div>
    <div class="node-template-source-filter" :aria-label="t('workflowNodes.source')">
      <strong>{{ t('workflowNodes.source') }}</strong>
      <el-radio-group v-model="selectedSource" class="node-template-source-options">
        <el-radio-button v-for="source in sources" :key="source" :value="source">{{ t(`workflowCatalog.sources.${source}`) }}</el-radio-button>
      </el-radio-group>
    </div>
    <div class="node-template-layout">
      <aside class="node-template-category-filter" :aria-label="t('workflowNodes.category')">
        <strong>{{ t('workflowNodes.category') }}</strong>
        <el-radio-group v-model="selectedCategory" class="node-template-category-options">
          <el-tooltip v-for="category in categories" :key="category" :content="t(`workflowCatalog.categories.${category}`)" placement="right" :show-after="300">
            <el-radio-button :value="category" :aria-label="t(`workflowCatalog.categories.${category}`)">
              <span class="node-template-category-label">{{ t(`workflowCatalog.categories.${category}`) }}</span>
            </el-radio-button>
          </el-tooltip>
        </el-radio-group>
      </aside>
      <section class="node-template-group">
      <div v-if="filteredRows.length" class="node-template-grid">
        <article v-for="row in filteredRows" :key="row.id" class="node-template-card" :class="[`node-template-card--${row.nodeType.toLowerCase()}`, { disabled: !row.enabled }]">
          <button type="button" class="node-template-card-main" :disabled="!auth.hasPermission('workflow:node:update')" @click="open(row)">
            <span class="node-template-icon" :style="workflowTemplateCategoryStyle(row.functionalCategory, row.nodeType)">{{ templateIcon(row) }}</span>
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
    </div>

    <el-dialog v-model="visible" :title="form.id ? t('workflowNodes.edit') : t('workflowNodes.add')" width="min(980px, 94vw)" top="5vh">
      <el-alert class="node-template-required-hint" :title="t('workflowNodes.requiredHint')" type="info" show-icon :closable="false" />
      <el-form label-width="120px">
        <el-form-item :label="t('common.code')" required><el-input v-model="form.code" :disabled="form.systemTemplate || form.importedTemplate" /></el-form-item>
        <el-form-item :label="t('common.name')" required><el-input v-model="form.name" /></el-form-item>
        <el-form-item :label="t('common.type')" required><el-select v-model="form.nodeType" class="full" :disabled="form.systemTemplate || form.importedTemplate" @change="syncDefaultCategory"><el-option v-for="type in nodeTypes" :key="type" :label="type" :value="type" /></el-select></el-form-item>
        <el-form-item :label="t('workflowNodes.source')" required><el-select v-model="form.source" class="full" :disabled="form.importedTemplate || !form.id"><el-option v-for="source in sources" :key="source" :label="t(`workflowCatalog.sources.${source}`)" :value="source" /></el-select></el-form-item>
        <el-form-item :label="t('workflowNodes.category')" required><el-select v-model="form.functionalCategory" class="full"><el-option v-for="category in categories" :key="category" :label="t(`workflowCatalog.categories.${category}`)" :value="category" /></el-select></el-form-item>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" type="textarea" :rows="2" /></el-form-item>
        <el-form-item :label="t('workflowNodes.defaultConfig')"><WorkflowNodeConfigEditor v-model="form.config" :node-type="form.nodeType" /></el-form-item>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled" /></el-form-item>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="marketplaceVisible" :title="t('workflowNodes.marketplaceTitle', { source: t(`workflowCatalog.sources.${selectedSource}`) })" width="min(1040px, 95vw)" top="4vh" @closed="stopMarketplacePolling">
      <div class="marketplace-toolbar">
        <el-input v-model="marketplaceQuery" clearable :placeholder="t('workflowNodes.marketplaceSearch')" @keyup.enter="searchMarketplace" />
        <el-checkbox v-model="compatibleOnly" @change="searchMarketplace">{{ t('workflowNodes.compatibleOnly') }}</el-checkbox>
        <el-button :loading="marketplaceLoading" @click="searchMarketplace">{{ t('common.search') }}</el-button>
      </div>
      <el-alert :title="t('workflowNodes.marketplaceHint')" type="info" show-icon :closable="false" />
      <el-checkbox-group v-if="marketplaceItems.length" v-model="selectedMarketplaceIds" class="marketplace-grid"
        :class="{ 'marketplace-grid--dify': selectedSource === 'DIFY' }">
        <article v-for="item in marketplaceItems" :key="item.externalId" class="marketplace-card"
          :class="{ unsupported: !item.compatible, 'marketplace-card--plugin': item.actions?.length }">
          <div class="marketplace-card-head">
            <div class="marketplace-card-identity">
              <el-checkbox v-if="!item.actions?.length" :value="item.externalId" :disabled="!item.compatible">
                <strong>{{ item.name }}</strong>
              </el-checkbox>
              <strong v-else>{{ item.name }}</strong>
            </div>
            <div class="marketplace-card-status">
              <el-tag v-if="isProbePending(item)" type="warning" size="small">{{ t('workflowNodes.probing') }}</el-tag>
              <el-tag v-else-if="item.compatible" type="success" size="small">{{ t('workflowNodes.compatible') }}</el-tag>
              <el-tag v-else type="info" size="small">{{ t('workflowNodes.unsupported') }}</el-tag>
            </div>
          </div>
          <p class="marketplace-card-description" :title="marketplaceDescription(item)">{{ marketplaceDescription(item) }}</p>
          <div class="marketplace-meta">
            <span>{{ item.publisher || selectedSource }}</span>
            <span v-if="item.version">v{{ item.version }}</span>
            <span v-if="marketplaceTypeLabel(item)">{{ marketplaceTypeLabel(item) }}</span>
          </div>
          <div v-if="item.actions?.length" class="marketplace-actions">
            <div class="marketplace-actions-head">
              <strong>{{ t('workflowNodes.marketplaceCapabilities') }}</strong>
              <small>{{ t('workflowNodes.marketplaceCapabilityHint') }}</small>
            </div>
            <el-checkbox v-for="action in item.actions" :key="action.externalId" :value="action.externalId" :disabled="!action.compatible">
              <span><strong>{{ action.name }}</strong><small>{{ action.description }}</small></span>
            </el-checkbox>
          </div>
          <div class="marketplace-card-footer">
          <el-tag v-if="item.compatibilityLevel === 'NATIVE_SUBSET'" type="warning" size="small">{{ t('workflowNodes.nativeSubset') }}</el-tag>
          <el-tag v-else-if="item.compatibilityLevel === 'PARTIAL'" type="warning" size="small">{{ t('workflowNodes.partialCompatibility') }}</el-tag>
          <el-tag v-else-if="isProbePending(item)" type="info" size="small">{{ t('workflowNodes.probePending') }}</el-tag>
            <small v-if="!item.compatible">{{ t(`workflowNodes.incompatibility.${item.incompatibilityReason}`) }}</small>
          </div>
        </article>
      </el-checkbox-group>
      <el-empty v-else-if="!marketplaceLoading" :description="t('workflowNodes.marketplaceEmpty')" :image-size="56" />
      <el-skeleton v-else :rows="6" animated />
      <el-pagination v-if="marketplaceTotal > marketplacePageSize" v-model:current-page="marketplacePage" class="marketplace-pagination"
        layout="prev, pager, next" :page-size="marketplacePageSize" :total="marketplaceTotal" @current-change="loadMarketplace" />
      <template #footer>
        <el-button @click="marketplaceVisible=false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="marketplaceImporting" :disabled="!selectedMarketplaceIds.length" @click="importMarketplaceNodes">
          {{ t('workflowNodes.importSelected', { count: selectedMarketplaceIds.length }) }}
        </el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import WorkflowNodeConfigEditor from '../components/WorkflowNodeConfigEditor.vue'
import { cloneConfig, WORKFLOW_NODE_TYPES } from '../utils/workflowNodeConfig'
import { defaultTemplateCategory, filterWorkflowTemplates, localizedTemplateText, marketplaceItemDescription, marketplaceNodeTypeLabel, normalizeTemplateMetadata, workflowTemplateCategoryStyle, WORKFLOW_TEMPLATE_CATEGORIES, WORKFLOW_TEMPLATE_SOURCES } from '../utils/workflowTemplateCatalog'

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
const marketplaceVisible = ref(false)
const marketplaceLoading = ref(false)
const marketplaceImporting = ref(false)
const marketplaceQuery = ref('')
const compatibleOnly = ref(false)
const marketplaceItems = ref([])
const selectedMarketplaceIds = ref([])
const marketplacePage = ref(1)
const marketplacePageSize = 20
const marketplaceTotal = ref(0)
const marketplaceProbePending = ref(false)
let marketplacePollTimer = null
/** 返回同时匹配必选来源和功能分类的节点，并保留停用模板供管理员维护。 */
const filteredRows = computed(() => filterWorkflowTemplates(rows.value, selectedSource.value, selectedCategory.value, true))

/** 按当前界面语言返回系统模板文案，自定义模板保留管理员录入内容。 */
function templateText(template, field) {
  return localizedTemplateText(template, field, t, te)
}

/** 返回市场条目的官方说明或来源感知的可信回退文案。 */
function marketplaceDescription(item) { return marketplaceItemDescription(item, selectedSource.value, t, te) }

/** 返回市场适配器对应的本地化原生能力名称。 */
function marketplaceTypeLabel(item) { return marketplaceNodeTypeLabel(item, t, te) }

/** 判断市场项是否仍在后台队列或 ABI 探测中。 */
function isProbePending(item) { return ['NOT_PROBED', 'QUEUED', 'PROBING'].includes(item?.probeStatus) }

/** 使用当前语言的功能名称生成卡片图标文字。 */
function templateIcon(template) { return templateText(template, 'name').trim().slice(0, 2).toUpperCase() || '·' }

/** 加载未作废节点模板。 */
async function load() { rows.value = (await http.get('/workflow/nodes')).data || [] }
/** 打开新增或编辑表单，并隔离默认配置副本。 */
function open(row) { Object.assign(form, emptyForm(), normalizeTemplateMetadata(row), { config: cloneConfig(row?.config) }); visible.value = true }
/** 打开当前来源的官方市场并重置临时选择。 */
async function openMarketplace() {
  marketplaceVisible.value = true; marketplaceQuery.value = ''; compatibleOnly.value = false
  marketplacePage.value = 1; selectedMarketplaceIds.value = []; await loadMarketplace()
}
/** 从第一页重新执行市场搜索。 */
async function searchMarketplace() { marketplacePage.value = 1; selectedMarketplaceIds.value = []; await loadMarketplace() }
/** 通过后端代理加载市场目录，避免浏览器直接访问第三方。 */
async function loadMarketplace() {
  if (!['N8N', 'DIFY'].includes(selectedSource.value)) return
  stopMarketplacePolling()
  marketplaceLoading.value = true
  try {
    const { data } = await http.get(`/workflow/node-marketplaces/${selectedSource.value}/nodes`, { params: {
      query: marketplaceQuery.value, page: marketplacePage.value, pageSize: marketplacePageSize,
      compatibleOnly: compatibleOnly.value
    } })
    marketplaceItems.value = data?.items || []; marketplaceTotal.value = Number(data?.total || 0)
    marketplaceProbePending.value = Boolean(data?.probePending)
    const compatibleIds = new Set(marketplaceItems.value.filter(item => item.compatible).flatMap(item =>
      item.actions?.length ? item.actions.filter(action => action.compatible).map(action => action.externalId) : [item.externalId]))
    selectedMarketplaceIds.value = selectedMarketplaceIds.value.filter(id => compatibleIds.has(id))
    scheduleMarketplacePolling()
  } catch (error) { marketplaceItems.value = []; marketplaceTotal.value = 0; marketplaceProbePending.value = false; showHttpError(error) }
  finally { marketplaceLoading.value = false }
}
/** 当前页存在未完成探测时自动刷新，避免由导入按钮触发探测。 */
function scheduleMarketplacePolling() {
  if (!marketplaceVisible.value || !marketplaceProbePending.value) return
  marketplacePollTimer = window.setTimeout(() => loadMarketplace(), 2000)
}
/** 清除市场轮询定时器，关闭弹窗或离开页面后不再请求。 */
function stopMarketplacePolling() {
  if (marketplacePollTimer !== null) window.clearTimeout(marketplacePollTimer)
  marketplacePollTimer = null
}
/** 导入服务端重新确认过的白名单节点，并刷新当前来源卡片。 */
async function importMarketplaceNodes() {
  marketplaceImporting.value = true
  try {
    let { data } = await http.post(`/workflow/node-marketplaces/${selectedSource.value}/imports`, {
      externalIds: [...selectedMarketplaceIds.value], replaceExisting: false
    })
    const updateItems = (data?.items || []).filter(item => item.status === 'UPDATE_AVAILABLE')
    if (updateItems.length) {
      const confirmed = await ElMessageBox.confirm(t('workflowNodes.updateAvailablePrompt', { count: updateItems.length }),
        t('workflowNodes.updateConfirmTitle')).then(() => true).catch(error => {
        if (error === 'cancel' || error === 'close') return false
        throw error
      })
      if (!confirmed) { marketplaceVisible.value = false; selectedMarketplaceIds.value = []; await load(); return }
      const response = await http.post(`/workflow/node-marketplaces/${selectedSource.value}/imports`, {
        externalIds: updateItems.map(item => item.externalId), replaceExisting: true
      })
      const replacements = new Map((response.data?.items || []).map(item => [item.externalId, item]))
      data = { ...data, items: (data?.items || []).map(item => replacements.get(item.externalId) || item) }
    }
    const created = (data?.items || []).filter(item => ['CREATED', 'RESTORED', 'UPDATED'].includes(item.status)).length
    marketplaceVisible.value = false; selectedMarketplaceIds.value = []; await load()
    const importedIds = new Set((data?.items || []).map(item => item.templateId))
    const firstImported = rows.value.find(row => importedIds.has(row.id))
    if (firstImported) selectedCategory.value = firstImported.functionalCategory
    ElMessage.success(t('workflowNodes.importCompleted', { created, total: data?.items?.length || 0 }))
  } catch (error) { showHttpError(error, 'workflowNodes.importFailed') }
  finally { marketplaceImporting.value = false }
}
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
  systemTemplate: false, importedTemplate: false, source: 'SYSTEM', functionalCategory: defaultTemplateCategory('LLM') } }
onMounted(load)
onBeforeUnmount(stopMarketplacePolling)
</script>

<style scoped>
.node-template-source-filter { display: flex; gap: 18px; align-items: flex-start; margin-bottom: 20px; padding: 18px; border: 1px solid #dfe6f0; border-radius: 14px; background: #f8faff; }
.node-template-source-filter > strong, .node-template-category-filter > strong { flex: 0 0 auto; padding-top: 7px; color: #344054; }
.node-template-source-options { display: flex; flex-wrap: wrap; gap: 8px; }
.node-template-layout { display: grid; grid-template-columns: minmax(180px, 220px) minmax(0, 1fr); gap: 24px; align-items: start; }
.node-template-category-filter { display: grid; gap: 12px; padding: 18px; border: 1px solid #dfe6f0; border-radius: 14px; background: #f8faff; }
.node-template-category-options { display: grid; width: 100%; gap: 8px; }
.node-template-category-options :deep(.el-radio-button), .node-template-category-options :deep(.el-radio-button__inner) { min-width: 0; width: 100%; }
.node-template-category-options :deep(.el-radio-button__inner) { box-sizing: border-box; display: flex; min-height: 48px; align-items: center; padding: 12px 14px; line-height: 1.4; text-align: left; }
.node-template-category-label { min-width: 0; flex: 1; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-template-source-options :deep(.el-radio-button__inner), .node-template-category-options :deep(.el-radio-button__inner) { border: 1px solid #dcdfe6; border-radius: 8px; box-shadow: none; }
.node-template-source-options :deep(.el-radio-button:first-child .el-radio-button__inner), .node-template-source-options :deep(.el-radio-button:last-child .el-radio-button__inner), .node-template-category-options :deep(.el-radio-button:first-child .el-radio-button__inner), .node-template-category-options :deep(.el-radio-button:last-child .el-radio-button__inner) { border-radius: 8px; }
.node-template-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(250px, 1fr)); gap: 14px; }
.node-template-card { overflow: hidden; border: 1px solid #dfe6f0; border-radius: 14px; background: #fff; box-shadow: 0 7px 20px rgb(31 53 91 / 7%); transition: border-color .2s ease, transform .2s ease, box-shadow .2s ease; }
.node-template-card:hover { border-color: #9eb8ef; transform: translateY(-2px); box-shadow: 0 12px 26px rgb(31 53 91 / 12%); }
.node-template-card.disabled { opacity: .68; }
.node-template-card-main { display: grid; width: 100%; grid-template-columns: 44px minmax(0, 1fr) auto; gap: 10px; align-items: center; padding: 16px; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.node-template-card-main:disabled { cursor: default; }
.node-template-icon { display: grid; grid-row: span 2; place-items: center; width: 44px; height: 44px; border-radius: 12px; font-size: 12px; font-weight: 800; }
.node-template-summary { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.node-template-summary strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.node-template-type, .node-template-card-main small { color: var(--app-muted); font-size: 12px; }
.node-template-type { grid-column: 2 / -1; }
.node-template-card-main small { grid-column: 1 / -1; min-height: 36px; line-height: 1.5; }
.node-template-actions { display: flex; justify-content: flex-end; gap: 4px; padding: 8px 14px; border-top: 1px solid #edf1f6; }
:deep(.el-dialog__body) { max-height: 76vh; overflow-y: auto; }
:deep(.workflow-config-editor) { width: 100%; }
.node-template-required-hint { margin-bottom: 16px; }
.marketplace-toolbar { display: grid; grid-template-columns: minmax(220px, 1fr) auto auto; gap: 12px; align-items: center; margin-bottom: 14px; }
.marketplace-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(280px, 1fr)); gap: 12px; margin-top: 16px; }
.marketplace-grid--dify { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px; }
.marketplace-card { display: grid; min-width: 0; min-height: 150px; align-content: start; gap: 12px; overflow: hidden; padding: 16px; border: 1px solid #dfe6f0; border-radius: 14px; color: var(--app-text); background: #fff; box-shadow: 0 4px 14px rgb(31 53 91 / 5%); font-size: 13px; line-height: 1.5; }
.marketplace-card.unsupported { background: #f8f9fb; opacity: .78; }
.marketplace-card-head { display: flex; min-width: 0; gap: 8px; align-items: flex-start; justify-content: space-between; }
.marketplace-card-identity { min-width: 0; flex: 1; }
.marketplace-card-head :deep(.el-checkbox), .marketplace-card-identity > strong { min-width: 0; max-width: 100%; }
.marketplace-card-head :deep(.el-checkbox) { height: auto; align-items: flex-start; white-space: normal; }
.marketplace-card-head :deep(.el-checkbox__label), .marketplace-card-identity > strong { min-width: 0; overflow-wrap: anywhere; white-space: normal; }
.marketplace-card-identity > strong { display: block; color: #344054; font-size: 14px; line-height: 1.5; }
.marketplace-card-status { display: flex; flex: 0 0 auto; align-items: center; }
.marketplace-card p { margin: 0; overflow-wrap: anywhere; color: var(--app-muted); font-size: 13px; line-height: 1.55; }
.marketplace-card-description { display: -webkit-box; min-height: 60px; overflow: hidden; -webkit-box-orient: vertical; -webkit-line-clamp: 3; }
.marketplace-meta { display: flex; flex-wrap: wrap; gap: 8px; align-items: center; justify-content: flex-start; color: var(--app-muted); font-size: 12px; line-height: 1.4; }
.marketplace-meta span { padding: 3px 8px; border-radius: 999px; background: #f1f4f9; color: #64748b; }
.marketplace-actions { display: grid; min-width: 0; gap: 8px; overflow: hidden; padding: 12px; border: 1px solid #e3eaf5; border-radius: 10px; background: #f8faff; }
.marketplace-actions-head { display: grid; gap: 2px; margin-bottom: 2px; }
.marketplace-actions-head strong { color: #344054; font-size: 13px; line-height: 1.4; }
.marketplace-actions-head small { color: var(--app-muted); font-size: 11px; font-weight: 400; line-height: 1.4; }
.marketplace-actions :deep(.el-checkbox) { width: 100%; min-width: 0; height: auto; align-items: flex-start; margin-right: 0; padding: 10px; border: 1px solid #e4eaf3; border-radius: 8px; background: #fff; white-space: normal; }
.marketplace-actions :deep(.el-checkbox__input) { margin-top: 2px; }
.marketplace-actions :deep(.el-checkbox__label) { min-width: 0; white-space: normal; }
.marketplace-actions span { display: grid; min-width: 0; gap: 3px; }
.marketplace-actions strong, .marketplace-actions small { overflow-wrap: anywhere; }
.marketplace-actions small { color: var(--app-muted); font-weight: 400; }
.marketplace-card-footer { display: flex; min-width: 0; align-items: center; flex-wrap: wrap; gap: 8px; }
.marketplace-card-footer > small { min-width: 0; overflow-wrap: anywhere; color: var(--el-color-warning-dark-2); }
.marketplace-pagination { justify-content: center; margin-top: 18px; }
@media (max-width: 800px) {
  .node-template-source-filter { display: grid; gap: 8px; }
  .node-template-source-filter > strong, .node-template-category-filter > strong { padding-top: 0; }
  .node-template-layout { grid-template-columns: 1fr; gap: 18px; }
  .node-template-category-options { display: flex; flex-wrap: wrap; }
  .node-template-category-options :deep(.el-radio-button), .node-template-category-options :deep(.el-radio-button__inner) { width: auto; }
  .node-template-category-options :deep(.el-radio-button__inner) { justify-content: center; text-align: center; }
}
@media (max-width: 600px) {
  .node-template-grid { grid-template-columns: 1fr; }
  .marketplace-toolbar { grid-template-columns: 1fr; }
  .marketplace-grid { grid-template-columns: 1fr; }
  .marketplace-grid--dify { grid-template-columns: 1fr; }
}
</style>
