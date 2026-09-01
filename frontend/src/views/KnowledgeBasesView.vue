<template>
  <div class="knowledge-page">
    <header class="knowledge-hero">
      <div><h2>{{ t('knowledgeBases.title') }}</h2><p>{{ t('knowledgeBases.description') }}</p></div>
      <el-button v-if="auth.hasPermission('knowledge:base:create')" type="primary" @click="openForm()">{{ t('knowledgeBases.add') }}</el-button>
    </header>

    <section class="knowledge-summary" aria-live="polite">
      <article><small>{{ t('knowledgeBases.summary.totalBases') }}</small><strong>{{ summary.totalBases }}</strong></article>
      <article><small>{{ t('knowledgeBases.summary.enabledBases') }}</small><strong>{{ summary.enabledBases }}</strong></article>
      <article><small>{{ t('knowledgeBases.summary.documents') }}</small><strong>{{ summary.documentCount }}</strong></article>
      <article :class="{ 'summary-danger': summary.failedDocumentCount }"><small>{{ t('knowledgeBases.summary.failedDocuments') }}</small><strong>{{ summary.failedDocumentCount }}</strong></article>
    </section>

    <el-alert :title="t('knowledgeBases.connectionHint')" type="info" show-icon :closable="false" />

    <section class="knowledge-workspace">
      <aside class="knowledge-directory panel">
        <div class="directory-head"><div><h3>{{ t('knowledgeBases.directory') }}</h3><small>{{ t('knowledgeBases.totalResults', { count: total }) }}</small></div><el-button link :loading="loading" @click="loadBases()">{{ t('common.refresh') }}</el-button></div>
        <div class="directory-filters">
          <el-input v-model="query.keyword" clearable :placeholder="t('knowledgeBases.searchPlaceholder')" :aria-label="t('knowledgeBases.searchPlaceholder')" @keyup.enter="searchBases" @clear="searchBases" />
          <div><el-select v-model="query.enabled" :placeholder="t('knowledgeBases.allStatuses')" clearable @change="searchBases"><el-option :label="t('common.enabled')" :value="true"/><el-option :label="t('common.disabled')" :value="false"/></el-select>
            <el-select v-model="query.storageType" :placeholder="t('knowledgeBases.allStores')" clearable @change="searchBases"><el-option v-for="type in storageTypes" :key="type" :label="storageLabel(type)" :value="type"/></el-select></div>
        </div>
        <div v-if="loading" class="directory-state"><el-skeleton :rows="5" animated /></div>
        <div v-else-if="loadError" class="directory-state"><el-result icon="error" :title="t('knowledgeBases.loadFailed')"><template #extra><el-button type="primary" @click="loadBases()">{{ t('knowledgeBases.retry') }}</el-button></template></el-result></div>
        <el-empty v-else-if="!rows.length" :description="hasBaseFilters ? t('knowledgeBases.noResults') : t('knowledgeBases.empty')">
          <el-button v-if="!hasBaseFilters&&auth.hasPermission('knowledge:base:create')" type="primary" @click="openForm()">{{ t('knowledgeBases.add') }}</el-button>
        </el-empty>
        <div v-else class="directory-list">
          <button v-for="row in rows" :key="row.id" type="button" :class="['directory-item',{ active: activeBase?.id===row.id }]" @click="selectBase(row)">
            <span class="directory-title"><strong>{{ row.name }}</strong><el-tag size="small" :type="row.enabled?'success':'info'">{{ row.enabled?t('common.enabled'):t('common.disabled') }}</el-tag></span>
            <code>{{ row.code }}</code><span class="directory-meta"><span>{{ storageLabel(row.storageType) }}</span><span>{{ t('knowledgeBases.documentCount', { count: row.documentCount }) }}</span></span>
            <span v-if="row.failedDocumentCount" class="directory-error">{{ t('knowledgeBases.failedCount', { count: row.failedDocumentCount }) }}</span>
          </button>
        </div>
        <el-pagination v-if="total>query.size" v-model:current-page="query.page" small background :page-size="query.size" :total="total" layout="prev, pager, next" @current-change="()=>loadBases()" />
      </aside>

      <main class="knowledge-detail panel">
        <el-empty v-if="!activeBase&&!loading" :description="t('knowledgeBases.selectHint')" />
        <template v-else-if="activeBase">
          <header class="detail-head">
            <div><div class="detail-title"><h3>{{ activeBase.name }}</h3><el-tag :type="activeBase.enabled?'success':'info'">{{ activeBase.enabled?t('common.enabled'):t('common.disabled') }}</el-tag><el-tag v-if="!canMaintain(activeBase)" type="warning">{{ t('knowledgeBases.readOnly') }}</el-tag></div><p>{{ activeBase.description || t('knowledgeBases.noDescription') }}</p></div>
            <div v-if="canMaintain(activeBase)" class="detail-actions">
              <el-switch v-if="auth.hasPermission('knowledge:base:update')" :model-value="activeBase.enabled" :loading="toggling" :active-text="t('common.enabled')" :inactive-text="t('common.disabled')" @change="toggleEnabled" />
              <el-button v-if="auth.hasPermission('knowledge:base:update')" @click="openForm(activeBase)">{{ t('common.edit') }}</el-button>
              <el-button v-if="auth.hasPermission('knowledge:base:delete')" type="danger" plain @click="removeBase(activeBase)">{{ t('common.delete') }}</el-button>
            </div>
          </header>

          <section class="detail-overview">
            <article><small>{{ t('knowledgeBases.documentsReady') }}</small><strong>{{ activeBase.readyDocumentCount }} / {{ activeBase.documentCount }}</strong></article>
            <article><small>{{ t('knowledgeBases.chunks') }}</small><strong>{{ activeBase.chunkCount }}</strong></article>
            <article><small>{{ t('knowledgeBases.dimension') }}</small><strong>{{ activeBase.embeddingDimension || '-' }}</strong></article>
            <article><small>{{ t('knowledgeBases.updatedAt') }}</small><strong>{{ formatDate(activeBase.updatedAt) }}</strong></article>
          </section>

          <section class="config-section">
            <div class="subsection-head"><div><h4>{{ t('knowledgeBases.configuration') }}</h4><p>{{ t('knowledgeBases.configurationHint') }}</p></div></div>
            <el-descriptions :column="detailColumns" border>
              <el-descriptions-item :label="t('common.code')">{{ activeBase.code }}</el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.storageType')">{{ storageLabel(activeBase.storageType) }}</el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.resourceMode')">{{ activeBase.resourceMode==='MANAGED'?t('knowledgeBases.managed'):t('knowledgeBases.existing') }}</el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.resourceName')"><code>{{ activeBase.resourceName }}</code></el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.vectorConnection')">{{ activeConnectionLabel }}</el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.embeddingModel')">{{ activeModelLabel }}</el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.distance')">{{ activeBase.distanceMetric }}</el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.chunkConfig')">{{ activeBase.chunkSize }} / {{ activeBase.chunkOverlap }}</el-descriptions-item>
              <el-descriptions-item :label="t('knowledgeBases.createdAt')">{{ formatDate(activeBase.createdAt) }}</el-descriptions-item>
              <el-descriptions-item v-if="auth.isAdmin" :label="t('knowledgeBases.owner')">#{{ activeBase.ownerUserId }}</el-descriptions-item>
            </el-descriptions>
          </section>

          <section class="documents-section">
            <div class="subsection-head"><div><h4>{{ t('knowledgeBases.documents') }}</h4><p>{{ t('knowledgeBases.documentsHint') }}</p></div><div class="document-actions">
              <input ref="fileInput" type="file" multiple hidden @change="chooseFiles">
              <el-button v-if="canMaintain(activeBase)&&auth.hasPermission('knowledge:base:update')" type="primary" :disabled="!activeBase.enabled" @click="fileInput?.click()">{{ t('knowledgeBases.upload') }}</el-button>
              <el-button v-if="canMaintain(activeBase)&&auth.hasPermission('knowledge:base:update')" type="danger" plain :disabled="!selectedDocuments.length" @click="removeSelectedDocuments">{{ t('knowledgeBases.deleteSelected', { count: selectedDocuments.length }) }}</el-button>
            </div></div>
            <el-alert v-if="!activeBase.enabled" class="document-notice" :title="t('knowledgeBases.disabledUploadHint')" type="warning" show-icon :closable="false" />
            <div class="document-filters"><el-input v-model="documentQuery.keyword" clearable :placeholder="t('knowledgeBases.documentSearch')" @keyup.enter="searchDocuments" @clear="searchDocuments"/><el-select v-model="documentQuery.status" clearable :placeholder="t('knowledgeBases.allDocumentStatuses')" @change="searchDocuments"><el-option v-for="status in documentStatuses" :key="status" :label="t(`knowledgeBases.documentStatuses.${status}`)" :value="status"/></el-select><el-button :loading="documentLoading" @click="loadDocuments">{{ t('common.refresh') }}</el-button></div>
            <el-table v-loading="documentLoading" :data="documents" table-layout="auto" empty-text=" " @selection-change="selectedDocuments=$event">
              <el-table-column v-if="canMaintain(activeBase)" type="selection" width="46" :selectable="()=>auth.hasPermission('knowledge:base:update')"/>
              <el-table-column prop="fileName" :label="t('knowledgeBases.fileName')" min-width="210" show-overflow-tooltip/>
              <el-table-column prop="contentType" :label="t('knowledgeBases.contentType')" min-width="150" show-overflow-tooltip/>
              <el-table-column :label="t('common.status')" width="105"><template #default="scope"><el-tag :type="documentStatusType(scope.row.status)">{{ t(`knowledgeBases.documentStatuses.${scope.row.status}`) }}</el-tag></template></el-table-column>
              <el-table-column prop="chunkCount" :label="t('knowledgeBases.chunks')" width="80"/>
              <el-table-column :label="t('knowledgeBases.updatedAt')" min-width="165"><template #default="scope">{{ formatDate(scope.row.updatedAt) }}</template></el-table-column>
              <el-table-column :label="t('knowledgeBases.failureReason')" min-width="180"><template #default="scope"><span v-if="scope.row.errorMessage" class="document-error">{{ t(documentErrorTranslationKey(scope.row.errorMessage)) }}</span><span v-else>-</span></template></el-table-column>
              <el-table-column v-if="canMaintain(activeBase)" :label="t('common.operation')" width="86" fixed="right"><template #default="scope"><el-button v-if="auth.hasPermission('knowledge:base:update')" link type="danger" @click="removeDocument(scope.row)">{{ t('common.delete') }}</el-button></template></el-table-column>
              <template #empty><el-empty :description="hasDocumentFilters?t('knowledgeBases.noDocumentResults'):t('knowledgeBases.noDocuments')" :image-size="64"/></template>
            </el-table>
            <el-pagination v-if="documentTotal>documentQuery.size" v-model:current-page="documentQuery.page" v-model:page-size="documentQuery.size" background :page-sizes="[20,50,100]" :total="documentTotal" layout="total, sizes, prev, pager, next" @size-change="resizeDocuments" @current-change="loadDocuments" />
          </section>
        </template>
      </main>
    </section>

    <el-dialog v-model="formVisible" :title="form.id?t('knowledgeBases.edit'):t('knowledgeBases.add')" width="min(860px,94vw)" destroy-on-close>
      <el-form ref="formRef" :model="form" :rules="formRules" label-position="top" status-icon>
        <div class="form-grid"><el-form-item :label="t('common.code')" prop="code"><el-input v-model="form.code" maxlength="80" show-word-limit/></el-form-item><el-form-item :label="t('common.name')" prop="name"><el-input v-model="form.name" maxlength="120" show-word-limit/></el-form-item></div>
        <el-form-item :label="t('common.description')" prop="description"><el-input v-model="form.description" type="textarea" :rows="3" maxlength="500" show-word-limit/></el-form-item>
        <el-alert v-if="immutableLocked" class="form-notice" :title="t('knowledgeBases.immutableHint')" type="warning" show-icon :closable="false"/>
        <el-alert v-if="!vectorConnections.length" class="form-notice" :title="t('knowledgeBases.noVectorConnections')" type="warning" show-icon :closable="false"/>
        <el-form-item :label="t('knowledgeBases.vectorConnection')" prop="connectionId"><el-select v-model="form.connectionId" filterable class="full" :disabled="immutableLocked"><el-option v-for="item in vectorConnections" :key="item.id" :label="`${item.name} (${item.connectionType}) · ${t(`workflowConnections.vectorStatuses.${item.vectorStatus}`)}`" :value="item.id"/></el-select></el-form-item>
        <el-alert v-if="!embeddingModels.length" class="form-notice" :title="t('knowledgeBases.noEmbeddingModels')" type="warning" show-icon :closable="false"/>
        <el-form-item :label="t('knowledgeBases.embeddingModel')" prop="embeddingModelId"><el-select v-model="form.embeddingModelId" filterable class="full" :disabled="immutableLocked"><el-option v-for="item in embeddingModels" :key="item.id" :label="`${item.name} · ${item.modelName}`" :value="item.id"/></el-select></el-form-item>
        <div class="form-grid"><el-form-item :label="t('knowledgeBases.resourceMode')" prop="resourceMode"><el-select v-model="form.resourceMode" :disabled="immutableLocked"><el-option :label="t('knowledgeBases.managed')" value="MANAGED"/><el-option :label="t('knowledgeBases.existing')" value="EXISTING"/></el-select></el-form-item><el-form-item v-if="form.resourceMode==='EXISTING'" :label="t('knowledgeBases.resourceName')" prop="resourceName"><el-input v-model="form.resourceName" :disabled="immutableLocked" maxlength="120"/></el-form-item></div>
        <div class="form-grid three"><el-form-item :label="t('knowledgeBases.distance')" prop="distanceMetric"><el-select v-model="form.distanceMetric" :disabled="immutableLocked"><el-option v-for="item in ['COSINE','L2','IP']" :key="item" :label="item" :value="item"/></el-select></el-form-item><el-form-item :label="t('knowledgeBases.chunkSize')" prop="chunkSize"><el-input-number v-model="form.chunkSize" :min="50" :max="500"/></el-form-item><el-form-item :label="t('knowledgeBases.chunkOverlap')" prop="chunkOverlap"><el-input-number v-model="form.chunkOverlap" :min="0" :max="499"/></el-form-item></div>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled"/></el-form-item>
      </el-form>
      <template #footer><el-button :disabled="saving" @click="formVisible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" :disabled="!vectorConnections.length||!embeddingModels.length" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="uploadVisible" :title="t('knowledgeBases.uploadTitle', { name: uploadBaseName })" width="min(760px,94vw)" :show-close="!uploading" :close-on-click-modal="false" :close-on-press-escape="!uploading">
      <el-progress :percentage="uploadSummary.percentage" :status="uploadProgressStatus"/>
      <p class="upload-summary">{{ t('knowledgeBases.uploadProgress', { completed: uploadSummary.completed, total: uploadSummary.total, success: uploadSummary.success, failed: uploadSummary.failed + uploadSummary.rejected }) }}</p>
      <el-table :data="uploadQueue" max-height="380"><el-table-column prop="name" :label="t('knowledgeBases.fileName')" min-width="220" show-overflow-tooltip/><el-table-column :label="t('knowledgeBases.fileSize')" width="100"><template #default="scope">{{ formatFileSize(scope.row.size) }}</template></el-table-column><el-table-column :label="t('common.status')" width="110"><template #default="scope"><el-tag :type="uploadStatusType(scope.row.status)">{{ t(`knowledgeBases.uploadStatuses.${scope.row.status}`) }}</el-tag></template></el-table-column><el-table-column :label="t('common.error')" min-width="180"><template #default="scope">{{ uploadItemError(scope.row) }}</template></el-table-column></el-table>
      <template #footer><el-button :disabled="uploading" @click="uploadVisible=false">{{ t('common.close') }}</el-button><el-button v-if="uploadSummary.failed" :disabled="uploading" @click="startUploads">{{ t('knowledgeBases.retryFailed') }}</el-button><el-button v-if="uploadSummary.pending" type="primary" :loading="uploading" @click="startUploads">{{ t('knowledgeBases.startUpload') }}</el-button></template>
    </el-dialog>

    <el-dialog v-model="batchResultVisible" :title="t('knowledgeBases.batchResult')" width="min(620px,94vw)"><p>{{ t('knowledgeBases.batchDeletePartial', { success: batchDeletedCount, failed: batchFailures.length }) }}</p><el-table :data="batchFailures"><el-table-column prop="fileName" :label="t('knowledgeBases.fileName')"/><el-table-column :label="t('knowledgeBases.failureReason')"><template #default="scope">{{ t(documentErrorTranslationKey(scope.row.messageKey)) }}</template></el-table-column></el-table><template #footer><el-button type="primary" @click="batchResultVisible=false">{{ t('common.close') }}</el-button></template></el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
import { createUploadQueue, documentErrorTranslationKey, documentStatusType, formatFileSize,
  runUploadQueue, uploadQueueSummary, uploadStatusType } from '../utils/knowledgeBaseManagement'

const { t, locale } = useI18n(), auth = useAuthStore()
const storageTypes = ['PGVECTOR', 'QDRANT', 'MILVUS', 'ELASTICSEARCH'], documentStatuses = ['READY', 'FAILED', 'INDEXING']
const rows = ref([]), total = ref(0), loading = ref(false), loadError = ref(false), activeBase = ref(null)
const summary = reactive({ totalBases: 0, enabledBases: 0, documentCount: 0, failedDocumentCount: 0 })
const query = reactive({ keyword: '', enabled: '', storageType: '', page: 1, size: 20 })
const connections = ref([]), models = ref([]), formVisible = ref(false), formRef = ref(null), saving = ref(false), toggling = ref(false)
const form = reactive(emptyForm()), editingDocumentCount = ref(0)
const documents = ref([]), documentTotal = ref(0), documentLoading = ref(false), selectedDocuments = ref([])
const documentQuery = reactive({ keyword: '', status: '', page: 1, size: 20 })
const fileInput = ref(null), uploadVisible = ref(false), uploading = ref(false), uploadQueue = ref([]), uploadBaseId = ref(null), uploadBaseName = ref('')
const batchResultVisible = ref(false), batchFailures = ref([]), batchDeletedCount = ref(0)
const viewportWidth = ref(window.innerWidth)
const vectorConnections = computed(() => connections.value.filter(item => ['POSTGRESQL', 'QDRANT', 'MILVUS', 'ELASTICSEARCH'].includes(item.connectionType) && item.vectorStatus === 'SUPPORTED'))
const embeddingModels = computed(() => models.value.filter(item => item.enabled && item.supportedModelTypes?.includes('embedding_model')))
const hasBaseFilters = computed(() => Boolean(query.keyword || query.enabled !== '' || query.storageType))
const hasDocumentFilters = computed(() => Boolean(documentQuery.keyword || documentQuery.status))
const immutableLocked = computed(() => Boolean(form.id && editingDocumentCount.value > 0))
const uploadSummary = computed(() => uploadQueueSummary(uploadQueue.value))
const uploadProgressStatus = computed(() => uploadSummary.value.failed || uploadSummary.value.rejected ? 'exception' : uploadSummary.value.success === uploadSummary.value.total ? 'success' : undefined)
const detailColumns = computed(() => viewportWidth.value < 760 ? 1 : 2)
const activeConnectionLabel = computed(() => { const item = connections.value.find(value => value.id === activeBase.value?.connectionId); return item ? `${item.name} (${item.connectionType})` : `#${activeBase.value?.connectionId || '-'}` })
const activeModelLabel = computed(() => { const item = models.value.find(value => value.id === activeBase.value?.embeddingModelId); return item ? `${item.name} · ${item.modelName}` : `#${activeBase.value?.embeddingModelId || '-'}` })
const formRules = computed(() => ({
  code: [{ required: true, message: t('knowledgeBases.validation.codeRequired'), trigger: 'blur' }, { pattern: /^[A-Za-z][A-Za-z0-9_-]{1,79}$/, message: t('knowledgeBases.validation.codeFormat'), trigger: 'blur' }],
  name: [{ required: true, message: t('knowledgeBases.validation.nameRequired'), trigger: 'blur' }],
  connectionId: [{ required: true, message: t('knowledgeBases.validation.connectionRequired'), trigger: 'change' }],
  embeddingModelId: [{ required: true, message: t('knowledgeBases.validation.modelRequired'), trigger: 'change' }],
  resourceName: [{ validator: (_rule, value, done) => form.resourceMode !== 'EXISTING' || /^[a-z][a-z0-9_]{2,119}$/.test(value || '') ? done() : done(new Error(t('knowledgeBases.validation.resourceName'))), trigger: 'blur' }],
  chunkSize: [{ validator: (_rule, value, done) => value >= 50 && value <= 500 ? done() : done(new Error(t('knowledgeBases.validation.chunkSize'))), trigger: 'change' }],
  chunkOverlap: [{ validator: (_rule, value, done) => value >= 0 && value < form.chunkSize ? done() : done(new Error(t('knowledgeBases.validation.chunkOverlap'))), trigger: 'change' }]
}))

/** 加载知识库分页、聚合统计并维持有效选中项。 */
async function loadBases(preferredId = activeBase.value?.id) {
  loading.value = true; loadError.value = false
  try {
    const { data } = await http.get('/knowledge-bases/management', { params: { ...query, enabled: query.enabled === '' ? undefined : query.enabled, storageType: query.storageType || undefined } })
    rows.value = (data.items || []).map(item => ({ ...item.knowledgeBase, documentCount: item.documentCount, readyDocumentCount: item.readyDocumentCount, failedDocumentCount: item.failedDocumentCount, chunkCount: item.chunkCount }))
    total.value = data.total || 0; Object.assign(summary, data.summary || { totalBases: 0, enabledBases: 0, documentCount: 0, failedDocumentCount: 0 })
    activeBase.value = rows.value.find(item => item.id === preferredId) || rows.value[0] || null
    if (activeBase.value) await loadDocuments(); else resetDocuments()
  } catch (error) { loadError.value = true; showHttpError(error, 'knowledgeBases.loadFailed') }
  finally { loading.value = false }
}

/** 加载创建和详情展示所需的连接与模型资源。 */
async function loadResources() {
  try { const [connectionRows, modelRows] = await Promise.all([http.get('/workflow/connections'), http.get('/models')]); connections.value = connectionRows.data || []; models.value = modelRows.data || [] }
  catch (error) { showHttpError(error, 'knowledgeBases.resourcesFailed') }
}

/** 重新应用知识库筛选并回到第一页。 */
function searchBases() { query.page = 1; loadBases(null) }
/** 选择知识库并加载第一页文档。 */
function selectBase(row) { activeBase.value = row; documentQuery.keyword = ''; documentQuery.status = ''; documentQuery.page = 1; loadDocuments() }

/** 加载当前知识库文档分页。 */
async function loadDocuments() {
  if (!activeBase.value) return resetDocuments(); const baseId = activeBase.value.id; documentLoading.value = true
  try { const { data } = await http.get(`/knowledge-bases/${baseId}/documents/page`, { params: { ...documentQuery, status: documentQuery.status || undefined } }); if (activeBase.value?.id === baseId) { documents.value = data.items || []; documentTotal.value = data.total || 0; selectedDocuments.value = [] } }
  catch (error) { showHttpError(error, 'knowledgeBases.documentsFailed') }
  finally { if (activeBase.value?.id === baseId) documentLoading.value = false }
}
/** 清空已离开知识库的文档状态。 */
function resetDocuments() { documents.value = []; documentTotal.value = 0; selectedDocuments.value = []; documentLoading.value = false }
/** 重新应用文档筛选并回到第一页。 */
function searchDocuments() { documentQuery.page = 1; loadDocuments() }
/** 切换文档页容量并从第一页重新加载。 */
function resizeDocuments() { documentQuery.page = 1; loadDocuments() }

/** 打开新增或编辑表单，并记录不可变配置锁定条件。 */
function openForm(row) { Object.assign(form, emptyForm(), row || {}); editingDocumentCount.value = row?.documentCount || 0; formVisible.value = true }
/** 校验并保存知识库，成功后刷新并选中新记录。 */
async function save() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    const response = form.id ? await http.put(`/knowledge-bases/${form.id}`, form) : await http.post('/knowledge-bases', form)
    if (!form.id) { query.keyword = ''; query.enabled = ''; query.storageType = ''; query.page = 1 }
    formVisible.value = false; await loadBases(response.data.id); ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
  finally { saving.value = false }
}

/** 快速切换拥有者知识库状态。 */
async function toggleEnabled(enabled) {
  toggling.value = true
  try { await http.patch(`/knowledge-bases/${activeBase.value.id}/enabled`, { enabled }); await loadBases(activeBase.value.id); ElMessage.success(t('knowledgeBases.statusUpdated')) }
  catch (error) { showHttpError(error) }
  finally { toggling.value = false }
}
/** 删除知识库及平台管理的外部资源。 */
async function removeBase(row) { try { await ElMessageBox.confirm(t('knowledgeBases.deleteBaseConfirm', { name: row.name }), t('common.deleteConfirm'), { type: 'warning' }); await http.delete(`/knowledge-bases/${row.id}`); await loadBases(null); ElMessage.success(t('common.successDeleted')) } catch (error) { if (!['cancel', 'close'].includes(error)) showHttpError(error) } }

/** 选择最多二十个文档并建立可重试上传队列。 */
function chooseFiles(event) { const files = event.target.files; event.target.value = ''; if (!files?.length) return; uploadQueue.value = createUploadQueue(files); uploadBaseId.value = activeBase.value.id; uploadBaseName.value = activeBase.value.name; uploadVisible.value = true }
/** 顺序上传当前队列，并保留逐文件结果供失败重试。 */
async function startUploads() {
  uploading.value = true
  const result = await runUploadQueue(uploadQueue.value, async item => { const data = new FormData(); data.append('file', item.file); await http.post(`/knowledge-bases/${uploadBaseId.value}/documents`, data, { silentError: true }) }, state => { uploadQueue.value = state })
  uploading.value = false; await loadBases(activeBase.value?.id)
  if (result.failed || result.rejected) ElMessage.warning(t('knowledgeBases.uploadCompletedWithFailures', { success: result.success, failed: result.failed + result.rejected }))
  else if (result.success) ElMessage.success(t('knowledgeBases.uploadCompleted', { count: result.success }))
}
/** 返回队列拒绝原因或本地化接口错误。 */
function uploadItemError(item) { if (item.rejection) return t(`knowledgeBases.uploadRejections.${item.rejection}`); return item.error?.response?.data?.message || item.error?.message || '' }

/** 删除单个文档及其向量。 */
async function removeDocument(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.fileName }), t('common.deleteConfirm')); await http.delete(`/knowledge-bases/${activeBase.value.id}/documents/${row.id}`); await loadBases(activeBase.value.id); ElMessage.success(t('common.successDeleted')) } catch (error) { if (!['cancel', 'close'].includes(error)) showHttpError(error) } }
/** 批量删除选中文档并展示逐项失败。 */
async function removeSelectedDocuments() {
  const selected = [...selectedDocuments.value]
  try {
    await ElMessageBox.confirm(t('knowledgeBases.batchDeleteConfirm', { count: selected.length }), t('common.deleteConfirm'), { type: 'warning' })
    const { data } = await http.post(`/knowledge-bases/${activeBase.value.id}/documents/batch-delete`, { documentIds: selected.map(item => item.id) })
    batchDeletedCount.value = data.deletedIds?.length || 0; batchFailures.value = (data.failures || []).map(failure => ({ ...failure, fileName: selected.find(item => item.id === failure.documentId)?.fileName || `#${failure.documentId}` }))
    await loadBases(activeBase.value.id)
    if (batchFailures.value.length) batchResultVisible.value = true
    else ElMessage.success(t('knowledgeBases.batchDeleted', { count: batchDeletedCount.value }))
  } catch (error) { if (!['cancel', 'close'].includes(error)) showHttpError(error) }
}

/** 当前用户必须拥有记录且具备更新权限才能执行维护操作。 */
function canMaintain(row) { return Boolean(row && Number(row.ownerUserId) === Number(auth.user?.id)) }
/** 本地化向量存储类型。 */
function storageLabel(type) { return t(`knowledgeBases.storageTypes.${type}`) }
/** 按当前语言展示稳定日期时间。 */
function formatDate(value) { if (!value) return '-'; const date = new Date(value); return Number.isNaN(date.getTime()) ? '-' : new Intl.DateTimeFormat(locale.value === 'zh-CN' ? 'zh-CN' : 'en-US', { dateStyle: 'medium', timeStyle: 'short' }).format(date) }
/** 同步视口宽度，使描述列表在窗口缩放后保持响应式列数。 */
function updateViewportWidth() { viewportWidth.value = window.innerWidth }
/** 创建稳定默认配置。 */
function emptyForm() { return { id: null, code: '', name: '', description: '', connectionId: null, resourceMode: 'MANAGED', resourceName: '', embeddingModelId: null, distanceMetric: 'COSINE', chunkSize: 400, chunkOverlap: 60, enabled: true } }

onMounted(() => { window.addEventListener('resize', updateViewportWidth); Promise.all([loadResources(), loadBases()]) })
onBeforeUnmount(() => window.removeEventListener('resize', updateViewportWidth))
</script>

<style scoped>
.knowledge-page{display:grid;gap:18px}.knowledge-hero,.detail-head,.subsection-head,.directory-head{display:flex;align-items:flex-start;justify-content:space-between;gap:16px}.knowledge-hero h2,.knowledge-hero p,.detail-head h3,.detail-head p,.subsection-head h4,.subsection-head p,.directory-head h3{margin:0}.knowledge-hero p,.detail-head p,.subsection-head p{margin-top:6px;color:var(--app-muted);line-height:1.55}.knowledge-summary{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:14px}.knowledge-summary article,.detail-overview article{display:grid;gap:7px;padding:16px 18px;border:1px solid var(--app-border);border-radius:12px;background:#fff}.knowledge-summary small,.detail-overview small,.directory-head small{color:var(--app-muted)}.knowledge-summary strong{font-size:26px}.knowledge-summary .summary-danger{border-color:#f5c2c0;background:#fff8f7;color:#c43b37}.knowledge-workspace{display:grid;grid-template-columns:minmax(270px,320px) minmax(0,1fr);gap:18px;min-height:640px}.knowledge-directory,.knowledge-detail{min-width:0;padding:20px}.knowledge-directory{display:flex;min-height:0;flex-direction:column;gap:14px}.directory-filters{display:grid;gap:10px}.directory-filters>div{display:grid;grid-template-columns:1fr 1fr;gap:8px}.directory-state{padding:10px 0}.directory-list{display:grid;align-content:start;gap:8px;max-height:650px;overflow:auto;padding-right:4px}.directory-item{display:grid;width:100%;gap:7px;padding:13px;border:1px solid var(--app-border);border-radius:10px;background:#fff;color:var(--app-text);text-align:left;cursor:pointer;transition:.16s ease}.directory-item:hover{border-color:#b8caee;background:#f8faff}.directory-item:focus-visible{outline:2px solid var(--app-primary);outline-offset:1px}.directory-item.active{border-color:#92afe8;background:#eef4ff;box-shadow:0 0 0 1px #dbe7ff inset}.directory-title,.directory-meta{display:flex;align-items:center;justify-content:space-between;gap:8px}.directory-title strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.directory-item code{color:#59677c;font-size:11px}.directory-meta{color:var(--app-muted);font-size:12px}.directory-error,.document-error{color:#c43b37;font-size:12px}.knowledge-detail{overflow:hidden}.detail-title{display:flex;align-items:center;flex-wrap:wrap;gap:9px}.detail-actions,.document-actions{display:flex;align-items:center;flex-wrap:wrap;gap:8px}.detail-overview{display:grid;grid-template-columns:repeat(4,minmax(0,1fr));gap:10px;margin-top:20px}.detail-overview article{padding:13px}.detail-overview strong{font-size:17px;overflow-wrap:anywhere}.config-section,.documents-section{margin-top:24px;padding-top:22px;border-top:1px solid var(--app-border)}.subsection-head{margin-bottom:15px}.document-notice,.form-notice{margin-bottom:14px}.document-filters{display:grid;grid-template-columns:minmax(180px,1fr) 190px auto;gap:9px;margin-bottom:12px}.documents-section :deep(.el-pagination){margin-top:14px;justify-content:flex-end}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-grid.three{grid-template-columns:1fr 1fr 1fr}.full{width:100%}.upload-summary{color:var(--app-muted);font-size:13px}@media(max-width:1100px){.knowledge-workspace{grid-template-columns:280px minmax(0,1fr)}.detail-overview{grid-template-columns:repeat(2,minmax(0,1fr))}}@media(max-width:820px){.knowledge-summary{grid-template-columns:repeat(2,minmax(0,1fr))}.knowledge-workspace{grid-template-columns:1fr}.directory-list{max-height:360px}.detail-head,.subsection-head{align-items:stretch;flex-direction:column}.document-filters{grid-template-columns:1fr 1fr}.document-filters .el-button{grid-column:1/-1}.detail-actions,.document-actions{justify-content:flex-start}}@media(max-width:600px){.knowledge-summary,.detail-overview,.form-grid,.form-grid.three,.directory-filters>div,.document-filters{grid-template-columns:1fr}.knowledge-hero{align-items:stretch;flex-direction:column}.knowledge-summary article{padding:13px}.knowledge-directory,.knowledge-detail{padding:15px}.document-filters .el-button{grid-column:auto}}
</style>
