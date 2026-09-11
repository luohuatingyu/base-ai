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

    <el-dialog v-model="visible" :title="form.id ? t('dataSources.edit') : t('dataSources.add')" width="min(1180px, 96vw)"
               :close-on-click-modal="!saving" :close-on-press-escape="!saving">
      <div class="connection-wizard">
        <header class="connection-wizard-steps" :aria-label="t('workflowConnections.wizardProgress')">
          <button type="button" class="connection-wizard-step" :class="{ active: editorStep === 'TYPE', completed: editorStep === 'CONFIG' }"
                  @click="goToTypeStep">
            <span>1</span><div><strong>{{ t('workflowConnections.chooseStep') }}</strong><small>{{ t('workflowConnections.chooseStepHelp') }}</small></div>
          </button>
          <span class="connection-wizard-line" :class="{ completed: editorStep === 'CONFIG' }" />
          <button type="button" class="connection-wizard-step" :class="{ active: editorStep === 'CONFIG' }" :disabled="!form.connectionType"
                  @click="returnToConfiguration">
            <span>2</span><div><strong>{{ t('workflowConnections.configureStep') }}</strong><small>{{ t('workflowConnections.configureStepHelp') }}</small></div>
          </button>
        </header>

        <section v-if="editorStep === 'TYPE'" class="connection-type-stage">
          <div class="connection-stage-heading">
            <div><span class="connection-stage-eyebrow">{{ t('workflowConnections.chooseEyebrow') }}</span><h3>{{ t('workflowConnections.chooseTitle') }}</h3><p>{{ t('workflowConnections.chooseDescription') }}</p></div>
            <el-tag type="info" effect="plain">{{ t('workflowConnections.typeTotal', { count: connectionTypes.length }) }}</el-tag>
          </div>
          <nav class="connection-category-tabs" :aria-label="t('workflowConnections.category')">
            <button v-for="category in connectionCategories" :key="category.key" type="button"
                    :class="{ active: selectionCategory === category.key }" @click="selectCategory(category.key)">
              <span class="connection-category-tab-icon" :style="categoryStyle(category.key)"><DataSourceTypeIcon :category="category.key" /></span>
              <strong>{{ categoryLabel(category.key) }}</strong><small>{{ category.types.length }}</small>
            </button>
          </nav>
          <div class="connection-type-card-grid">
            <button v-for="type in availableConnectionTypes" :key="type" type="button" class="connection-type-card"
                    :class="{ active: selectionType === type }" :aria-pressed="selectionType === type" @click="selectConnectionType(type)">
              <span class="connection-type-card-icon" :style="typeStyle(type)"><DataSourceTypeIcon :type="type" /></span>
              <span class="connection-type-card-copy"><strong>{{ typeLabel(type) }}</strong><small>{{ connectionTypeGuide(type) }}</small></span>
              <span class="connection-type-card-meta">{{ connectionTypeFieldSummary(type) }}</span>
              <span v-if="selectionType === type" class="connection-type-card-check" aria-hidden="true">✓</span>
            </button>
          </div>
          <div class="connection-security-callout"><strong>{{ t('workflowConnections.securityTitle') }}</strong><span>{{ t('workflowConnections.securityNotice') }}</span></div>
        </section>

        <el-form v-else label-position="top" class="data-source-editor">
          <div class="connection-config-hero">
            <span class="connection-selection-icon" :style="typeStyle(form.connectionType)"><DataSourceTypeIcon :type="form.connectionType" /></span>
            <div class="connection-selection-copy"><small>{{ categoryLabel(form.connectionCategory) }}</small><strong>{{ typeLabel(form.connectionType) }}</strong><p>{{ connectionTypeGuide(form.connectionType) }}</p></div>
            <div class="connection-hero-progress"><small>{{ t('workflowConnections.requiredProgress') }}</small><strong>{{ requiredProgress.completed }}/{{ requiredProgress.total }}</strong></div>
            <el-button link type="primary" @click="goToTypeStep">{{ t('workflowConnections.changeType') }}</el-button>
          </div>

          <el-alert v-if="saveTestState" class="connection-save-test-alert" :type="saveTestState.type" :title="saveTestState.title"
                    :description="saveTestState.description" show-icon :closable="false" />

          <div class="connection-config-layout">
            <main class="connection-form-pane">
              <section class="connection-form-card connection-identity-card">
                <div class="connection-form-card-head"><span class="connection-form-card-kicker">01</span><div><h3>{{ t('workflowConnections.identityTitle') }}</h3><p>{{ t('workflowConnections.identityHelp') }}</p></div></div>
                <div class="connection-grid connection-grid--identity">
                  <div class="connection-config-field" :class="{ 'has-error': identityFieldError('code') }" @focusin="focusIdentityField" @focusout="touchIdentityField('code')">
                    <div class="connection-field-label"><strong>{{ t('common.code') }}</strong><span class="connection-field-requirement required">{{ t('workflowConnections.requiredField') }}</span></div>
                    <el-input v-model="form.code" :placeholder="t('workflowConnections.codePlaceholder')" @blur="normalizeCode" />
                    <small v-if="identityFieldError('code')" class="connection-inline-error">{{ identityFieldError('code') }}</small>
                    <small v-else class="connection-field-help">{{ t('workflowConnections.codeHelp') }}</small>
                  </div>
                  <div class="connection-config-field" :class="{ 'has-error': identityFieldError('name') }" @focusin="focusIdentityField" @focusout="touchIdentityField('name')">
                    <div class="connection-field-label"><strong>{{ t('common.name') }}</strong><span class="connection-field-requirement required">{{ t('workflowConnections.requiredField') }}</span></div>
                    <el-input v-model="form.name" :placeholder="t('workflowConnections.namePlaceholder')" />
                    <small v-if="identityFieldError('name')" class="connection-inline-error">{{ identityFieldError('name') }}</small>
                    <small v-else class="connection-field-help">{{ t('workflowConnections.nameHelp') }}</small>
                  </div>
                  <div class="connection-config-field" @focusin="focusIdentityField">
                    <div class="connection-field-label"><strong>{{ t('common.status') }}</strong><span class="connection-field-requirement">{{ t('workflowConnections.optionalField') }}</span></div>
                    <div class="connection-status-control"><el-switch v-model="form.enabled" /><span>{{ t(form.enabled ? 'common.enabled' : 'common.disabled') }}</span></div>
                    <small class="connection-field-help">{{ t('workflowConnections.statusHelp') }}</small>
                  </div>
                </div>
              </section>

              <section v-if="form.connectionType === 'PLUGIN'" class="connection-form-card" :class="{ 'has-error': pluginComponentError }">
                <div class="connection-form-card-head"><span class="connection-form-card-kicker">02</span><div><h3>{{ t('workflowConnections.pluginComponent') }}</h3><p>{{ t('workflowConnections.pluginComponentHelp') }}</p></div></div>
                <el-select :model-value="form.config.pluginComponentId" class="full" filterable :placeholder="t('workflowConnections.pluginComponentPlaceholder')"
                           @change="touchPluginComponent" @update:model-value="selectPluginComponent">
                  <el-option v-for="component in pluginComponents" :key="component.id" :value="component.id" :label="pluginComponentLabel(component)" />
                </el-select>
                <small v-if="pluginComponentError" class="connection-inline-error">{{ pluginComponentError }}</small>
              </section>

              <template v-for="section in configFormSections" :key="section.key">
                <section v-if="section.kind === 'GROUP'" class="connection-form-card" :class="{ 'connection-form-card--advanced': section.advanced }">
                  <div class="connection-form-card-head"><span class="connection-form-card-kicker">{{ configGroupMarker(section.key) }}</span><div><h3>{{ configGroupLabel(section.key) }}</h3><p>{{ configGroupDescription(section.key) }}</p></div></div>
                  <div class="connection-config-grid">
                    <div v-for="field in section.fields" :key="field.key" class="connection-config-field"
                         :class="{ 'connection-config-field--wide': field.wide || field.editor === 'keyValue', 'has-error': configFieldError(field) }"
                         @focusin="focusConfigField(field)" @focusout="touchConfigField(field.key)">
                      <div class="connection-field-label"><strong>{{ fieldLabel(field.key) }}</strong><span class="connection-field-requirement" :class="{ required: fieldRequired(field) }">{{ fieldRequirementLabel(field) }}</span></div>
                      <div class="connection-card-body">
                        <el-input v-if="['text', 'password'].includes(field.editor)" :model-value="configFieldValue(field.key)" :type="field.editor"
                                  :show-password="field.editor === 'password'" autocomplete="off" :placeholder="fieldPlaceholder(field)"
                                  @update:model-value="setConfigField(field.key, $event)" />
                        <div v-else-if="field.editor === 'boolean'" class="connection-boolean-control" :class="{ 'connection-boolean-control--risk': field.risk }">
                          <el-switch :model-value="configFieldValue(field.key)" @update:model-value="setConfigField(field.key, $event)" />
                          <span>{{ t(configFieldValue(field.key) ? 'common.enabled' : 'common.disabled') }}</span><small v-if="field.risk">{{ t('workflowConnections.sensitiveOption') }}</small>
                        </div>
                        <el-select v-else-if="field.editor === 'select'" :model-value="configFieldValue(field.key)" class="full"
                                   @update:model-value="setConfigField(field.key, $event)">
                          <el-option v-for="option in field.options" :key="metadataOptionValue(option) || '__empty'" :label="connectionOptionLabel(option)" :value="metadataOptionValue(option)" />
                        </el-select>
                        <template v-else-if="field.editor === 'keyValue'">
                          <div class="connection-key-values">
                            <div v-for="([key, value]) in mapEntries(field.key)" :key="key" class="connection-key-value"><strong>{{ key }}</strong><el-input :model-value="value" @update:model-value="setMapValue(field.key, key, $event)" /><el-button link type="danger" @click="removeMapValue(field.key, key)">{{ t('common.delete') }}</el-button></div>
                            <div class="connection-key-value connection-key-value--add"><el-input v-model="mapDraft.key" :placeholder="t('workflowConnections.customKey')" /><el-input v-model="mapDraft.value" :placeholder="t('workflowConnections.customValue')" @keyup.enter="addMapValue(field.key)" /><el-button type="primary" plain @click="addMapValue(field.key)">{{ t('common.add') }}</el-button></div>
                            <small v-if="mapError" class="connection-error">{{ mapError }}</small>
                          </div>
                        </template>
                      </div>
                      <small v-if="configFieldError(field)" class="connection-inline-error">{{ configFieldError(field) }}</small>
                    </div>
                  </div>
                </section>

                <section v-else class="connection-advanced-toggle" :class="{ active: advancedOpen }">
                  <button type="button" @click="advancedOpen = !advancedOpen"><span class="connection-advanced-icon">{{ advancedOpen ? '−' : '+' }}</span><div><strong>{{ t('workflowConnections.advancedTitle') }}</strong><small>{{ t('workflowConnections.advancedHelp') }}</small></div><el-tag type="info" effect="plain">{{ t('workflowConnections.configuredCount', { count: advancedConfiguredCount }) }}</el-tag><span class="connection-advanced-action">{{ t(advancedOpen ? 'workflowConnections.collapseAdvanced' : 'workflowConnections.expandAdvanced') }}</span></button>
                </section>
              </template>

              <section v-if="advancedOpen" class="connection-custom-section">
                <div class="connection-form-card-head"><span class="connection-form-card-kicker">+</span><div><h3>{{ t('workflowConnections.customTitle') }}</h3><p>{{ t('workflowConnections.customHelp') }}</p></div><el-tag type="info" effect="plain">{{ customKeys.length }}</el-tag></div>
                <div class="connection-custom-list">
                  <article v-for="key in customKeys" :key="key" class="connection-custom-card"><div class="connection-custom-head"><strong>{{ key }}</strong><el-button link type="danger" @click="removeCustomField(key)">{{ t('common.delete') }}</el-button></div><WorkflowConfigValueEditor :model-value="form.config[key]" @update:model-value="setConfigField(key, $event)" /></article>
                  <div class="connection-custom-add"><el-input v-model="customKey" :placeholder="t('workflowConnections.customKey')" @keyup.enter="addCustomField" /><el-select v-model="customType"><el-option v-for="type in CONFIG_VALUE_TYPES" :key="type" :label="t(`workflowConfig.valueTypes.${type}`)" :value="type" /></el-select><el-button type="primary" plain @click="addCustomField">{{ t('workflowConnections.addCustom') }}</el-button></div>
                  <small v-if="customError" class="connection-error">{{ customError }}</small>
                </div>
              </section>
            </main>

            <aside class="connection-config-assistant">
              <section class="connection-assistant-progress"><div><span>{{ t('workflowConnections.configAssistant') }}</span><strong>{{ requiredProgress.completed }}/{{ requiredProgress.total }}</strong></div><el-progress :percentage="requiredProgress.percentage" :stroke-width="8" :show-text="false" /><small>{{ t('workflowConnections.progressHelp') }}</small></section>
              <section class="connection-assistant-guide"><span class="connection-assistant-eyebrow">{{ focusedField ? t('workflowConnections.fieldGuide') : t('workflowConnections.configurationGuide') }}</span><strong>{{ focusedField ? fieldLabel(focusedField.key) : typeLabel(form.connectionType) }}</strong><p>{{ focusedField ? fieldDescription(focusedField.key) : connectionTypeGuide(form.connectionType) }}</p><div v-if="focusedField" class="connection-assistant-example"><small>{{ t('workflowConnections.formatExample') }}</small><code>{{ fieldExample(focusedField) }}</code></div><div v-if="focusedField?.risk" class="connection-assistant-risk">{{ t('workflowConnections.riskHelp') }}</div></section>
              <section class="connection-assistant-checklist"><div class="connection-assistant-section-title"><strong>{{ t('workflowConnections.parameterChecklist') }}</strong><small>{{ assistantChecklist.length }}</small></div><ul><li v-for="item in assistantChecklist" :key="item.key" :class="{ completed: item.completed, missing: item.required && !item.completed }"><span>{{ item.completed ? '✓' : item.required ? '!' : '○' }}</span><div><strong>{{ item.label }}</strong><small>{{ item.required ? t('workflowConnections.requiredField') : t('workflowConnections.optionalField') }}</small></div></li></ul></section>
              <section class="connection-assistant-security"><strong>{{ t('workflowConnections.securityTitle') }}</strong><p>{{ t('workflowConnections.maskHelp') }}</p></section>
            </aside>
          </div>
        </el-form>
      </div>
      <template #footer>
        <div class="connection-wizard-footer">
          <el-button v-if="editorStep === 'CONFIG'" :disabled="saving" @click="goToTypeStep">{{ t('workflowConnections.changeType') }}</el-button><span />
          <el-button :disabled="saving" @click="visible=false">{{ t('common.cancel') }}</el-button>
          <el-button v-if="editorStep === 'TYPE'" type="primary" :disabled="!selectionType" @click="applyTypeSelection">{{ t('workflowConnections.continueConfigure') }}</el-button>
          <el-button v-else type="primary" :loading="saving" @click="save">{{ saveActionLabel }}</el-button>
        </div>
      </template>
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
import { cloneConnectionConfig, CONNECTION_CATEGORIES, CONNECTION_CONFIG_GROUPS, CONNECTION_TYPES, connectionCategoriesForType,
  connectionCategoryStyle, connectionConfigFields, connectionTypesForCategory, connectionTypeStyle,
  createConnectionConfig, extraConnectionConfigKeys, hasAdvancedConnectionConfig, hasMeaningfulConnectionConfig,
  isConnectionConfigFieldRequired, isConnectionConfigValueEmpty, missingConnectionConfigFields,
  saveConnectionWithOptionalTest } from '../utils/workflowConnectionConfig'

const { t, te, locale } = useI18n()
const auth = useAuthStore()
const rows = ref([]), visible = ref(false)
const pluginComponents = ref([])
const connectionCategories = CONNECTION_CATEGORIES
const connectionTypes = CONNECTION_TYPES
const form = reactive(emptyForm())
const mapDraft = reactive({ key: '', value: '' })
const mapError = ref(''), customKey = ref(''), customType = ref('string'), customError = ref('')
const testingId = ref(null), batchTesting = ref(false)
const statusVisible = ref(false), statusLoading = ref(false), statusRow = ref(null), statusData = ref(null)
const editorStep = ref('TYPE'), selectionCategory = ref(CONNECTION_CATEGORIES[0].key), selectionType = ref('')
const advancedOpen = ref(false), focusedFieldKey = ref(''), validationAttempted = ref(false), saving = ref(false)
const touchedFields = reactive(new Set()), touchedIdentityFields = reactive(new Set())
const touchedPluginComponent = ref(false), saveTestState = ref(null)
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
/** 把常用分组排在前面，并在高级分组前插入唯一的折叠入口。 */
const configFormSections = computed(() => {
  const groups = configFieldGroups.value.map(group => ({ ...group, kind: 'GROUP', advanced: ['SCOPE', 'BEHAVIOR'].includes(group.key) }))
  return [...groups.filter(group => !group.advanced), { key: 'ADVANCED', kind: 'ADVANCED' },
    ...(advancedOpen.value ? groups.filter(group => group.advanced) : [])]
})
const customKeys = computed(() => extraConnectionConfigKeys(form.config, form.connectionType))
const availableConnectionTypes = computed(() => connectionTypesForCategory(selectionCategory.value))
const focusedField = computed(() => configFields.value.find(field => field.key === focusedFieldKey.value) || null)
/** 汇总基础信息、插件组件和参数字段，驱动右侧填写清单。 */
const assistantChecklist = computed(() => {
  const items = [
    { key: 'identity:code', label: t('common.code'), required: true, completed: validConnectionCode(form.code) },
    { key: 'identity:name', label: t('common.name'), required: true, completed: Boolean(form.name.trim()) }
  ]
  if (form.connectionType === 'PLUGIN') items.push({ key: 'plugin:component', label: t('workflowConnections.pluginComponent'), required: true, completed: Boolean(selectedPluginComponent.value) })
  return items.concat(configFields.value.map(field => ({
    key: `config:${field.key}`, label: fieldLabel(field.key), required: fieldRequired(field), completed: configFieldConfigured(field)
  })))
})
/** 计算所有当前必填项的完成比例，条件必填变化会即时反映。 */
const requiredProgress = computed(() => {
  const requiredItems = assistantChecklist.value.filter(item => item.required)
  const completed = requiredItems.filter(item => item.completed).length
  return { completed, total: requiredItems.length, percentage: requiredItems.length ? Math.round(completed / requiredItems.length * 100) : 100 }
})
const advancedConfiguredCount = computed(() => configFields.value.filter(field => ['SCOPE', 'BEHAVIOR'].includes(field.group) && configFieldConfigured(field)).length + customKeys.value.length)
const pluginComponentError = computed(() => form.connectionType === 'PLUGIN' && !selectedPluginComponent.value
  && (validationAttempted.value || touchedPluginComponent.value) ? t('workflowConnections.fieldRequiredInline') : '')
const saveActionLabel = computed(() => auth.hasPermission('operations:data-source:test') ? t('workflowConnections.saveAndTest') : t('common.save'))
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
  selectionCategory.value = connectionCategory || CONNECTION_CATEGORIES[0].key
  selectionType.value = connectionType
  editorStep.value = connectionType ? 'CONFIG' : 'TYPE'
  advancedOpen.value = connectionType ? hasAdvancedConnectionConfig(connectionType, form.config) : false
  focusedFieldKey.value = connectionType ? connectionConfigFields(connectionType)[0]?.key || '' : ''
  clearDrafts(); clearValidation(); saveTestState.value = null; visible.value = true
}
/** 在类型选择步骤切换分类，不提前改写已经填写的正式配置。 */
function selectCategory(category) {
  if (selectionCategory.value === category) return
  selectionCategory.value = category
  if (!connectionTypesForCategory(category).includes(selectionType.value)) selectionType.value = ''
}
/** 在类型卡片中选择候选类型，点击继续后才应用到表单。 */
function selectConnectionType(type) { selectionType.value = type }
/** 返回类型选择步骤，同时保留当前表单供用户取消更换。 */
function goToTypeStep() {
  selectionCategory.value = form.connectionCategory || selectionCategory.value || CONNECTION_CATEGORIES[0].key
  selectionType.value = form.connectionType
  editorStep.value = 'TYPE'
}
/** 未改变候选类型时从步骤条直接返回配置页。 */
function returnToConfiguration() {
  if (form.connectionType) editorStep.value = 'CONFIG'
}
/** 应用候选类型；已有有效配置时先确认，避免静默清空参数。 */
async function applyTypeSelection() {
  if (!selectionType.value) return ElMessage.warning(t('workflowConnections.selectConnectionType'))
  const typeChanged = Boolean(form.connectionType && form.connectionType !== selectionType.value)
  if (typeChanged && hasMeaningfulConnectionConfig(form.connectionType, form.config)) {
    try {
      await ElMessageBox.confirm(t('workflowConnections.changeTypeWarning'), t('workflowConnections.changeTypeConfirm'), { type: 'warning' })
    } catch (error) {
      if (error === 'cancel' || error === 'close') return
      throw error
    }
  }
  form.connectionCategory = selectionCategory.value
  if (form.connectionType !== selectionType.value) {
    form.connectionType = selectionType.value; resetConfig(selectionType.value)
  }
  editorStep.value = 'CONFIG'; saveTestState.value = null; clearValidation()
  focusedFieldKey.value = configFields.value[0]?.key || ''
}
/** 切换类型时使用安全默认配置。 */
function resetConfig(type) {
  form.config = type === 'PLUGIN' ? { pluginComponentId: null, credentials: {} } : createConnectionConfig(type)
  advancedOpen.value = false; clearDrafts()
}
/** 创建或更新结构化连接配置；有权限时在持久化成功后继续检测。 */
async function save() {
  if (saving.value || !validateEditor()) return
  normalizeCode()
  const command = { code: form.code, name: form.name, connectionType: form.connectionType, config: cloneConnectionConfig(form.config), enabled: form.enabled }
  saving.value = true; saveTestState.value = null
  try {
    const result = await saveConnectionWithOptionalTest({
      connectionId: form.id,
      command,
      shouldTest: auth.hasPermission('operations:data-source:test'),
      persist: async (id, payload) => (id ? await http.put(`/data-sources/${id}`, payload) : await http.post('/data-sources', payload)).data,
      testConnection: async id => (await http.post(`/data-sources/${id}/test`)).data
    })
    if (result.saved?.id) form.id = result.saved.id
    await load()
    if (result.testError) {
      saveTestState.value = { type: 'error', title: t('workflowConnections.savedTestFailedTitle'), description: t('workflowConnections.savedTestFailedDescription') }
      showHttpError(result.testError, 'dataSources.testFailed'); return
    }
    visible.value = false
    if (!result.tested) ElMessage.success(t('common.successSaved'))
    else if (result.testResult?.vectorSupported === false && ['POSTGRESQL', 'QDRANT', 'MILVUS', 'ELASTICSEARCH'].includes(form.connectionType)) ElMessage.warning(t('dataSources.vectorUnsupported'))
    else ElMessage.success(t('workflowConnections.savedAndTested'))
  } catch (error) { showHttpError(error, 'common.saveFailed') }
  finally { saving.value = false }
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
/** 汇总类型卡片上的参数总数和固定必填数。 */
function connectionTypeFieldSummary(type) {
  const fields = connectionConfigFields(type)
  const required = fields.filter(field => field.required).length + (type === 'PLUGIN' ? 1 : 0)
  return t('workflowConnections.parameterSummary', { count: fields.length + (type === 'PLUGIN' ? 1 : 0), required })
}
/** 返回核心分组和高级分组的稳定视觉编号。 */
function configGroupMarker(group) { return { CONNECTION: '02', AUTH: '03', SCOPE: 'A1', BEHAVIOR: 'A2' }[group] || '•' }
/** 检查连接编码是否符合后端接受的稳定格式。 */
function validConnectionCode(value) { return /^[A-Z][A-Z0-9_-]{1,79}$/.test(String(value || '').trim().toUpperCase()) }
/** 返回基础字段的实时错误，仅在字段触达或提交后展示。 */
function identityFieldError(key) {
  if (!validationAttempted.value && !touchedIdentityFields.has(key)) return ''
  if (key === 'code') return !String(form.code || '').trim() ? t('workflowConnections.fieldRequiredInline')
    : validConnectionCode(form.code) ? '' : t('workflowConnections.codeInvalid')
  return key === 'name' && !form.name.trim() ? t('workflowConnections.fieldRequiredInline') : ''
}
/** 基础字段获得焦点时让助手回到数据源整体说明。 */
function focusIdentityField() { focusedFieldKey.value = '' }
/** 记录基础字段已被用户操作，用于控制实时错误显示。 */
function touchIdentityField(key) { touchedIdentityFields.add(key) }
/** 判断参数是否已配置；选填默认值不计入主动配置数量。 */
function configFieldConfigured(field) {
  const value = configFieldValue(field.key)
  if (fieldRequired(field)) return !isConnectionConfigValueEmpty(value)
  if (value && typeof value === 'object') return Object.keys(value).length > 0
  return !isConnectionConfigValueEmpty(value) && JSON.stringify(value) !== JSON.stringify(field.defaultValue)
}
/** 返回参数字段的实时必填错误。 */
function configFieldError(field) {
  return fieldRequired(field) && isConnectionConfigValueEmpty(configFieldValue(field.key))
    && (validationAttempted.value || touchedFields.has(field.key)) ? t('workflowConnections.fieldRequiredInline') : ''
}
/** 聚焦参数时在右侧助手展示对应填写说明。 */
function focusConfigField(field) { focusedFieldKey.value = field.key }
/** 记录参数字段已被操作。 */
function touchConfigField(key) { touchedFields.add(key) }
/** 记录插件组件已被选择或离开。 */
function touchPluginComponent() { touchedPluginComponent.value = true }
/** 根据编辑器类型生成可复制理解的配置示例。 */
function fieldExample(field) {
  if (field.editor === 'boolean') return t('workflowConnections.booleanExample')
  if (field.editor === 'keyValue') return t('workflowConnections.keyValueExample')
  if (field.editor === 'select') return field.options.map(connectionOptionLabel).join(' / ')
  return fieldPlaceholder(field)
}
/** 保存前统一执行基础、插件和条件参数校验，并定位首个缺失参数。 */
function validateEditor() {
  validationAttempted.value = true; normalizeCode()
  if (!form.code || !form.name.trim() || !form.connectionCategory || !form.connectionType
    || form.connectionType === 'PLUGIN' && !selectedPluginComponent.value) {
    ElMessage.warning(t('workflowConnections.required')); return false
  }
  if (!validConnectionCode(form.code)) { ElMessage.warning(t('workflowConnections.codeInvalid')); return false }
  const missingFields = form.connectionType === 'PLUGIN'
    ? configFields.value.filter(field => field.required && isConnectionConfigValueEmpty(configFieldValue(field.key))).map(field => field.key)
    : missingConnectionConfigFields(form.connectionType, form.config)
  if (!missingFields.length) return true
  const firstMissingField = configFields.value.find(field => field.key === missingFields[0])
  if (firstMissingField && ['SCOPE', 'BEHAVIOR'].includes(firstMissingField.group)) advancedOpen.value = true
  focusedFieldKey.value = missingFields[0]
  ElMessage.warning(t('workflowConnections.configRequired', { fields: missingFields.map(fieldLabel).join(t('workflowConnections.fieldSeparator')) }))
  return false
}
/** 清理上一次打开或切换类型产生的校验状态。 */
function clearValidation() {
  validationAttempted.value = false; touchedFields.clear(); touchedIdentityFields.clear(); touchedPluginComponent.value = false
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
.connection-wizard { display: grid; gap: 18px; }
.connection-wizard-steps { display: grid; grid-template-columns: minmax(0, 1fr) 80px minmax(0, 1fr); align-items: center; padding: 14px 18px; border: 1px solid #dbe7f6; border-radius: 16px; background: linear-gradient(135deg, #f7fbff 0%, #f8fafc 52%, #f5f3ff 100%); }
.connection-wizard-step { display: flex; min-width: 0; align-items: center; gap: 11px; padding: 0; border: 0; background: transparent; color: #64748b; font: inherit; text-align: left; cursor: pointer; }
.connection-wizard-step:disabled { cursor: default; opacity: .52; }
.connection-wizard-step > span { display: inline-flex; width: 34px; height: 34px; flex: none; align-items: center; justify-content: center; border: 2px solid #cbd5e1; border-radius: 50%; background: #fff; font-size: 13px; font-weight: 800; }
.connection-wizard-step > div { display: grid; min-width: 0; gap: 2px; }
.connection-wizard-step strong { color: #334155; font-size: 13px; }
.connection-wizard-step small { overflow: hidden; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.connection-wizard-step.active > span, .connection-wizard-step.completed > span { border-color: var(--app-primary); background: var(--app-primary); color: #fff; box-shadow: 0 0 0 5px rgba(37, 99, 235, .1); }
.connection-wizard-step.active strong { color: var(--app-primary); }
.connection-wizard-line { height: 2px; margin-inline: 14px; border-radius: 999px; background: #dbe4ef; }
.connection-wizard-line.completed { background: var(--app-primary); }
.connection-type-stage { display: grid; max-height: calc(90vh - 230px); gap: 18px; overflow-y: auto; padding: 4px 5px 4px 2px; }
.connection-stage-heading { display: flex; align-items: flex-end; justify-content: space-between; gap: 20px; }
.connection-stage-heading h3, .connection-stage-heading p { margin: 0; }
.connection-stage-heading h3 { margin-top: 4px; color: #0f172a; font-size: 22px; }
.connection-stage-heading p { max-width: 720px; margin-top: 6px; color: var(--app-muted); font-size: 13px; line-height: 1.55; }
.connection-stage-eyebrow, .connection-assistant-eyebrow { color: var(--app-primary); font-size: 10px; font-weight: 800; letter-spacing: .1em; text-transform: uppercase; }
.connection-category-tabs { display: grid; grid-template-columns: repeat(7, minmax(110px, 1fr)); gap: 8px; }
.connection-category-tabs button { display: flex; min-width: 0; align-items: center; gap: 8px; padding: 9px 10px; border: 1px solid #dfe7f2; border-radius: 11px; background: #fff; color: #64748b; font: inherit; cursor: pointer; transition: .16s ease; }
.connection-category-tabs button:hover { border-color: #aac3ec; background: #f8fbff; }
.connection-category-tabs button.active { border-color: #7da7e8; background: #eff6ff; color: #1d4ed8; box-shadow: inset 0 0 0 1px rgba(37, 99, 235, .08); }
.connection-category-tabs strong { min-width: 0; overflow: hidden; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.connection-category-tabs small { margin-left: auto; color: #94a3b8; font-variant-numeric: tabular-nums; }
.connection-category-tab-icon { display: inline-flex; width: 27px; height: 27px; flex: none; align-items: center; justify-content: center; border: 1px solid; border-radius: 8px; }
.connection-category-tab-icon svg { width: 15px; height: 15px; }
.connection-type-card-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.connection-type-card { position: relative; display: grid; grid-template-columns: 48px minmax(0, 1fr); grid-template-rows: auto auto; min-height: 126px; align-items: start; gap: 8px 14px; padding: 17px 44px 15px 17px; border: 1px solid #dce5f0; border-radius: 16px; background: #fff; color: var(--app-text); font: inherit; text-align: left; cursor: pointer; box-shadow: 0 2px 5px rgba(15, 23, 42, .04); transition: transform .16s ease, border-color .16s ease, box-shadow .16s ease; }
.connection-type-card:hover { transform: translateY(-2px); border-color: #9bb8e6; box-shadow: 0 10px 24px rgba(30, 64, 175, .1); }
.connection-type-card.active { border-color: var(--app-primary); background: linear-gradient(135deg, #fff 0%, #eff6ff 100%); box-shadow: 0 0 0 2px rgba(37, 99, 235, .12), 0 12px 28px rgba(30, 64, 175, .12); }
.connection-type-card-icon { display: inline-flex; width: 48px; height: 48px; grid-row: 1 / 3; align-items: center; justify-content: center; border: 1px solid; border-radius: 14px; }
.connection-type-card-icon svg { width: 27px; height: 27px; }
.connection-type-card-copy { display: grid; min-width: 0; gap: 5px; }
.connection-type-card-copy strong { color: #0f172a; font-size: 16px; }
.connection-type-card-copy small { color: var(--app-muted); font-size: 11px; line-height: 1.48; }
.connection-type-card-meta { align-self: end; color: #64748b; font-size: 11px; font-weight: 600; }
.connection-type-card-check { position: absolute; top: 15px; right: 15px; display: inline-flex; width: 24px; height: 24px; align-items: center; justify-content: center; border-radius: 50%; background: var(--app-primary); color: #fff; font-size: 12px; font-weight: 800; }
.connection-security-callout { display: flex; align-items: center; gap: 9px; padding: 11px 14px; border: 1px solid #d9e8fb; border-radius: 11px; background: #f0f7ff; color: #475569; font-size: 12px; }
.connection-security-callout strong { flex: none; color: #1d4ed8; }
.data-source-editor { max-height: calc(90vh - 228px); overflow-y: auto; padding-right: 5px; }
.connection-config-hero { position: sticky; z-index: 4; top: 0; display: flex; align-items: center; gap: 13px; margin-bottom: 14px; padding: 13px 15px; border: 1px solid #bfd5f2; border-radius: 15px; background: linear-gradient(135deg, rgba(239, 246, 255, .98), rgba(248, 250, 252, .98)); box-shadow: 0 5px 16px rgba(15, 23, 42, .07); backdrop-filter: blur(8px); }
.connection-selection-icon { display: inline-flex; width: 46px; height: 46px; flex: none; align-items: center; justify-content: center; border: 1px solid; border-radius: 13px; background: #fff !important; }
.connection-selection-icon svg { width: 26px; height: 26px; }
.connection-selection-copy { display: grid; min-width: 0; flex: 1; gap: 1px; }
.connection-selection-copy small { color: #64748b; font-size: 11px; }
.connection-selection-copy strong { color: #0f172a; font-size: 17px; }
.connection-selection-copy p { max-width: 680px; margin: 2px 0 0; color: var(--app-muted); font-size: 11px; line-height: 1.4; }
.connection-hero-progress { display: grid; min-width: 70px; gap: 1px; padding: 7px 10px; border-radius: 10px; background: #fff; text-align: center; }
.connection-hero-progress small { color: #64748b; font-size: 10px; }
.connection-hero-progress strong { color: var(--app-primary); font-size: 16px; font-variant-numeric: tabular-nums; }
.connection-save-test-alert { margin-bottom: 14px; }
.connection-config-layout { display: grid; grid-template-columns: minmax(0, 1fr) 286px; align-items: start; gap: 16px; }
.connection-form-pane { display: grid; min-width: 0; gap: 12px; }
.connection-form-card, .connection-custom-section { display: grid; gap: 13px; padding: 15px 16px 16px; border: 1px solid #dfe7f2; border-radius: 14px; background: #fff; box-shadow: 0 1px 3px rgba(15, 23, 42, .04); }
.connection-form-card--advanced { border-style: dashed; background: #fbfdff; }
.connection-form-card.has-error { border-color: #f2a6a6; }
.connection-form-card-head { display: flex; align-items: flex-start; gap: 10px; }
.connection-form-card-head > div { display: grid; flex: 1; gap: 2px; }
.connection-form-card-head h3, .connection-form-card-head p { margin: 0; }
.connection-form-card-head h3 { color: #1e293b; font-size: 14px; }
.connection-form-card-head p { color: var(--app-muted); font-size: 11px; line-height: 1.45; }
.connection-form-card-kicker { display: inline-flex; min-width: 28px; height: 24px; align-items: center; justify-content: center; border-radius: 7px; background: #eaf2ff; color: #1d4ed8; font-size: 10px; font-weight: 800; }
.connection-grid, .connection-config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 13px 15px; }
.connection-grid--identity > :last-child { grid-column: 1 / -1; }
.connection-config-field { display: grid; min-width: 0; align-content: start; gap: 6px; }
.connection-config-field--wide { grid-column: 1 / -1; }
.connection-field-label { display: flex; min-width: 0; align-items: center; justify-content: space-between; gap: 8px; }
.connection-field-label strong { overflow: hidden; color: #334155; font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.connection-field-requirement { flex: none; padding: 1px 5px; border-radius: 999px; background: #f1f5f9; color: #94a3b8; font-size: 9px; }
.connection-field-requirement.required { background: #fff1f2; color: #be123c; }
.connection-config-field.has-error :deep(.el-input__wrapper), .connection-config-field.has-error :deep(.el-select__wrapper) { box-shadow: 0 0 0 1px var(--el-color-danger) inset; }
.connection-card-body { display: flex; min-width: 0; min-height: 32px; align-items: center; }
.connection-field-help { color: var(--app-muted); font-size: 10px; line-height: 1.45; }
.connection-inline-error, .connection-error { color: var(--el-color-danger); font-size: 10px; line-height: 1.4; }
.connection-status-control { display: flex; min-height: 32px; align-items: center; gap: 9px; padding-inline: 10px; border: 1px solid #dcdfe6; border-radius: 4px; color: #64748b; font-size: 12px; }
.connection-boolean-control { display: flex; width: 100%; min-height: 32px; align-items: center; gap: 8px; padding: 0 10px; border: 1px solid #dcdfe6; border-radius: 4px; background: #fff; color: var(--app-muted); font-size: 12px; }
.connection-boolean-control--risk { border-color: #f0c979; background: #fffbeb; }
.connection-boolean-control small { margin-left: auto; color: #a16207; font-size: 9px; font-weight: 700; }
.connection-key-values, .connection-custom-list { display: flex; flex-direction: column; gap: 10px; }
.connection-key-values { width: 100%; }
.connection-key-value { display: grid; grid-template-columns: minmax(120px, .8fr) minmax(160px, 1.2fr) auto; align-items: center; gap: 8px; }
.connection-key-value strong { overflow-wrap: anywhere; }
.connection-advanced-toggle { border: 1px dashed #b9cceb; border-radius: 14px; background: #f8fbff; }
.connection-advanced-toggle.active { border-style: solid; border-color: #9fbae4; background: #f3f7fd; }
.connection-advanced-toggle > button { display: flex; width: 100%; align-items: center; gap: 11px; padding: 13px 15px; border: 0; background: transparent; color: var(--app-text); font: inherit; text-align: left; cursor: pointer; }
.connection-advanced-toggle button > div { display: grid; flex: 1; gap: 2px; }
.connection-advanced-toggle strong { color: #334155; font-size: 13px; }
.connection-advanced-toggle small { color: var(--app-muted); font-size: 10px; }
.connection-advanced-icon { display: inline-flex; width: 27px; height: 27px; flex: none; align-items: center; justify-content: center; border-radius: 8px; background: #dbeafe; color: #1d4ed8; font-size: 18px; }
.connection-advanced-action { color: var(--app-primary); font-size: 11px; font-weight: 700; }
.connection-custom-card { min-width: 0; padding: 13px; border: 1px solid #dfe7f2; border-radius: 11px; background: #f8fafc; }
.connection-custom-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.connection-custom-head strong { overflow-wrap: anywhere; }
.connection-custom-add { display: grid; grid-template-columns: minmax(160px, 1fr) 150px auto; gap: 8px; }
.connection-config-assistant { position: sticky; top: 76px; display: grid; gap: 11px; }
.connection-config-assistant > section { padding: 13px 14px; border: 1px solid #dfe7f2; border-radius: 13px; background: #fff; }
.connection-assistant-progress { display: grid; gap: 9px; border-color: #b9d1f1 !important; background: linear-gradient(135deg, #eff6ff, #f8fafc) !important; }
.connection-assistant-progress > div { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.connection-assistant-progress span { color: #334155; font-size: 12px; font-weight: 700; }
.connection-assistant-progress strong { color: var(--app-primary); font-size: 18px; font-variant-numeric: tabular-nums; }
.connection-assistant-progress small { color: #64748b; font-size: 10px; line-height: 1.4; }
.connection-assistant-guide { display: grid; gap: 7px; }
.connection-assistant-guide > strong { color: #0f172a; font-size: 14px; }
.connection-assistant-guide > p, .connection-assistant-security p { margin: 0; color: #64748b; font-size: 11px; line-height: 1.55; }
.connection-assistant-example { display: grid; gap: 4px; padding: 8px 9px; border-radius: 8px; background: #f8fafc; }
.connection-assistant-example small { color: #94a3b8; font-size: 9px; font-weight: 700; text-transform: uppercase; }
.connection-assistant-example code { overflow-wrap: anywhere; color: #334155; font-size: 10px; line-height: 1.45; }
.connection-assistant-risk { padding: 7px 9px; border-radius: 8px; background: #fff7ed; color: #9a3412; font-size: 10px; line-height: 1.45; }
.connection-assistant-section-title { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.connection-assistant-section-title strong { color: #334155; font-size: 12px; }
.connection-assistant-section-title small { color: #94a3b8; }
.connection-assistant-checklist ul { display: grid; max-height: 240px; gap: 7px; overflow-y: auto; margin: 10px 0 0; padding: 0; list-style: none; }
.connection-assistant-checklist li { display: flex; align-items: center; gap: 8px; color: #94a3b8; }
.connection-assistant-checklist li > span { display: inline-flex; width: 19px; height: 19px; flex: none; align-items: center; justify-content: center; border-radius: 50%; background: #f1f5f9; font-size: 9px; font-weight: 800; }
.connection-assistant-checklist li > div { display: flex; min-width: 0; flex: 1; align-items: center; justify-content: space-between; gap: 8px; }
.connection-assistant-checklist li strong { overflow: hidden; color: #475569; font-size: 10px; text-overflow: ellipsis; white-space: nowrap; }
.connection-assistant-checklist li small { flex: none; font-size: 9px; }
.connection-assistant-checklist li.completed > span { background: #dcfce7; color: #15803d; }
.connection-assistant-checklist li.missing > span { background: #fff1f2; color: #be123c; }
.connection-assistant-security { border-color: #f1d59e !important; background: #fffbeb !important; }
.connection-assistant-security strong { display: block; margin-bottom: 5px; color: #92400e; font-size: 11px; }
.connection-wizard-footer { display: flex; align-items: center; gap: 10px; }
.connection-wizard-footer > span { flex: 1; }
.connection-tag { font-weight: 600; }
@media (max-width: 1040px) {
  .connection-category-tabs { grid-template-columns: repeat(4, minmax(120px, 1fr)); }
  .connection-config-layout { grid-template-columns: 1fr; }
  .connection-config-assistant { position: static; order: -1; grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .connection-assistant-checklist { grid-row: span 2; }
}
@media (max-width: 720px) {
  .connection-wizard-steps { grid-template-columns: 1fr 28px 1fr; padding-inline: 12px; }
  .connection-wizard-step small, .connection-wizard-line { display: none; }
  .connection-category-tabs { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .connection-type-card-grid, .connection-grid, .connection-config-grid, .connection-key-value, .connection-custom-add, .connection-config-assistant { grid-template-columns: 1fr; }
  .connection-type-stage, .data-source-editor { max-height: none; overflow: visible; }
  .connection-config-field--wide, .connection-grid--identity > :last-child { grid-column: auto; }
  .connection-config-hero { position: static; align-items: flex-start; flex-wrap: wrap; }
  .connection-selection-copy { flex-basis: calc(100% - 64px); }
  .connection-hero-progress { order: 3; }
  .connection-key-value { align-items: stretch; }
  .connection-assistant-checklist { grid-row: auto; }
  .head-actions { flex-wrap: wrap; }
}
@media (max-width: 520px) {
  .connection-stage-heading { align-items: flex-start; flex-direction: column; }
  .connection-category-tabs { grid-template-columns: 1fr; }
  .connection-type-card { grid-template-columns: 42px minmax(0, 1fr); padding-left: 13px; }
  .connection-type-card-icon { width: 42px; height: 42px; }
  .connection-wizard-footer { flex-wrap: wrap; }
  .connection-wizard-footer > span { display: none; }
  .connection-wizard-footer .el-button { flex: 1; margin-left: 0; }
  .connection-advanced-action { display: none; }
}
</style>
