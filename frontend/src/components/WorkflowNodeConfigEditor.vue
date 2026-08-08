<template>
  <div class="workflow-config-editor">
    <el-alert :title="t('workflowConfig.visualHint')" type="info" show-icon :closable="false" />
    <el-alert v-if="missingRequirements.length" :title="requiredHint" type="warning" show-icon :closable="false" />
    <div v-if="fields.length" class="workflow-config-grid">
      <article v-for="field in fields" :key="field.key" class="workflow-config-card"
               :class="{ configured: hasField(field.key), disabled: fieldDisabled(field), 'required-missing': fieldRequirementMissing(field) }">
        <button type="button" class="workflow-config-card-head" @click="toggleField(field.key)">
          <span><strong>{{ fieldLabel(field.key) }} <em v-if="field.requirement">{{ t(`workflowConfig.${field.requirement}`) }}</em></strong><small>{{ fieldDescription(field.key) }}</small></span>
          <span class="workflow-config-card-tags">
            <el-tag v-if="fieldRequirementMissing(field)" size="small" type="danger">{{ t('workflowConfig.requiredMissing') }}</el-tag>
            <el-tag v-else size="small" :type="hasField(field.key) ? 'success' : fieldCanToggle(field) ? 'info' : hasDefault(field) ? 'primary' : 'info'">{{ t(fieldStatusKey(field)) }}</el-tag>
          </span>
        </button>
        <div v-if="openFields.includes(field.key)" class="workflow-config-card-body">
          <div v-if="fieldCanToggle(field)" class="workflow-config-enable">
            <span>{{ t('workflowConfig.enableField') }}</span>
            <el-switch :model-value="hasField(field.key)" @change="toggleConfigured(field, $event)" />
          </div>
          <fieldset class="workflow-config-controls" :disabled="fieldDisabled(field)">
            <el-input v-if="['text','textarea'].includes(field.editor)" :model-value="fieldValue(field)"
                        :type="field.editor === 'textarea' ? 'textarea' : 'text'" :rows="4" @update:model-value="setField(field.key, $event)" />
            <el-input-number v-else-if="field.editor === 'number'" :model-value="fieldValue(field)" controls-position="right" @update:model-value="setNumber(field.key, $event)" />
            <el-switch v-else-if="field.editor === 'boolean'" :model-value="fieldValue(field)" @update:model-value="setField(field.key, $event)" />
            <el-select v-else-if="field.editor === 'select'" :model-value="fieldValue(field)" @update:model-value="setField(field.key, $event)">
              <el-option v-for="option in field.options" :key="option" :label="fieldOption(field.key, option)" :value="option" />
            </el-select>
            <template v-else-if="field.editor === 'model'">
              <el-select :model-value="fieldValue(field)" filterable clearable fit-input-width :loading="modelOptionsLoading"
                         :placeholder="t('workflowConfig.selectModel')" :no-data-text="t('workflowConfig.noCompatibleModels')"
                         @update:model-value="setModelId">
                <template #label="{ label }">
                  <el-text class="workflow-model-option-label" truncated>{{ label }}</el-text>
                </template>
                <el-option v-for="option in compatibleModelOptions" :key="option.id"
                           :label="workflowModelOptionLabel(option)" :value="option.id">
                  <el-text class="workflow-model-option-label" truncated>{{ workflowModelOptionLabel(option) }}</el-text>
                </el-option>
              </el-select>
              <el-alert v-if="modelOptionsError" :title="t('workflowConfig.modelOptionsFailed')" type="error" show-icon :closable="false" />
            </template>
            <template v-else-if="field.editor === 'mailRoute'">
              <el-select :model-value="fieldValue(field)" filterable clearable fit-input-width :loading="mailRouteOptionsLoading"
                         :placeholder="t('workflowConfig.selectMailRoute')" :no-data-text="t('workflowConfig.noMailRoutes')"
                         @update:model-value="setResourceId('routeId', $event)">
                <template #label="{ label }"><el-text class="workflow-model-option-label" truncated>{{ label }}</el-text></template>
                <el-option v-for="option in mailRouteOptions" :key="option.id"
                           :label="workflowMailRouteOptionLabel(option)" :value="option.id">
                  <el-text class="workflow-model-option-label" truncated>{{ workflowMailRouteOptionLabel(option) }}</el-text>
                </el-option>
              </el-select>
              <el-alert v-if="mailRouteOptionsError" :title="t('workflowConfig.mailRouteOptionsFailed')" type="error" show-icon :closable="false" />
            </template>
            <template v-else-if="field.editor === 'connection'">
              <el-select :model-value="fieldValue(field)" filterable clearable fit-input-width :loading="connectionOptionsLoading"
                         :placeholder="t('workflowConfig.selectConnection')" :no-data-text="t('workflowConfig.noCompatibleConnections')"
                         @update:model-value="setResourceId('connectionId', $event)">
                <template #label="{ label }"><el-text class="workflow-model-option-label" truncated>{{ label }}</el-text></template>
                <el-option v-for="option in compatibleConnectionOptions" :key="option.id"
                           :label="workflowConnectionOptionLabel(option)" :value="option.id">
                  <el-text class="workflow-model-option-label" truncated>{{ workflowConnectionOptionLabel(option) }}</el-text>
                </el-option>
              </el-select>
              <el-alert v-if="connectionOptionsError" :title="t('workflowConfig.connectionOptionsFailed')" type="error" show-icon :closable="false" />
            </template>
            <div v-else-if="field.editor === 'condition'" class="workflow-condition-editor">
              <el-input :model-value="condition(field).left" :placeholder="t('workflowConfig.conditionLeft')" @update:model-value="setCondition(field, 'left', $event)" />
              <el-select :model-value="condition(field).operator" @update:model-value="setCondition(field, 'operator', $event)">
                <el-option v-for="operator in CONDITION_OPERATORS" :key="operator" :label="t(`workflowConfig.operators.${operator}`)" :value="operator" />
              </el-select>
              <WorkflowConfigValueEditor v-if="!['EXISTS','EMPTY'].includes(condition(field).operator)" :model-value="condition(field).right" @update:model-value="setCondition(field, 'right', $event)" />
            </div>
            <WorkflowConfigValueEditor v-else :model-value="fieldValue(field)" @update:model-value="setField(field.key, $event)" />
          </fieldset>
        </div>
      </article>
    </div>
    <el-empty v-else :description="t('workflowConfig.noStandardFields')" :image-size="52" />

    <section class="workflow-extra-config">
      <div class="workflow-extra-head"><div><strong>{{ t('workflowConfig.extraTitle') }}</strong><small>{{ t('workflowConfig.extraDescription') }}</small></div><el-tag>{{ extraKeys.length }}</el-tag></div>
      <article v-for="key in extraKeys" :key="key" class="workflow-config-card configured">
        <button type="button" class="workflow-config-card-head" @click="toggleExtra(key)"><span><strong>{{ key }}</strong><small>{{ t('workflowConfig.customParameter') }}</small></span><span>⌄</span></button>
        <div v-if="openExtra.includes(key)" class="workflow-config-card-body">
          <WorkflowConfigValueEditor :model-value="config[key]" @update:model-value="setField(key, $event)" />
          <el-button type="danger" plain @click="removeField(key)">{{ t('workflowConfig.removeParameter') }}</el-button>
        </div>
      </article>
      <div class="workflow-extra-add">
        <el-input v-model="newKey" :placeholder="t('workflowConfig.parameterName')" @keyup.enter="addExtra" />
        <el-select v-model="newType"><el-option v-for="type in CONFIG_VALUE_TYPES" :key="type" :label="t(`workflowConfig.valueTypes.${type}`)" :value="type" /></el-select>
        <el-button type="primary" plain @click="addExtra">{{ t('workflowConfig.addParameter') }}</el-button>
      </div>
      <small v-if="keyError" class="workflow-config-error">{{ keyError }}</small>
    </section>
  </div>
</template>

<script setup>
import { computed, ref, watch } from 'vue'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import WorkflowConfigValueEditor from './WorkflowConfigValueEditor.vue'
import { compatibleWorkflowModelId, compatibleWorkflowResourceId, CONDITION_OPERATORS, CONFIG_VALUE_TYPES, cloneConfig, createConfigValue, extraConfigKeys, filterWorkflowConnectionOptions, filterWorkflowModelOptions, isSafeConfigKey, missingNodeConfigRequirements, nodeConfigFields, workflowConnectionOptionLabel, workflowMailRouteOptionLabel, workflowModelOptionLabel } from '../utils/workflowNodeConfig'

const props = defineProps({ modelValue: { type: Object, default: () => ({}) }, nodeType: { type: String, required: true } })
const emit = defineEmits(['update:modelValue'])
const { t, te } = useI18n()
const config = ref(cloneConfig(props.modelValue))
const openFields = ref([])
const openExtra = ref([])
const newKey = ref('')
const newType = ref('string')
const keyError = ref('')
const modelOptions = ref([])
const modelOptionsLoading = ref(false)
const modelOptionsLoaded = ref(false)
const modelOptionsError = ref(false)
const mailRouteOptions = ref([])
const mailRouteOptionsLoading = ref(false)
const mailRouteOptionsLoaded = ref(false)
const mailRouteOptionsError = ref(false)
const connectionOptions = ref([])
const connectionOptionsLoading = ref(false)
const connectionOptionsLoaded = ref(false)
const connectionOptionsError = ref(false)
const fields = computed(() => nodeConfigFields(props.nodeType))
const extraKeys = computed(() => extraConfigKeys(config.value, props.nodeType))
const missingRequirements = computed(() => missingNodeConfigRequirements(props.nodeType, config.value))
const requiredHint = computed(() => t('workflowConfig.requiredHint', { fields: missingRequirements.value.map(requirementLabel).join(t('workflowConfig.fieldSeparator')) }))
const currentModelType = computed(() => config.value.modelType || 'text_model')
const compatibleModelOptions = computed(() => filterWorkflowModelOptions(modelOptions.value, currentModelType.value))
const compatibleConnectionOptions = computed(() => filterWorkflowConnectionOptions(connectionOptions.value, props.nodeType))
const hasModelSelector = computed(() => fields.value.some(field => field.editor === 'model'))
const hasMailRouteSelector = computed(() => fields.value.some(field => field.editor === 'mailRoute'))
const hasConnectionSelector = computed(() => fields.value.some(field => field.editor === 'connection'))

watch(() => props.modelValue, value => {
  const next = cloneConfig(value)
  if (JSON.stringify(next) !== JSON.stringify(config.value)) config.value = next
}, { deep: true })
watch(hasModelSelector, enabled => { if (enabled) loadModelOptions() }, { immediate: true })
watch(hasMailRouteSelector, enabled => { if (enabled) loadMailRouteOptions() }, { immediate: true })
watch(hasConnectionSelector, enabled => { if (enabled) loadConnectionOptions() }, { immediate: true })
watch([currentModelType, compatibleModelOptions], () => {
  if (!hasModelSelector.value || !modelOptionsLoaded.value || !hasField('modelId')) return
  if (compatibleWorkflowModelId(modelOptions.value, currentModelType.value, config.value.modelId) === null) removeField('modelId')
})
watch([hasMailRouteSelector, mailRouteOptions], () => {
  if (!hasMailRouteSelector.value) return
  if (!mailRouteOptionsLoaded.value || !hasField('routeId')) return
  if (compatibleWorkflowResourceId(mailRouteOptions.value, config.value.routeId) === null) removeField('routeId')
})
watch([hasConnectionSelector, compatibleConnectionOptions], () => {
  if (!hasConnectionSelector.value) return
  if (!connectionOptionsLoaded.value || !hasField('connectionId')) return
  if (compatibleWorkflowResourceId(compatibleConnectionOptions.value, config.value.connectionId) === null) removeField('connectionId')
})

/** 按需加载 AI 节点可选择的启用模型，失败时保留当前配置避免误清理。 */
async function loadModelOptions() {
  if (modelOptionsLoaded.value || modelOptionsLoading.value) return
  modelOptionsLoading.value = true; modelOptionsError.value = false
  try { modelOptions.value = (await http.get('/workflow/model-options')).data || []; modelOptionsLoaded.value = true }
  catch { modelOptionsError.value = true }
  finally { modelOptionsLoading.value = false }
}

/** 按需加载邮件节点可选择的可发送路由，失败时保留当前配置。 */
async function loadMailRouteOptions() {
  if (mailRouteOptionsLoaded.value || mailRouteOptionsLoading.value) return
  mailRouteOptionsLoading.value = true; mailRouteOptionsError.value = false
  try { mailRouteOptions.value = (await http.get('/workflow/mail-route-options')).data || []; mailRouteOptionsLoaded.value = true }
  catch { mailRouteOptionsError.value = true }
  finally { mailRouteOptionsLoading.value = false }
}

/** 按需加载当前用户拥有的启用连接，失败时保留当前配置。 */
async function loadConnectionOptions() {
  if (connectionOptionsLoaded.value || connectionOptionsLoading.value) return
  connectionOptionsLoading.value = true; connectionOptionsError.value = false
  try { connectionOptions.value = (await http.get('/workflow/connection-options')).data || []; connectionOptionsLoaded.value = true }
  catch { connectionOptionsError.value = true }
  finally { connectionOptionsLoading.value = false }
}

/** 返回字段是否已写入当前配置。 */
function hasField(key) { return Object.prototype.hasOwnProperty.call(config.value, key) }
/** 判断字段定义是否声明默认值，包含 null、false、零和空容器。 */
function hasDefault(field) { return Object.prototype.hasOwnProperty.call(field, 'defaultValue') }
/** 判断字段是否允许由用户启用或禁用；固定必填字段始终启用。 */
function fieldCanToggle(field) { return field.requirement !== 'required' }
/** 判断可选或条件必填字段当前是否处于禁用状态。 */
function fieldDisabled(field) { return fieldCanToggle(field) && !hasField(field.key) }
/** 返回非缺失字段的配置状态文案键。 */
function fieldStatusKey(field) {
  if (hasField(field.key)) return 'workflowConfig.configured'
  if (fieldCanToggle(field)) return 'workflowConfig.disabled'
  return hasDefault(field) ? 'workflowConfig.defaultValue' : 'workflowConfig.notConfigured'
}
/** 返回已配置值或仅供编辑展示的字段默认值，不因展开卡片写入配置。 */
function fieldValue(field) { return hasField(field.key) ? config.value[field.key] : cloneValue(field.defaultValue) }
/** 深复制可序列化字段值，避免可视化编辑器污染共享默认值。 */
function cloneValue(value) { return value === undefined ? null : JSON.parse(JSON.stringify(value)) }
/** 返回当前语言下的标准字段名称。 */
function fieldLabel(key) { const path = `workflowConfig.fieldLabels.${key}`; return te(path) ? t(path) : key }
/** 返回当前语言下的标准字段说明。 */
function fieldDescription(key) { return t('workflowConfig.standardParameterDescription', { key }) }
/** 返回必填汇总中使用的本地化字段或组合条件名称。 */
function requirementLabel(key) { const path = `workflowConfig.requirementLabels.${key}`; return te(path) ? t(path) : fieldLabel(key) }
/** 判断当前卡片对应的必填或条件必填要求是否仍未满足。 */
function fieldRequirementMissing(field) {
  if (missingRequirements.value.includes(field.key)) return true
  if (props.nodeType === 'DOCUMENT_EXTRACTOR' && ['content', 'base64'].includes(field.key)) return missingRequirements.value.includes('contentOrBase64')
  if (props.nodeType === 'RABBITMQ_PUBLISH' && ['exchange', 'routingKey'].includes(field.key)) return missingRequirements.value.includes('rabbitDestination')
  return false
}
/** 返回选择项的本地化名称，缺少文案时显示原始值。 */
function fieldOption(key, option) { const path = `workflowConfig.options.${key}.${option}`; return te(path) ? t(path) : option }
/** 展开或收起标准字段卡片。 */
function toggleField(key) { openFields.value = openFields.value.includes(key) ? openFields.value.filter(item => item !== key) : [...openFields.value, key] }
/** 展开或收起附加参数卡片。 */
function toggleExtra(key) { openExtra.value = openExtra.value.includes(key) ? openExtra.value.filter(item => item !== key) : [...openExtra.value, key] }
/** 更新配置字段并向父组件发送隔离副本。 */
function setField(key, value) { config.value = { ...config.value, [key]: value }; emit('update:modelValue', cloneConfig(config.value)) }
/** 规范数字字段后更新配置。 */
function setNumber(key, value) { setField(key, Number.isFinite(value) ? value : 0) }
/** 启用时写入隔离的默认值，禁用时从配置中移除字段。 */
function toggleConfigured(field, enabled) { enabled ? setField(field.key, cloneValue(field.defaultValue)) : removeField(field.key) }
/** 保存下拉选择的模型 ID；清空选择时移除可选配置并回退能力路由。 */
function setModelId(value) { value === null || value === undefined || value === '' ? removeField('modelId') : setField('modelId', Number(value)) }
/** 保存资源下拉选择的数字 ID；清空选择时移除字段并恢复缺失提示。 */
function setResourceId(key, value) { value === null || value === undefined || value === '' ? removeField(key) : setField(key, Number(value)) }
/** 删除配置字段并保留其他未知字段。 */
function removeField(key) { const next = { ...config.value }; delete next[key]; config.value = next; emit('update:modelValue', cloneConfig(next)) }
/** 返回结构化条件并补齐默认操作符。 */
function condition(field) { const value = fieldValue(field); return value && typeof value === 'object' && !Array.isArray(value) ? { operator: 'EQ', ...value } : { left: '', operator: 'EQ', right: '' } }
/** 更新结构化条件的一个组成部分。 */
function setCondition(field, part, value) { setField(field.key, { ...condition(field), [part]: value }) }
/** 校验后添加附加参数卡片。 */
function addExtra() {
  const key = newKey.value.trim()
  if (!isSafeConfigKey(key)) { keyError.value = t('workflowConfig.invalidParameterName'); return }
  if (Object.prototype.hasOwnProperty.call(config.value, key)) { keyError.value = t('workflowConfig.duplicateParameter'); return }
  setField(key, createConfigValue(newType.value)); openExtra.value = [...openExtra.value, key]; newKey.value = ''; keyError.value = ''
}
</script>

<style scoped>
.workflow-config-editor { display: flex; flex-direction: column; gap: 16px; }
.workflow-config-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.workflow-config-card { min-width: 0; overflow: hidden; border: 1px solid #e2e8f0; border-radius: 12px; background: #f8fafc; }
.workflow-config-card.configured { border-color: #b8cdf8; background: #f7faff; }
.workflow-config-card.disabled { border-color: #d8dee9; background: #fafbfc; }
.workflow-config-card.required-missing { border-color: #efb2b2; background: #fffafa; }
.workflow-config-card-head { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; padding: 14px; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.workflow-config-card-head > span:first-child, .workflow-extra-head > div { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.workflow-config-card-head strong em { margin-left: 4px; color: var(--el-color-danger); font-size: 12px; font-style: normal; font-weight: 500; }
.workflow-config-card-tags { display: flex; flex: 0 0 auto; gap: 6px; }
.workflow-config-card-head small, .workflow-extra-head small { color: var(--app-muted); line-height: 1.45; }
.workflow-config-card-body { display: flex; flex-direction: column; gap: 12px; padding: 0 14px 14px; border-top: 1px solid #e8edf5; }
.workflow-config-card-body > :first-child { margin-top: 14px; }
.workflow-config-enable { display: flex; align-items: center; justify-content: space-between; }
.workflow-config-controls { display: flex; min-width: 0; flex-direction: column; gap: 12px; margin: 0; padding: 0; border: 0; }
.workflow-config-controls:disabled { opacity: .65; pointer-events: none; }
.workflow-model-option-label { display: block; width: 100%; min-width: 0; }
.workflow-condition-editor { display: flex; flex-direction: column; gap: 10px; }
.workflow-extra-config { display: flex; flex-direction: column; gap: 10px; padding-top: 4px; }
.workflow-extra-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.workflow-extra-add { display: grid; grid-template-columns: minmax(140px, 1fr) 140px auto; gap: 8px; }
.workflow-config-error { color: var(--el-color-danger); }
@media (max-width: 720px) { .workflow-config-grid { grid-template-columns: 1fr; } .workflow-extra-add { grid-template-columns: 1fr; } }
</style>
