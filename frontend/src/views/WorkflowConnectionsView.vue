<template>
  <div class="panel">
    <div class="section-head">
      <div><h2>{{ t('workflowConnections.title') }}</h2><p>{{ t('workflowConnections.description') }}</p></div>
      <el-button v-if="auth.hasPermission('workflow:connection:create')" type="primary" @click="open()">{{ t('workflowConnections.add') }}</el-button>
    </div>
    <el-alert :title="t('workflowConnections.securityNotice')" type="warning" show-icon :closable="false" />
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('common.code')" min-width="150" />
      <el-table-column prop="name" :label="t('common.name')" min-width="160" />
      <el-table-column :label="t('workflowConnections.category')" min-width="150"><template #default="scope">
        <el-tag class="connection-tag" :style="categoryStyle(preferredCategory(scope.row.connectionType))">{{ categoryLabel(preferredCategory(scope.row.connectionType)) }}</el-tag>
      </template></el-table-column>
      <el-table-column :label="t('workflowConnections.connectionType')" min-width="160"><template #default="scope">
        <el-tag class="connection-tag" :style="typeStyle(scope.row.connectionType, preferredCategory(scope.row.connectionType))">{{ typeLabel(scope.row.connectionType) }}</el-tag>
      </template></el-table-column>
      <el-table-column :label="t('workflowConnections.vectorCapability')" min-width="190"><template #default="scope"><el-tag :type="vectorStatusType(scope.row.vectorStatus)">{{ t(`workflowConnections.vectorStatuses.${scope.row.vectorStatus || 'UNKNOWN'}`) }}</el-tag><small v-if="scope.row.vectorEngine" class="vector-detail">{{ scope.row.vectorEngine }} {{ scope.row.vectorVersion }}</small></template></el-table-column>
      <el-table-column :label="t('common.status')" width="100"><template #default="scope"><el-tag :type="scope.row.enabled ? 'success' : 'info'">{{ scope.row.enabled ? t('common.enabled') : t('common.disabled') }}</el-tag></template></el-table-column>
      <el-table-column :label="t('common.operation')" width="280" fixed="right"><template #default="scope"><div class="table-actions">
        <el-button v-if="auth.hasPermission('workflow:connection:update')" link type="success" @click="test(scope.row)">{{ t('workflowConnections.test') }}</el-button>
        <el-button v-if="scope.row.connectionType === 'PLUGIN' && auth.hasPermission('workflow:connection:update')" link type="warning" @click="oauth(scope.row)">{{ t('workflowConnections.oauth') }}</el-button>
        <el-button v-if="auth.hasPermission('workflow:connection:update')" link type="primary" @click="open(scope.row)">{{ t('common.edit') }}</el-button>
        <el-button v-if="auth.hasPermission('workflow:connection:delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
      </div></template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id ? t('workflowConnections.edit') : t('workflowConnections.add')" width="min(980px, 94vw)">
      <el-form label-position="top">
        <div class="connection-grid"><el-form-item :label="t('common.code')"><el-input v-model="form.code" /></el-form-item><el-form-item :label="t('common.name')"><el-input v-model="form.name" /></el-form-item></div>
        <div class="connection-grid">
          <el-form-item :label="t('workflowConnections.category')">
            <el-select v-model="form.connectionCategory" class="full" :placeholder="t('workflowConnections.selectCategory')" @change="selectCategory">
              <el-option v-for="category in connectionCategories" :key="category.key" :label="categoryLabel(category.key)" :value="category.key">
                <el-tag class="connection-tag" :style="categoryStyle(category.key)">{{ categoryLabel(category.key) }}</el-tag>
              </el-option>
            </el-select>
          </el-form-item>
          <el-form-item :label="t('workflowConnections.connectionType')">
            <el-select v-model="form.connectionType" class="full" :disabled="!form.connectionCategory" :placeholder="t('workflowConnections.selectConnectionType')" @change="resetConfig">
              <el-option v-for="type in availableConnectionTypes" :key="type" :label="typeLabel(type)" :value="type">
                <el-tag class="connection-tag" :style="typeStyle(type, form.connectionCategory)">{{ typeLabel(type) }}</el-tag>
              </el-option>
            </el-select>
          </el-form-item>
        </div>
        <el-form-item v-if="form.connectionType === 'PLUGIN'" :label="t('workflowConnections.pluginComponent')">
          <el-select :model-value="form.config.pluginComponentId" class="full" filterable @update:model-value="selectPluginComponent">
            <el-option v-for="component in pluginComponents" :key="component.id" :value="component.id" :label="pluginComponentLabel(component)" />
          </el-select>
        </el-form-item>
        <section v-if="form.connectionType" class="connection-config-section">
          <div class="connection-config-head"><div><h3>{{ t('workflowConnections.config') }}</h3><p>{{ t('workflowConnections.configHelp') }}</p></div><div class="connection-tag-group"><el-tag class="connection-tag" :style="categoryStyle(form.connectionCategory)">{{ categoryLabel(form.connectionCategory) }}</el-tag><el-tag class="connection-tag" :style="typeStyle(form.connectionType, form.connectionCategory)">{{ typeLabel(form.connectionType) }}</el-tag></div></div>
          <div class="connection-config-grid">
            <article v-for="field in configFields" :key="field.key" class="connection-config-card">
              <div class="connection-card-head"><strong>{{ fieldLabel(field.key) }} <span v-if="field.required" class="connection-required">*</span></strong><small>{{ fieldDescription(field.key) }}</small></div>
              <div class="connection-card-body">
                <el-input v-if="['text', 'password'].includes(field.editor)" :model-value="configFieldValue(field.key)"
                          :type="field.editor" :show-password="field.editor === 'password'" autocomplete="off"
                          @update:model-value="setConfigField(field.key, $event)" />
                <el-switch v-else-if="field.editor === 'boolean'" :model-value="configFieldValue(field.key)"
                           @update:model-value="setConfigField(field.key, $event)" />
                <el-select v-else-if="field.editor === 'select'" :model-value="configFieldValue(field.key)" class="full"
                           @update:model-value="setConfigField(field.key, $event)">
                  <el-option v-for="option in field.options" :key="metadataOptionValue(option) || '__empty'"
                             :label="pluginOptionLabel(option)" :value="metadataOptionValue(option)" />
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
            </article>
          </div>
        </section>
        <div v-if="form.connectionType" class="form-help">{{ t('workflowConnections.maskHelp') }}</div>
        <section v-if="form.connectionType" class="connection-custom-section">
          <div class="connection-config-head"><div><h3>{{ t('workflowConnections.customTitle') }}</h3><p>{{ t('workflowConnections.customHelp') }}</p></div><el-tag type="info">{{ customKeys.length }}</el-tag></div>
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
        </section>
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
import WorkflowConfigValueEditor from '../components/WorkflowConfigValueEditor.vue'
import { useAuthStore } from '../stores/auth'
import { CONFIG_VALUE_TYPES, createConfigValue, isSafeConfigKey } from '../utils/workflowNodeConfig'
import { localizedMetadataOptionText, localizedMetadataText, metadataOptionValue } from '../utils/workflowTemplateCatalog'
import { cloneConnectionConfig, CONNECTION_CATEGORIES, connectionCategoriesForType, connectionCategoryStyle,
  connectionConfigFields, connectionTypesForCategory, connectionTypeStyle, createConnectionConfig,
  extraConnectionConfigKeys } from '../utils/workflowConnectionConfig'

const { t, te, locale } = useI18n()
const auth = useAuthStore()
const rows = ref([]), visible = ref(false)
const pluginComponents = ref([])
const connectionCategories = CONNECTION_CATEGORIES
const form = reactive(emptyForm())
const mapDraft = reactive({ key: '', value: '' })
const mapError = ref(''), customKey = ref(''), customType = ref('string'), customError = ref('')
const selectedPluginComponent = computed(() => pluginComponents.value.find(item => item.id === Number(form.config.pluginComponentId)))
const configFields = computed(() => form.connectionType === 'PLUGIN' ? pluginCredentialFields(selectedPluginComponent.value)
  : connectionConfigFields(form.connectionType))
const customKeys = computed(() => extraConnectionConfigKeys(form.config, form.connectionType))
const availableConnectionTypes = computed(() => connectionTypesForCategory(form.connectionCategory))

/** 加载当前用户可见的脱敏连接。 */
async function load() {
  const [connections, components] = await Promise.all([
    http.get('/workflow/connections'), http.get('/workflow/plugin-component-options')
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
/** 切换一级分类时选择该分类的首个连接类型并重置配置。 */
function selectCategory(category) {
  const [connectionType = ''] = connectionTypesForCategory(category)
  form.connectionType = connectionType; resetConfig(connectionType)
}
/** 切换类型时使用安全默认配置。 */
function resetConfig(type) { form.config = type === 'PLUGIN' ? { pluginComponentId: null, credentials: {} } : createConnectionConfig(type); clearDrafts() }
/** 创建或更新结构化连接配置。 */
async function save() {
  if (!form.code.trim() || !form.name.trim() || !form.connectionCategory || !form.connectionType
    || form.connectionType === 'PLUGIN' && !selectedPluginComponent.value) return ElMessage.warning(t('workflowConnections.required'))
  if (form.connectionType === 'PLUGIN' && configFields.value.some(field => field.required
    && (configFieldValue(field.key) === undefined || configFieldValue(field.key) === null || String(configFieldValue(field.key)).trim() === ''))) {
    return ElMessage.warning(t('workflowConnections.required'))
  }
  const command = { code: form.code, name: form.name, connectionType: form.connectionType, config: cloneConnectionConfig(form.config), enabled: form.enabled }
  try {
    if (form.id) await http.put(`/workflow/connections/${form.id}`, command)
    else await http.post('/workflow/connections', command)
    visible.value = false; await load(); ElMessage.success(t('common.successSaved'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
}
/** 执行无副作用连接测试。 */
async function test(row) { try { const { data } = await http.post(`/workflow/connections/${row.id}/test`); await load(); data.vectorSupported === false && ['POSTGRESQL','QDRANT','MILVUS','ELASTICSEARCH'].includes(row.connectionType) ? ElMessage.warning(t('workflowConnections.vectorUnsupported')) : ElMessage.success(t('workflowConnections.connected')) } catch (error) { showHttpError(error, 'workflowConnections.testFailed') } }
/** 启动插件提供的 OAuth 生命周期并跳转到经过后端校验的授权地址。 */
async function oauth(row) {
  try {
    const redirectUri = `${window.location.origin}${window.location.pathname}`
    const { data } = await http.post(`/workflow/connections/${row.id}/oauth/authorize`, { redirectUri })
    window.location.assign(data.authorizationUrl)
  } catch (error) { showHttpError(error, 'workflowConnections.oauthFailed') }
}
/** 在连接页面消费授权方回传的一次性 code/state，并清理浏览器地址。 */
async function completeOAuthCallback() {
  const query = new URLSearchParams(window.location.search)
  const code = query.get('code'), state = query.get('state')
  if (!code || !state) return
  try {
    await http.post('/workflow/plugin-oauth/callback', { code, state })
    ElMessage.success(t('workflowConnections.oauthConnected'))
  } catch (error) { showHttpError(error, 'workflowConnections.oauthFailed') }
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
/** 返回分类标签色板。 */
function categoryStyle(category) { return connectionCategoryStyle(category) }
/** 返回连接类型在当前分类中的同色系深浅样式。 */
function typeStyle(type, category) { return connectionTypeStyle(type, category) }
/** 删除未被工作流引用的连接。 */
async function remove(row) { try { await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name }), t('common.deleteConfirm')); await http.delete(`/workflow/connections/${row.id}`); await load() } catch (error) { if (error !== 'cancel' && error !== 'close') showHttpError(error) } }
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
    options: Array.isArray(item.options) ? item.options : [] }))
}
/** 返回插件凭据枚举项的当前语言名称，同时保持提交值不变。 */
function pluginOptionLabel(option) {
  const value = metadataOptionValue(option)
  return value === '' ? t('workflowConnections.notSet') : localizedMetadataOptionText(option, locale.value, value)
}
/** 生成包含来源、包和版本的插件组件标签。 */
function pluginComponentLabel(component) { return `${localizedMetadataText(component, 'name', locale.value, component.name)} · ${component.source}/${component.packageKey}@${component.packageVersion}` }
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
.connection-grid { display: grid; grid-template-columns: 1fr 1fr; gap: 14px; }
.form-help { margin: -8px 0 18px; color: var(--app-muted); font-size: 13px; }
.connection-config-section, .connection-custom-section { display: flex; flex-direction: column; gap: 14px; margin-bottom: 18px; }
.connection-config-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.connection-tag-group { display: flex; align-items: center; justify-content: flex-end; flex-wrap: wrap; gap: 8px; }
.connection-tag { font-weight: 600; }
.connection-config-head h3, .connection-config-head p { margin: 0; }
.connection-config-head p { margin-top: 4px; color: var(--app-muted); font-size: 13px; }
.connection-config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.connection-config-card, .connection-custom-card { min-width: 0; padding: 14px; border: 1px solid #dfe7f2; border-radius: 12px; background: #f8fafc; }
.connection-card-head { display: flex; flex-direction: column; gap: 4px; margin-bottom: 12px; }
.connection-card-head small { color: var(--app-muted); line-height: 1.45; }
.connection-card-body { min-width: 0; }
.connection-key-values, .connection-custom-list { display: flex; flex-direction: column; gap: 10px; }
.connection-key-value { display: grid; grid-template-columns: minmax(120px, .8fr) minmax(160px, 1.2fr) auto; align-items: center; gap: 8px; }
.connection-key-value strong { overflow-wrap: anywhere; }
.connection-custom-card { background: #fff; }
.connection-custom-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.connection-custom-head strong { overflow-wrap: anywhere; }
.connection-custom-add { display: grid; grid-template-columns: minmax(160px, 1fr) 150px auto; gap: 8px; }
.connection-error { color: var(--el-color-danger); }
.connection-required { color: var(--el-color-danger); }
.vector-detail { display: block; margin-top: 4px; color: var(--app-muted); }
@media (max-width: 720px) {
  .connection-grid, .connection-config-grid, .connection-key-value, .connection-custom-add { grid-template-columns: 1fr; }
  .connection-grid { gap: 0; }
  .connection-key-value { align-items: stretch; }
}
</style>
