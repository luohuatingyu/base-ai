<template>
  <div class="workflow-config-editor">
    <el-alert :title="t('workflowConfig.visualHint')" type="info" show-icon :closable="false" />
    <div v-if="fields.length" class="workflow-config-grid">
      <article v-for="field in fields" :key="field.key" class="workflow-config-card" :class="{ configured: hasField(field.key) }">
        <button type="button" class="workflow-config-card-head" @click="toggleField(field.key)">
          <span><strong>{{ fieldLabel(field.key) }}</strong><small>{{ fieldDescription(field.key) }}</small></span>
          <el-tag size="small" :type="hasField(field.key) ? 'success' : 'info'">{{ hasField(field.key) ? t('workflowConfig.configured') : t('workflowConfig.notConfigured') }}</el-tag>
        </button>
        <div v-if="openFields.includes(field.key)" class="workflow-config-card-body">
          <div class="workflow-config-enable"><span>{{ t('workflowConfig.enableField') }}</span><el-switch :model-value="hasField(field.key)" @change="toggleConfigured(field)" /></div>
          <template v-if="hasField(field.key)">
            <el-input v-if="['text','textarea'].includes(field.editor)" :model-value="config[field.key]"
                      :type="field.editor === 'textarea' ? 'textarea' : 'text'" :rows="4" @update:model-value="setField(field.key, $event)" />
            <el-input-number v-else-if="field.editor === 'number'" :model-value="config[field.key]" controls-position="right" @update:model-value="setNumber(field.key, $event)" />
            <el-switch v-else-if="field.editor === 'boolean'" :model-value="config[field.key]" @update:model-value="setField(field.key, $event)" />
            <el-select v-else-if="field.editor === 'select'" :model-value="config[field.key]" @update:model-value="setField(field.key, $event)">
              <el-option v-for="option in field.options" :key="option" :label="fieldOption(field.key, option)" :value="option" />
            </el-select>
            <div v-else-if="field.editor === 'condition'" class="workflow-condition-editor">
              <el-input :model-value="condition(field.key).left" :placeholder="t('workflowConfig.conditionLeft')" @update:model-value="setCondition(field.key, 'left', $event)" />
              <el-select :model-value="condition(field.key).operator" @update:model-value="setCondition(field.key, 'operator', $event)">
                <el-option v-for="operator in CONDITION_OPERATORS" :key="operator" :label="t(`workflowConfig.operators.${operator}`)" :value="operator" />
              </el-select>
              <WorkflowConfigValueEditor v-if="!['EXISTS','EMPTY'].includes(condition(field.key).operator)" :model-value="condition(field.key).right" @update:model-value="setCondition(field.key, 'right', $event)" />
            </div>
            <WorkflowConfigValueEditor v-else :model-value="config[field.key]" @update:model-value="setField(field.key, $event)" />
          </template>
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
import WorkflowConfigValueEditor from './WorkflowConfigValueEditor.vue'
import { CONDITION_OPERATORS, CONFIG_VALUE_TYPES, cloneConfig, createConfigValue, extraConfigKeys, isSafeConfigKey, nodeConfigFields } from '../utils/workflowNodeConfig'

const props = defineProps({ modelValue: { type: Object, default: () => ({}) }, nodeType: { type: String, required: true } })
const emit = defineEmits(['update:modelValue'])
const { t, te } = useI18n()
const config = ref(cloneConfig(props.modelValue))
const openFields = ref([])
const openExtra = ref([])
const newKey = ref('')
const newType = ref('string')
const keyError = ref('')
const fields = computed(() => nodeConfigFields(props.nodeType))
const extraKeys = computed(() => extraConfigKeys(config.value, props.nodeType))

watch(() => props.modelValue, value => {
  const next = cloneConfig(value)
  if (JSON.stringify(next) !== JSON.stringify(config.value)) config.value = next
}, { deep: true })

/** 返回字段是否已写入当前配置。 */
function hasField(key) { return Object.prototype.hasOwnProperty.call(config.value, key) }
/** 返回当前语言下的标准字段名称。 */
function fieldLabel(key) { const path = `workflowConfig.fieldLabels.${key}`; return te(path) ? t(path) : key }
/** 返回当前语言下的标准字段说明。 */
function fieldDescription(key) { return t('workflowConfig.standardParameterDescription', { key }) }
/** 返回选择项的本地化名称，缺少文案时显示原始值。 */
function fieldOption(key, option) { const path = `workflowConfig.options.${key}.${option}`; return te(path) ? t(path) : option }
/** 展开或收起标准字段卡片。 */
function toggleField(key) { openFields.value = openFields.value.includes(key) ? openFields.value.filter(item => item !== key) : [...openFields.value, key] }
/** 展开或收起附加参数卡片。 */
function toggleExtra(key) { openExtra.value = openExtra.value.includes(key) ? openExtra.value.filter(item => item !== key) : [...openExtra.value, key] }
/** 启用或移除标准配置字段。 */
function toggleConfigured(field) { hasField(field.key) ? removeField(field.key) : setField(field.key, JSON.parse(JSON.stringify(field.defaultValue))) }
/** 更新配置字段并向父组件发送隔离副本。 */
function setField(key, value) { config.value = { ...config.value, [key]: value }; emit('update:modelValue', cloneConfig(config.value)) }
/** 规范数字字段后更新配置。 */
function setNumber(key, value) { setField(key, Number.isFinite(value) ? value : 0) }
/** 删除配置字段并保留其他未知字段。 */
function removeField(key) { const next = { ...config.value }; delete next[key]; config.value = next; emit('update:modelValue', cloneConfig(next)) }
/** 返回结构化条件并补齐默认操作符。 */
function condition(key) { const value = config.value[key]; return value && typeof value === 'object' && !Array.isArray(value) ? { operator: 'EQ', ...value } : { left: '', operator: 'EQ', right: '' } }
/** 更新结构化条件的一个组成部分。 */
function setCondition(key, part, value) { setField(key, { ...condition(key), [part]: value }) }
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
.workflow-config-card-head { display: flex; width: 100%; align-items: center; justify-content: space-between; gap: 12px; padding: 14px; border: 0; color: inherit; background: transparent; text-align: left; cursor: pointer; }
.workflow-config-card-head > span:first-child, .workflow-extra-head > div { display: flex; min-width: 0; flex-direction: column; gap: 4px; }
.workflow-config-card-head small, .workflow-extra-head small { color: var(--app-muted); line-height: 1.45; }
.workflow-config-card-body { display: flex; flex-direction: column; gap: 12px; padding: 0 14px 14px; border-top: 1px solid #e8edf5; }
.workflow-config-card-body > :first-child { margin-top: 14px; }
.workflow-config-enable { display: flex; align-items: center; justify-content: space-between; }
.workflow-condition-editor { display: flex; flex-direction: column; gap: 10px; }
.workflow-extra-config { display: flex; flex-direction: column; gap: 10px; padding-top: 4px; }
.workflow-extra-head { display: flex; align-items: center; justify-content: space-between; gap: 16px; }
.workflow-extra-add { display: grid; grid-template-columns: minmax(140px, 1fr) 140px auto; gap: 8px; }
.workflow-config-error { color: var(--el-color-danger); }
@media (max-width: 720px) { .workflow-config-grid { grid-template-columns: 1fr; } .workflow-extra-add { grid-template-columns: 1fr; } }
</style>
