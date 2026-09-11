<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('dataSources.title') }}</h2><p>{{ t('dataSources.description') }}</p></div>
      <div class="head-actions">
        <div class="auto-detect" v-if="auth.hasPermission('operations:data-source:test')">
          <el-switch v-model="autoDetect" size="small" />
          <span class="auto-detect-label">{{ t('dataSources.autoDetect') }}</span>
          <el-select v-if="autoDetect" v-model="autoDetectInterval" size="small" class="auto-detect-interval">
            <el-option v-for="option in AUTO_DETECT_INTERVALS" :key="option" :label="`${option}s`" :value="option" />
          </el-select>
        </div>
        <el-button v-if="auth.hasPermission('operations:data-source:test')" @click="testAll" :loading="batchTesting">
          {{ t('dataSources.detectAll') }}
        </el-button>
        <el-button v-if="auth.hasPermission('operations:data-source:create')" type="primary" @click="open()">{{ t('dataSources.add') }}</el-button>
      </div>
    </div>
    <el-alert :title="t('workflowConnections.securityNotice')" type="warning" show-icon :closable="false" />
    <el-empty v-if="!rows.length" :description="t('dataSources.empty')" />
    <section v-for="group in groupedRows" :key="group.key" class="ds-group">
      <div class="ds-group-head">
        <span class="ds-group-icon" :style="categoryStyle(group.key)"><DataSourceTypeIcon :category="group.key" /></span>
        <h3>{{ categoryLabel(group.key) }}</h3>
        <el-tag size="small" type="info" effect="plain">{{ group.items.length }}</el-tag>
      </div>
      <div class="ds-grid">
        <article v-for="row in group.items" :key="row.id" class="ds-card" :class="{ 'ds-card--disabled': !row.enabled }">
          <div class="ds-card-top">
            <span class="ds-logo" :style="typeStyle(row.connectionType)">
              <DataSourceTypeIcon :type="row.connectionType" />
            </span>
            <div class="ds-titles">
              <strong>{{ row.name }}</strong>
              <small>{{ row.code }}</small>
            </div>
            <el-tag class="connection-tag" :style="typeStyle(row.connectionType)">
              {{ typeLabel(row.connectionType) }}
            </el-tag>
          </div>
          <div class="ds-status-line">
            <span class="ds-status" :class="`ds-status--${statusKind(row)}`">
              <span class="ds-dot" />{{ statusText(row) }}
            </span>
            <span v-if="row.lastTestLatencyMs !== null && row.lastTestLatencyMs !== undefined" class="ds-latency">
              {{ row.lastTestLatencyMs }}ms
            </span>
          </div>
          <div class="ds-meta-line">
            <small v-if="row.lastTestAt">{{ t('dataSources.lastTestAt') }}: {{ formatTime(row.lastTestAt) }}</small>
            <small v-else class="ds-muted">{{ t('dataSources.notTested') }}</small>
          </div>
          <div class="ds-tags">
            <el-tag size="small" :type="vectorStatusType(row.vectorStatus)">
              {{ t(`workflowConnections.vectorStatuses.${row.vectorStatus || 'UNKNOWN'}`) }}
            </el-tag>
            <el-tag v-if="!row.enabled" size="small" type="info">{{ t('common.disabled') }}</el-tag>
          </div>
          <div class="ds-actions">
            <el-button v-if="auth.hasPermission('operations:data-source:test')" link type="success"
                       :loading="testingId === row.id" @click="test(row)">{{ t('dataSources.test') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-source:test')" link type="info" @click="openStatus(row)">
              {{ t('dataSources.statusTitle') }}
            </el-button>
            <el-button v-if="row.connectionType === 'PLUGIN' && auth.hasPermission('operations:data-source:update')"
                       link type="warning" @click="oauth(row)">{{ t('dataSources.oauth') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-source:update')" link type="primary" @click="open(row)">
              {{ t('common.edit') }}</el-button>
            <el-button v-if="auth.hasPermission('operations:data-source:delete')" link type="danger" @click="remove(row)">
              {{ t('common.delete') }}</el-button>
          </div>
        </article>
      </div>
    </section>

    <el-drawer v-model="statusVisible" :title="statusRow ? `${statusRow.name} · ${t('dataSources.statusTitle')}` : ''" size="380px">
      <div v-loading="statusLoading" class="status-body">
        <template v-if="statusData">
          <div class="status-hero" :class="`ds-status--${statusData.connected ? 'ok' : 'bad'}`">
            <span class="ds-dot" />
            <strong>{{ statusData.connected ? t('dataSources.connected') : t('dataSources.statusFailed') }}</strong>
            <span class="status-latency">{{ statusData.latencyMs }}ms</span>
          </div>
          <div class="status-metrics">
            <div v-for="entry in statusEntries" :key="entry.label" class="status-metric">
              <small>{{ entry.label }}</small><strong>{{ entry.value }}</strong>
            </div>
          </div>
          <el-alert v-if="statusData.vectorSupported === false && isVectorType" :title="t('dataSources.vectorUnsupported')"
                    type="warning" show-icon :closable="false" />
        </template>
      </div>
    </el-drawer>

    <el-dialog v-model="visible" :title="form.id ? t('dataSources.edit') : t('dataSources.add')" width="min(1120px, 96vw)">
      <el-form label-position="top" class="data-source-editor">
        <div class="connection-editor-layout">
          <aside class="connection-picker">
            <div class="connection-picker-title">{{ t('workflowConnections.category') }}</div>
            <nav class="connection-category-nav" :aria-label="t('workflowConnections.category')">
              <button v-for="category in connectionCategories" :key="category.key" type="button"
                      class="connection-category-option" :class="{ active: form.connectionCategory === category.key }"
                      :aria-pressed="form.connectionCategory === category.key" @click="selectCategory(category.key)">
                <span class="connection-nav-icon" :style="categoryStyle(category.key)">
                  <DataSourceTypeIcon :category="category.key" />
                </span>
                <span class="connection-nav-copy"><strong>{{ categoryLabel(category.key) }}</strong><small>{{ category.types.length }}</small></span>
                <span v-if="form.connectionCategory === category.key" class="connection-nav-check" aria-hidden="true">✓</span>
              </button>
            </nav>
            <div v-if="form.connectionCategory" class="connection-type-nav">
              <div class="connection-picker-title">{{ t('workflowConnections.connectionType') }}</div>
              <div class="connection-type-options">
                <button v-for="type in availableConnectionTypes" :key="type" type="button"
                        class="connection-type-option" :class="{ active: form.connectionType === type }"
                        :aria-pressed="form.connectionType === type" @click="selectConnectionType(type)">
                  <span class="connection-nav-icon" :style="typeStyle(type)"><DataSourceTypeIcon :type="type" /></span>
                  <strong>{{ typeLabel(type) }}</strong>
                  <span v-if="form.connectionType === type" class="connection-nav-check" aria-hidden="true">✓</span>
                </button>
              </div>
            </div>
          </aside>

          <main class="connection-form-pane">
            <div v-if="form.connectionType" class="connection-selection-head">
              <span class="connection-selection-icon" :style="typeStyle(form.connectionType)">
                <DataSourceTypeIcon :type="form.connectionType" />
              </span>
              <div class="connection-selection-copy">
                <small>{{ categoryLabel(form.connectionCategory) }}</small>
                <strong>{{ typeLabel(form.connectionType) }}</strong>
                <p>{{ connectionTypeGuide(form.connectionType) }}</p>
              </div>
            </div>
            <div v-else class="connection-selection-empty">
              <strong>{{ t('workflowConnections.selectCategory') }}</strong>
              <small>{{ t('workflowConnections.configHelp') }}</small>
            </div>

            <section class="connection-form-section connection-identity-section">
              <div class="connection-grid connection-grid--identity">
                <el-form-item :label="t('common.code')">
                  <div class="connection-input-stack">
                    <el-input v-model="form.code" :placeholder="t('workflowConnections.codePlaceholder')" @blur="normalizeCode" />
                    <small>{{ t('workflowConnections.codeHelp') }}</small>
                  </div>
                </el-form-item>
                <el-form-item :label="t('common.name')">
                  <el-input v-model="form.name" :placeholder="t('workflowConnections.namePlaceholder')" />
                </el-form-item>
                <el-form-item :label="t('common.status')">
                  <div class="connection-status-control"><el-switch v-model="form.enabled" /><span>{{ t(form.enabled ? 'common.enabled' : 'common.disabled') }}</span></div>
                </el-form-item>
              </div>
            </section>

            <section v-if="form.connectionType" class="connection-form-section connection-config-section">
              <div class="connection-config-head"><div><h3>{{ t('workflowConnections.config') }}</h3><p>{{ t('workflowConnections.configHelp') }}</p></div></div>
              <el-form-item v-if="form.connectionType === 'PLUGIN'" :label="t('workflowConnections.pluginComponent')" class="connection-plugin-select">
                <el-select :model-value="form.config.pluginComponentId" class="full" filterable @update:model-value="selectPluginComponent">
                  <el-option v-for="component in pluginComponents" :key="component.id" :value="component.id" :label="pluginComponentLabel(component)" />
                </el-select>
              </el-form-item>
              <div v-if="configFieldGroups.length" class="connection-config-groups">
                <section v-for="group in configFieldGroups" :key="group.key" class="connection-config-group">
                  <div class="connection-config-group-head">
                    <strong>{{ configGroupLabel(group.key) }}</strong>
                    <small>{{ configGroupDescription(group.key) }}</small>
                  </div>
                  <div class="connection-config-grid">
                    <div v-for="field in group.fields" :key="field.key" class="connection-config-field"
                         :class="{ 'connection-config-field--wide': field.wide || field.editor === 'keyValue' }">
                      <div class="connection-field-label">
                        <strong>{{ fieldLabel(field.key) }}</strong>
                        <span class="connection-field-requirement" :class="{ required: fieldRequired(field) }">
                          {{ fieldRequirementLabel(field) }}
                        </span>
                      </div>
                      <div class="connection-card-body">
                        <el-input v-if="['text', 'password'].includes(field.editor)" :model-value="configFieldValue(field.key)"
                                  :type="field.editor" :show-password="field.editor === 'password'" autocomplete="off"
                                  :placeholder="fieldPlaceholder(field)" @update:model-value="setConfigField(field.key, $event)" />
                        <div v-else-if="field.editor === 'boolean'" class="connection-boolean-control" :class="{ 'connection-boolean-control--risk': field.risk }">
                          <el-switch :model-value="configFieldValue(field.key)" @update:model-value="setConfigField(field.key, $event)" />
                          <span>{{ t(configFieldValue(field.key) ? 'common.enabled' : 'common.disabled') }}</span>
                          <small v-if="field.risk">{{ t('workflowConnections.sensitiveOption') }}</small>
                        </div>
                        <el-select v-else-if="field.editor === 'select'" :model-value="configFieldValue(field.key)" class="full"
                                   @update:model-value="setConfigField(field.key, $event)">
                          <el-option v-for="option in field.options" :key="metadataOptionValue(option) || '__empty'"
                                     :label="connectionOptionLabel(option)" :value="metadataOptionValue(option)" />
                        </el-select>
                        <template v-else-if="field.editor === 'keyValue'">
                          <div class="connection-key-values">
                            <div v-for="([key, value]) in mapEntries(field.key)" :key="key" class="connection-key-value">
                              <strong>{{ key }}</strong>
                              <el-input :model-value="value" @update:model-value="setMapValue(field.key, key, $event)" />
                              <el-button link type="danger" @click="removeMapValue(field.key, key)">{{ t('common.delete') }}</el-button>
                            </div>
                            <div class="connection-key-value connection-key-value--add">
                              <el-input v-model="mapDraft.key" :placeholder="t('workflowConnections.customKey')" />
                              <el-input v-model="mapDraft.value" :placeholder="t('workflowConnections.customValue')" @keyup.enter="addMapValue(field.key)" />
                              <el-button type="primary" plain @click="addMapValue(field.key)">{{ t('common.add') }}</el-button>
                            </div>
                            <small v-if="mapError" class="connection-error">{{ mapError }}</small>
                          </div>
                        </template>
                      </div>
                      <small class="connection-field-help">{{ fieldDescription(field.key) }}</small>
                    </div>
                  </div>
                </section>
              </div>
              <div class="form-help">{{ t('workflowConnections.maskHelp') }}</div>
            </section>

            <details v-if="form.connectionType" class="connection-custom-section" :open="customKeys.length > 0">
              <summary><div><h3>{{ t('workflowConnections.customTitle') }}</h3><p>{{ t('workflowConnections.customHelp') }}</p></div><el-tag type="info">{{ customKeys.length }}</el-tag></summary>
              <div class="connection-custom-list">
                <article v-for="key in customKeys" :key="key" class="connection-custom-card">
                  <div class="connection-custom-head"><strong>{{ key }}</strong><el-button link type="danger" @click="removeCustomField(key)">{{ t('common.delete') }}</el-button></div>
                  <WorkflowConfigValueEditor :model-value="form.config[key]" @update:model-value="setConfigField(key, $event)" />
                </article>
                <div class="connection-custom-add">
                  <el-input v-model="customKey" :placeholder="t('workflowConnections.customKey')" @keyup.enter="addCustomField" />
                  <el-select v-model="customType"><el-option v-for="type in CONFIG_VALUE_TYPES" :key="type" :label="t(`workflowConfig.valueTypes.${type}`)" :value="type" /></el-select>
                  <el-button type="primary" plain @click="addCustomField">{{ t('workflowConnections.addCustom') }}</el-button>
                </div>
                <small v-if="customError" class="connection-error">{{ customError }}</small>
              </div>
            </details>
          </main>
        </div>
      </el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'
import DataSourceTypeIcon from '../components/DataSourceTypeIcon.vue'
import WorkflowConfigValueEditor from '../components/WorkflowConfigValueEditor.vue'
import { useAuthStore } from '../stores/auth'
import { CONFIG_VALUE_TYPES, createConfigValue, isSafeConfigKey } from '../utils/workflowNodeConfig'
import { localizedMetadataOptionText, localizedMetadataText, metadataOptionValue } from '../utils/workflowTemplateCatalog'
import { cloneConnectionConfig, CONNECTION_CATEGORIES, CONNECTION_CONFIG_GROUPS, connectionCategoriesForType,
  connectionCategoryStyle, connectionConfigFields, connectionTypesForCategory, connectionTypeStyle,
  createConnectionConfig, extraConnectionConfigKeys, isConnectionConfigFieldRequired,
  missingConnectionConfigFields } from '../utils/workflowConnectionConfig'

const { t, te, locale } = useI18n()
const auth = useAuthStore()
const rows = ref([]), visible = ref(false)
const pluginComponents = ref([])
const connectionCategories = CONNECTION_CATEGORIES
const form = reactive(emptyForm())
const mapDraft = reactive({ key: '', value: '' })
const mapError = ref(''), customKey = ref(''), customType = ref('string'), customError = ref('')
const testingId = ref(null), batchTesting = ref(false)
const statusVisible = ref(false), statusLoading = ref(false), statusRow = ref(null), statusData = ref(null)
const AUTO_DETECT_KEY = 'dataSources.autoDetect', AUTO_DETECT_INTERVAL_KEY = 'dataSources.autoDetectInterval'
const AUTO_DETECT_INTERVALS = [30, 60, 120]
/** 自动检测默认关闭，用户可切换为按固定间隔轮询。 */
const autoDetect = ref(localStorage.getItem(AUTO_DETECT_KEY) === 'true')
const autoDetectInterval = ref(Number(localStorage.getItem(AUTO_DETECT_INTERVAL_KEY)) || 30)
let autoDetectTimer = null
const selectedPluginComponent = computed(() => pluginComponents.value.find(item => item.id === Number(form.config.pluginComponentId)))
const configFields = computed(() => form.connectionType === 'PLUGIN' ? pluginCredentialFields(selectedPluginComponent.value)
  : connectionConfigFields(form.connectionType))
/** 按固定语义顺序整理参数字段，空分组不占据表单空间。 */
const configFieldGroups = computed(() => CONNECTION_CONFIG_GROUPS.map(key => ({
  key, fields: configFields.value.filter(field => (field.group || 'AUTH') === key)
})).filter(group => group.fields.length))
const customKeys = computed(() => extraConnectionConfigKeys(form.config, form.connectionType))
const availableConnectionTypes = computed(() => connectionTypesForCategory(form.connectionCategory))
/** 按首选分类分组并保持分类定义顺序，空分类不展示。 */
const groupedRows = computed(() => CONNECTION_CATEGORIES.map(category => ({
  key: category.key, items: rows.value.filter(row => preferredCategory(row.connectionType) === category.key)
})).filter(group => group.items.length))
/** 状态详情抽屉中的指标条目，过滤空值并本地化已知键。 */
const statusEntries = computed(() => {
  const info = statusData.value?.info || {}
  return Object.entries(info).filter(([, value]) => value !== null && value !== undefined && value !== '')
    .map(([key, value]) => {
      const path = `dataSources.infoLabels.${key}`
      return { label: te(path) ? t(path) : key, value: String(value) }
    })
})
/** 当前抽屉中的数据源是否为向量类型，用于展示向量能力告警。 */
const isVectorType = computed(() => statusData.value?.vectorSupported !== undefined
  && ['POSTGRESQL', 'QDRANT', 'MILVUS', 'ELASTICSEARCH'].includes(statusData.value?.connectionType))

/** 加载当前用户可见的脱敏连接。 */
async function load() {
  const [connections, components] = await Promise.all([
    http.get('/data-sources'), http.get('/data-sources/plugin-component-options')
  ])
  rows.value = connections.data || []; pluginComponents.value = components.data || []
}
/** 打开连接编辑器并保留服务端脱敏占位符。 */
function open(row) {
  const connectionType = row?.connectionType || ''
  const connectionCategory = connectionType ? preferredCategory(connectionType) : ''
  Object.assign(form, emptyForm(), row || {}, {
    connectionCategory, connectionType, config: connectionType ? createConnectionConfig(connectionType, row?.config) : {}
  })
  clearDrafts(); visible.value = true
}
/** 切换左侧分类时选择该分类的首个连接类型并重置配置。 */
function selectCategory(category) {
  if (form.connectionCategory === category) return
  form.connectionCategory = category
  const [connectionType = ''] = connectionTypesForCategory(category)
  form.connectionType = connectionType; resetConfig(connectionType)
}
/** 切换左侧连接类型，同时避免重复点击清空已填写配置。 */
function selectConnectionType(type) {
  if (form.connectionType === type) return
  form.connectionType = type; resetConfig(type)
}
/** 切换类型时使用安全默认配置。 */
function resetConfig(type) { form.config = type === 'PLUGIN' ? { pluginComponentId: null, credentials: {} } : createConnectionConfig(type); clearDrafts() }
/** 创建或更新结构化连接配置。 */
async function save() {
  normalizeCode()
  if (!form.code || !form.name.trim() || !form.connectionCategory || !form.connectionType
    || form.connectionType === 'PLUGIN' && !selectedPluginComponent.value) return ElMessage.warning(t('workflowConnections.required'))
  if (!form.code.match(/^[A-Z][A-Z0-9_-]{1,79}$/)) return ElMessage.warning(t('workflowConnections.codeInvalid'))
  const missingFields = form.connectionType === 'PLUGIN'
    ? configFields.value.filter(field => field.required && emptyConfigValue(configFieldValue(field.key))).map(field => field.key)
    : missingConnectionConfigFields(form.connectionType, form.config)
  if (missingFields.length) return ElMessage.warning(t('workflowConnections.configRequired', {
    fields: missingFields.map(fieldLabel).join(t('workflowConnections.fieldSeparator'))
  }))
  const command = { code: form.code, name: form.name, connectionType: form.connectionType, config: cloneConnectionConfig(form.config), enabled: form.enabled }
  try {
    if (form.id) await http.put(`/data-sources/${form.id}`, command)
    else await http.post('/data-sources', command)
    visible.value = false; await load(); ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
}
/** 执行单条连通性测试并刷新卡片上的最近检测状态。 */
async function test(row) {
  testingId.value = row.id
  try {
    const { data } = await http.post(`/data-sources/${row.id}/test`)
    await load()
    data.vectorSupported === false && ['POSTGRESQL','QDRANT','MILVUS','ELASTICSEARCH'].includes(row.connectionType)
      ? ElMessage.warning(t('dataSources.vectorUnsupported')) : ElMessage.success(t('dataSources.connected'))
  } catch (error) { await load(); showHttpError(error, 'dataSources.testFailed') }
  finally { testingId.value = null }
}
/** 批量检测全部数据源，供手动“全部检测”和自动轮询复用。 */
async function testAll() {
  if (batchTesting.value || !auth.hasPermission('operations:data-source:test')) return
  batchTesting.value = true
  try {
    for (const row of rows.value) {
      try { await http.post(`/data-sources/${row.id}/test`) } catch (ignored) { /* 单条失败由最近状态记录呈现。 */ }
    }
    await load()
  } finally { batchTesting.value = false }
}
/** 打开状态抽屉并执行一次实时探测。 */
async function openStatus(row) {
  statusRow.value = row; statusData.value = null; statusVisible.value = true; statusLoading.value = true
  try {
    const { data } = await http.get(`/data-sources/${row.id}/status`)
    statusData.value = data
  } catch (error) { showHttpError(error, 'dataSources.testFailed'); statusVisible.value = false }
  finally { statusLoading.value = false }
}
/** 映射卡片健康状态样式：正常、异常、未检测或已停用。 */
function statusKind(row) {
  if (!row.enabled) return 'off'
  if (row.lastTestOk === true) return 'ok'
  if (row.lastTestOk === false) return 'bad'
  return 'unknown'
}
/** 返回卡片健康状态文案。 */
function statusText(row) {
  const kind = statusKind(row)
  return kind === 'off' ? t('common.disabled') : kind === 'ok' ? t('dataSources.statusOk')
    : kind === 'bad' ? t('dataSources.statusFailed') : t('dataSources.statusUnknown')
}
/** 本地化最近检测时间。 */
function formatTime(value) { return new Date(value).toLocaleString() }
/** 启动或停止自动检测定时器，配置变化即时生效。 */
watch([autoDetect, autoDetectInterval], () => {
  localStorage.setItem(AUTO_DETECT_KEY, String(autoDetect.value))
  localStorage.setItem(AUTO_DETECT_INTERVAL_KEY, String(autoDetectInterval.value))
  clearInterval(autoDetectTimer)
  if (autoDetect.value) autoDetectTimer = setInterval(() => { if (!document.hidden) testAll() }, autoDetectInterval.value * 1000)
})
onBeforeUnmount(() => clearInterval(autoDetectTimer))
/** 启动插件提供的 OAuth 生命周期并跳转到经过后端校验的授权地址。 */
async function oauth(row) {
  try {
    const redirectUri = `${window.location.origin}${window.location.pathname}`
    const { data } = await http.post(`/data-sources/${row.id}/oauth/authorize`, { redirectUri })
    window.location.assign(data.authorizationUrl)
  } catch (error) { showHttpError(error, 'dataSources.oauthFailed') }
}
/** 在连接页面消费授权方回传的一次性 code/state，并清理浏览器地址。 */
async function completeOAuthCallback() {
  const query = new URLSearchParams(window.location.search)
  const code = query.get('code'), state = query.get('state')
  if (!code || !state) return
  try {
    await http.post('/data-sources/plugin-oauth/callback', { code, state })
    ElMessage.success(t('dataSources.oauthConnected'))
  } catch (error) { showHttpError(error, 'dataSources.oauthFailed') }
  finally { window.history.replaceState({}, '', window.location.pathname) }
}
/** 将向量能力状态映射为稳定标签颜色。 */
function vectorStatusType(status) { return status === 'SUPPORTED' ? 'success' : status === 'UNSUPPORTED' ? 'danger' : 'info' }
/** 返回连接类型的首选分类，兼容没有分类字段的历史连接。 */
function preferredCategory(connectionType) { return connectionCategoriesForType(connectionType)[0] || 'OTHER' }
/** 返回本地化分类名称。 */
function categoryLabel(category) { return t(`workflowConnections.categories.${category || 'OTHER'}`) }
/** 返回本地化连接类型名称。 */
function typeLabel(type) { return t(`workflowConnections.types.${type || 'PLUGIN'}`) }
/** 返回当前连接类型的用途和关键配置提示。 */
function connectionTypeGuide(type) { return t(`workflowConnections.typeGuides.${type || 'PLUGIN'}`) }
/** 返回参数分组标题。 */
function configGroupLabel(group) { return t(`workflowConnections.configGroups.${group}.label`) }
/** 返回参数分组说明。 */
function configGroupDescription(group) { return t(`workflowConnections.configGroups.${group}.description`) }
/** 返回分类标签色板。 */
function categoryStyle(category) { return connectionCategoryStyle(category) }
/** 返回连接类型独立于分类的常规品牌样式。 */
function typeStyle(type) { return connectionTypeStyle(type) }
/** 删除未被工作流引用的连接。 */
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm')); await http.delete(`/data-sources/${row.id}`); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
/** 返回当前语言下的连接字段名称。 */
function fieldLabel(key) {
  const dynamic = configFields.value.find(field => field.key === key)?.label
  if (dynamic) return dynamic
  const path = `workflowConnections.fields.${key}`; return te(path) ? t(path) : key
}
/** 返回当前语言下的连接字段说明。 */
function fieldDescription(key) {
  const dynamic = configFields.value.find(field => field.key === key)?.description
  if (dynamic) return dynamic
  const path = `workflowConnections.fieldDescriptions.${key}`; return te(path) ? t(path) : key
}
/** 返回字段的示例占位，不把示例值写入实际配置。 */
function fieldPlaceholder(field) {
  return field.placeholder || t('workflowConnections.fieldPlaceholder', { field: fieldLabel(field.key) })
}
/** 判断标准字段或插件动态字段在当前状态下是否必填。 */
function fieldRequired(field) {
  return form.connectionType === 'PLUGIN' ? Boolean(field.required) : isConnectionConfigFieldRequired(field, form.config)
}
/** 返回必填、条件必填或选填标识。 */
function fieldRequirementLabel(field) {
  if (field.requiredWhen) return t('workflowConnections.conditionalRequired')
  return t(fieldRequired(field) ? 'workflowConnections.requiredField' : 'workflowConnections.optionalField')
}
/** 返回内置枚举选项的可读名称，空值统一展示为不设置。 */
function connectionOptionLabel(option) {
  if (form.connectionType === 'PLUGIN') return pluginOptionLabel(option)
  const value = metadataOptionValue(option)
  return value === '' ? t('workflowConnections.notSet') : value
}
/** 更新一个标准或自定义配置字段。 */
function setConfigField(key, value) {
  form.config = form.connectionType === 'PLUGIN'
    ? { ...form.config, credentials: { ...(form.config.credentials || {}), [key]: value } }
    : { ...form.config, [key]: value }
}
/** 返回普通连接字段或插件嵌套凭据值。 */
function configFieldValue(key) { return form.connectionType === 'PLUGIN' ? form.config.credentials?.[key] : form.config[key] }
/** 选择插件组件并按凭据 Schema 重置非敏感默认值。 */
function selectPluginComponent(id) {
  const component = pluginComponents.value.find(item => item.id === Number(id))
  const credentials = Object.fromEntries((component?.credentialSchema || []).filter(item => item.default !== null && item.default !== undefined)
    .map(item => [item.name, item.default]))
  form.config = { pluginComponentId: Number(id), credentials }
}
/** 把插件凭据 Schema 转换为受控连接字段。 */
function pluginCredentialFields(component) {
  return (component?.credentialSchema || []).filter(item => String(item.type || '').toLowerCase() !== 'hidden')
    .map(item => ({ key: item.name, label: localizedMetadataText(item, 'label', locale.value, item.label || item.name), required: Boolean(item.required),
    description: localizedMetadataText(item, 'description', locale.value, item.description), editor: item.secret ? 'password' : item.type === 'boolean' ? 'boolean'
      : ['select', 'options'].includes(String(item.type || '').toLowerCase()) ? 'select' : 'text',
    options: Array.isArray(item.options) ? item.options : [], group: 'AUTH', wide: true }))
}
/** 返回插件凭据枚举项的当前语言名称，同时保持提交值不变。 */
function pluginOptionLabel(option) {
  const value = metadataOptionValue(option)
  return value === '' ? t('workflowConnections.notSet') : localizedMetadataOptionText(option, locale.value, value)
}
/** 生成包含来源、包和版本的插件组件标签。 */
function pluginComponentLabel(component) { return `${localizedMetadataText(component, 'name', locale.value, component.name)} · ${component.source}/${component.packageKey}@${component.packageVersion}` }
/** 保存前将编码规范为后端接受的大写格式。 */
function normalizeCode() { form.code = form.code.trim().toUpperCase() }
/** 判断动态插件字段是否缺少可提交值。 */
function emptyConfigValue(value) { return value === undefined || value === null || typeof value === 'string' && value.trim() === '' }
/** 返回对象型配置的键值列表。 */
function mapEntries(key) { return Object.entries(form.config[key] || {}) }
/** 更新对象型配置中的值。 */
function setMapValue(fieldKey, key, value) { setConfigField(fieldKey, { ...(form.config[fieldKey] || {}), [key]: value }) }
/** 删除对象型配置中的键值。 */
function removeMapValue(fieldKey, key) { const next = { ...(form.config[fieldKey] || {}) }; delete next[key]; setConfigField(fieldKey, next) }
/** 校验后向对象型配置添加键值。 */
function addMapValue(fieldKey) {
  const key = mapDraft.key.trim()
  if (!isSafeConfigKey(key)) { mapError.value = t('workflowConnections.invalidCustomKey'); return }
  if (Object.prototype.hasOwnProperty.call(form.config[fieldKey] || {}, key)) { mapError.value = t('workflowConnections.duplicateCustomKey'); return }
  setMapValue(fieldKey, key, mapDraft.value); mapDraft.key = ''; mapDraft.value = ''; mapError.value = ''
}
/** 校验后添加自定义配置卡片。 */
function addCustomField() {
  const key = customKey.value.trim()
  if (!isSafeConfigKey(key)) { customError.value = t('workflowConnections.invalidCustomKey'); return }
  if (Object.prototype.hasOwnProperty.call(form.config, key)) { customError.value = t('workflowConnections.duplicateCustomKey'); return }
  setConfigField(key, createConfigValue(customType.value)); customKey.value = ''; customError.value = ''
}
/** 删除自定义配置卡片。 */
function removeCustomField(key) { const next = { ...form.config }; delete next[key]; form.config = next }
/** 清空类型切换和弹窗复用产生的临时输入。 */
function clearDrafts() { mapDraft.key = ''; mapDraft.value = ''; mapError.value = ''; customKey.value = ''; customType.value = 'string'; customError.value = '' }
/** 创建空连接表单。 */
function emptyForm() { return { id: null, code: '', name: '', connectionCategory: '', connectionType: '', config: {}, enabled: true } }
onMounted(async () => { await completeOAuthCallback(); await load() })
</script>

<style scoped>
.panel { display: grid; gap: 18px; }
.head-actions { display: flex; align-items: center; gap: 12px; }
.auto-detect { display: flex; align-items: center; gap: 8px; padding: 4px 10px; border: 1px solid #dfe7f2; border-radius: 8px; background: #f8fafc; }
.auto-detect-label { font-size: 13px; color: var(--app-muted); }
.auto-detect-interval { width: 88px; }
.ds-group { display: grid; gap: 12px; }
.ds-group-head { display: flex; align-items: center; gap: 10px; }
.ds-group-head h3 { margin: 0; font-size: 15px; }
.ds-group-icon { display: inline-flex; width: 28px; height: 28px; align-items: center; justify-content: center; border-radius: 8px; }
.ds-group-icon svg { width: 16px; height: 16px; }
.ds-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(320px, 1fr)); gap: 14px; }
.ds-card { display: grid; gap: 10px; padding: 16px; border: 1px solid #dfe7f2; border-radius: 14px; background: #fff; box-shadow: 0 1px 2px rgba(15, 23, 42, .04); transition: box-shadow .2s, border-color .2s; }
.ds-card:hover { border-color: #c3d4f5; box-shadow: 0 6px 18px rgba(15, 23, 42, .08); }
.ds-card--disabled { opacity: .62; }
.ds-card-top { display: flex; align-items: center; gap: 12px; }
.ds-logo { display: inline-flex; width: 42px; height: 42px; flex: none; align-items: center; justify-content: center; border-radius: 12px; }
.ds-logo svg { width: 24px; height: 24px; }
.ds-titles { flex: 1; min-width: 0; display: grid; gap: 2px; }
.ds-titles strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ds-titles small { color: var(--app-muted); overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.ds-status-line { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ds-status { display: inline-flex; align-items: center; gap: 6px; font-size: 13px; font-weight: 600; }
.ds-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; }
.ds-status--ok { color: #047857; }
.ds-status--bad { color: #be123c; }
.ds-status--unknown, .ds-status--off { color: var(--app-muted); }
.ds-latency { font-size: 12px; color: var(--app-muted); font-variant-numeric: tabular-nums; }
.ds-meta-line small { color: var(--app-muted); }
.ds-muted { color: var(--app-muted); }
.ds-tags { display: flex; flex-wrap: wrap; gap: 8px; }
.ds-actions { display: flex; flex-wrap: wrap; gap: 4px; border-top: 1px solid #eef2f8; padding-top: 8px; }
.status-body { display: grid; gap: 16px; min-height: 120px; }
.status-hero { display: flex; align-items: center; gap: 10px; padding: 14px; border-radius: 12px; font-size: 15px; }
.ds-status--ok.status-hero { background: #ecfdf5; color: #047857; }
.ds-status--bad.status-hero { background: #fff1f2; color: #be123c; }
.status-latency { margin-left: auto; font-variant-numeric: tabular-nums; }
.status-metrics { display: grid; grid-template-columns: 1fr 1fr; gap: 10px; }
.status-metric { display: grid; gap: 4px; padding: 10px 12px; border: 1px solid #dfe7f2; border-radius: 10px; background: #f8fafc; }
.status-metric small { color: var(--app-muted); }
.status-metric strong { overflow-wrap: anywhere; }
.data-source-editor { max-height: calc(90vh - 160px); overflow-y: auto; padding-right: 4px; }
.connection-editor-layout { display: grid; grid-template-columns: 248px minmax(0, 1fr); align-items: start; gap: 24px; }
.connection-picker { position: sticky; top: 0; display: grid; gap: 10px; padding: 14px; border: 1px solid #dfe7f2; border-radius: 16px; background: #f8fafc; }
.connection-picker-title { padding: 2px 4px; color: #64748b; font-size: 12px; font-weight: 700; letter-spacing: .05em; text-transform: uppercase; }
.connection-category-nav, .connection-type-options { display: grid; gap: 6px; }
.connection-category-option, .connection-type-option { display: flex; width: 100%; min-width: 0; align-items: center; gap: 10px; padding: 9px 10px; border: 1px solid transparent; border-radius: 11px; background: transparent; color: var(--app-text); font: inherit; text-align: left; cursor: pointer; transition: background .16s ease, border-color .16s ease, box-shadow .16s ease; }
.connection-category-option:hover, .connection-type-option:hover { border-color: #d5e0ef; background: #fff; }
.connection-category-option:focus-visible, .connection-type-option:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 1px; }
.connection-category-option.active, .connection-type-option.active { border-color: #b9ccef; background: #fff; box-shadow: 0 4px 14px rgba(30, 64, 175, .08); }
.connection-nav-icon { display: inline-flex; width: 32px; height: 32px; flex: none; align-items: center; justify-content: center; border: 1px solid; border-radius: 9px; }
.connection-nav-icon svg { width: 18px; height: 18px; }
.connection-nav-copy { display: flex; min-width: 0; flex: 1; align-items: center; justify-content: space-between; gap: 8px; }
.connection-nav-copy strong, .connection-type-option strong { overflow: hidden; font-size: 13px; text-overflow: ellipsis; white-space: nowrap; }
.connection-nav-copy small { color: #94a3b8; font-variant-numeric: tabular-nums; }
.connection-nav-check { margin-left: auto; color: var(--app-primary); font-size: 13px; font-weight: 800; }
.connection-type-nav { display: grid; gap: 8px; margin-top: 4px; padding-top: 12px; border-top: 1px solid #e2e8f0; }
.connection-type-option { padding-block: 8px; }
.connection-type-option .connection-nav-icon { width: 30px; height: 30px; }
.connection-form-pane { min-width: 0; }
.connection-selection-head, .connection-selection-empty { display: flex; min-height: 56px; align-items: center; gap: 12px; margin-bottom: 18px; padding: 13px 15px; border: 1px solid #dbe6f4; border-radius: 14px; background: linear-gradient(135deg, #f8fbff, #f8fafc); }
.connection-selection-copy { display: grid; min-width: 0; gap: 2px; }
.connection-selection-head small, .connection-selection-empty small { color: var(--app-muted); }
.connection-selection-head strong { font-size: 16px; }
.connection-selection-copy p { margin: 3px 0 0; color: var(--app-muted); font-size: 12px; line-height: 1.45; }
.connection-selection-icon { display: inline-flex; width: 42px; height: 42px; flex: none; align-items: center; justify-content: center; border: 1px solid; border-radius: 12px; }
.connection-selection-icon svg { width: 23px; height: 23px; }
.connection-selection-empty { align-items: flex-start; flex-direction: column; justify-content: center; gap: 4px; border-style: dashed; }
.connection-form-section { padding-bottom: 20px; }
.connection-identity-section { margin-bottom: 20px; border-bottom: 1px solid #e7edf5; }
.connection-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.connection-grid--identity { grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) 132px; }
.connection-grid :deep(.el-form-item) { margin-bottom: 0; }
.connection-input-stack { display: grid; width: 100%; gap: 5px; }
.connection-input-stack small { color: var(--app-muted); font-size: 11px; line-height: 1.4; }
.connection-status-control { display: flex; min-height: 32px; align-items: center; gap: 9px; color: var(--app-muted); font-size: 13px; }
.form-help { padding: 10px 12px; border-radius: 9px; background: #f8fafc; color: var(--app-muted); font-size: 12px; line-height: 1.5; }
.connection-config-section { display: flex; flex-direction: column; gap: 14px; margin-bottom: 20px; border-bottom: 1px solid #e7edf5; }
.connection-config-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.connection-tag { font-weight: 600; }
.connection-config-head h3, .connection-config-head p { margin: 0; }
.connection-config-head p { margin-top: 4px; color: var(--app-muted); font-size: 13px; }
.connection-plugin-select { margin-bottom: 0; }
.connection-config-groups { display: grid; gap: 12px; }
.connection-config-group { display: grid; gap: 12px; padding: 13px 14px 14px; border: 1px solid #e2e8f0; border-radius: 12px; background: #fbfdff; }
.connection-config-group-head { display: flex; align-items: baseline; gap: 9px; padding-bottom: 9px; border-bottom: 1px solid #edf2f7; }
.connection-config-group-head strong { color: #334155; font-size: 13px; }
.connection-config-group-head small { color: var(--app-muted); font-size: 11px; }
.connection-config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px 16px; }
.connection-config-field { display: grid; min-width: 0; align-content: start; gap: 6px; }
.connection-config-field--wide { grid-column: 1 / -1; }
.connection-field-label { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 8px; }
.connection-field-label strong { overflow: hidden; color: #334155; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.connection-field-requirement { flex: none; color: #94a3b8; font-size: 10px; }
.connection-field-requirement.required { color: var(--el-color-danger); }
.connection-card-body { display: flex; min-width: 0; min-height: 32px; align-items: center; }
.connection-field-help { color: var(--app-muted); font-size: 11px; line-height: 1.4; }
.connection-boolean-control { display: flex; width: 100%; min-height: 32px; align-items: center; gap: 8px; padding: 0 10px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; color: var(--app-muted); font-size: 12px; }
.connection-boolean-control--risk { border-color: #f1d8a8; background: #fffcf5; }
.connection-boolean-control small { margin-left: auto; color: #b7791f; font-size: 10px; }
.connection-key-values, .connection-custom-list { display: flex; flex-direction: column; gap: 10px; }
.connection-key-values { width: 100%; }
.connection-key-value { display: grid; grid-template-columns: minmax(120px, .8fr) minmax(160px, 1.2fr) auto; align-items: center; gap: 8px; }
.connection-key-value strong { overflow-wrap: anywhere; }
.connection-custom-section { margin-bottom: 4px; border: 1px solid #e2e8f0; border-radius: 13px; background: #f8fafc; }
.connection-custom-section > summary { display: flex; align-items: center; justify-content: space-between; gap: 16px; padding: 14px 16px; cursor: pointer; list-style: none; }
.connection-custom-section > summary::-webkit-details-marker { display: none; }
.connection-custom-section > summary h3, .connection-custom-section > summary p { margin: 0; }
.connection-custom-section > summary p { margin-top: 3px; color: var(--app-muted); font-size: 12px; }
.connection-custom-list { padding: 0 16px 16px; }
.connection-custom-card { min-width: 0; padding: 14px; border: 1px solid #dfe7f2; border-radius: 11px; background: #fff; }
.connection-custom-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.connection-custom-head strong { overflow-wrap: anywhere; }
.connection-custom-add { display: grid; grid-template-columns: minmax(160px, 1fr) 150px auto; gap: 8px; }
.connection-error { color: var(--el-color-danger); }
.connection-required { color: var(--el-color-danger); }
@media (max-width: 900px) {
  .connection-editor-layout { grid-template-columns: 1fr; }
  .connection-picker { position: static; }
  .connection-category-nav { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .connection-type-options { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 720px) {
  .data-source-editor { max-height: none; overflow: visible; }
  .connection-grid, .connection-config-grid, .connection-key-value, .connection-custom-add { grid-template-columns: 1fr; }
  .connection-grid { gap: 0; }
  .connection-grid--identity { gap: 14px; }
  .connection-config-field--wide { grid-column: auto; }
  .connection-key-value { align-items: stretch; }
  .head-actions { flex-wrap: wrap; }
}
@media (max-width: 520px) {
  .connection-category-nav, .connection-type-options { grid-template-columns: 1fr; }
  .connection-editor-layout { gap: 18px; }
  .connection-picker { padding: 11px; }
  .connection-config-group-head { align-items: flex-start; flex-direction: column; gap: 3px; }
}
</style>
