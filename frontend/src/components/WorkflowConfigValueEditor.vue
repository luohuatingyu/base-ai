<template>
  <div class="config-value-editor">
    <div class="config-value-toolbar">
      <el-select :model-value="valueType" :disabled="lockedType" @change="changeType">
        <el-option v-for="type in CONFIG_VALUE_TYPES" :key="type" :label="t(`workflowConfig.valueTypes.${type}`)" :value="type" />
      </el-select>
    </div>

    <el-input v-if="valueType === 'string'" :model-value="modelValue" :type="multiline ? 'textarea' : 'text'"
              :rows="multiline ? 4 : undefined" @update:model-value="update" />
    <el-input-number v-else-if="valueType === 'number'" :model-value="modelValue" controls-position="right" @update:model-value="updateNumber" />
    <el-switch v-else-if="valueType === 'boolean'" :model-value="modelValue" @update:model-value="update" />
    <el-alert v-else-if="valueType === 'null'" :title="t('workflowConfig.nullValue')" type="info" :closable="false" />

    <template v-else-if="valueType === 'object'">
      <div v-if="depth >= maxDepth" class="config-depth-warning">{{ t('workflowConfig.depthLimit') }}</div>
      <div v-else class="config-nested-list">
        <article v-for="[key, value] in objectEntries" :key="key" class="config-nested-card">
          <div class="config-nested-head"><strong>{{ key }}</strong><el-button link type="danger" @click="removeObjectItem(key)">{{ t('common.delete') }}</el-button></div>
          <WorkflowConfigValueEditor :model-value="value" :depth="depth + 1" :max-depth="maxDepth" @update:model-value="setObjectItem(key, $event)" />
        </article>
        <div class="config-add-row">
          <el-input v-model="newKey" :placeholder="t('workflowConfig.parameterName')" @keyup.enter="addObjectItem" />
          <el-select v-model="newType"><el-option v-for="type in CONFIG_VALUE_TYPES" :key="type" :label="t(`workflowConfig.valueTypes.${type}`)" :value="type" /></el-select>
          <el-button type="primary" plain @click="addObjectItem">{{ t('common.add') }}</el-button>
        </div>
        <small v-if="keyError" class="config-error">{{ keyError }}</small>
      </div>
    </template>

    <template v-else-if="valueType === 'array'">
      <div v-if="depth >= maxDepth" class="config-depth-warning">{{ t('workflowConfig.depthLimit') }}</div>
      <div v-else class="config-nested-list">
        <article v-for="(value, index) in modelValue" :key="index" class="config-nested-card">
          <div class="config-nested-head"><strong>#{{ index + 1 }}</strong><el-button link type="danger" @click="removeArrayItem(index)">{{ t('common.delete') }}</el-button></div>
          <WorkflowConfigValueEditor :model-value="value" :depth="depth + 1" :max-depth="maxDepth" @update:model-value="setArrayItem(index, $event)" />
        </article>
        <div class="config-add-row config-add-row--array">
          <el-select v-model="newType"><el-option v-for="type in CONFIG_VALUE_TYPES" :key="type" :label="t(`workflowConfig.valueTypes.${type}`)" :value="type" /></el-select>
          <el-button type="primary" plain @click="addArrayItem">{{ t('workflowConfig.addItem') }}</el-button>
        </div>
      </div>
    </template>
  </div>
</template>

<script setup>
import { computed, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { CONFIG_VALUE_TYPES, configValueType, createConfigValue, isSafeConfigKey } from '../utils/workflowNodeConfig'

defineOptions({ name: 'WorkflowConfigValueEditor' })
const props = defineProps({
  modelValue: { default: null },
  depth: { type: Number, default: 0 },
  maxDepth: { type: Number, default: 8 },
  lockedType: { type: Boolean, default: false },
  multiline: { type: Boolean, default: false }
})
const emit = defineEmits(['update:modelValue'])
const { t } = useI18n()
const newKey = ref('')
const newType = ref('string')
const keyError = ref('')
const valueType = computed(() => configValueType(props.modelValue))
const objectEntries = computed(() => Object.entries(props.modelValue || {}))

/** 更新当前值。 */
function update(value) { emit('update:modelValue', value) }
/** 将空数字控件值规范为零。 */
function updateNumber(value) { emit('update:modelValue', Number.isFinite(value) ? value : 0) }
/** 切换值类型并生成对应空值。 */
function changeType(type) { emit('update:modelValue', createConfigValue(type)) }
/** 更新对象中的单个参数。 */
function setObjectItem(key, value) { emit('update:modelValue', { ...(props.modelValue || {}), [key]: value }) }
/** 删除对象中的单个参数。 */
function removeObjectItem(key) { const next = { ...(props.modelValue || {}) }; delete next[key]; emit('update:modelValue', next) }
/** 校验并新增对象参数。 */
function addObjectItem() {
  const key = newKey.value.trim()
  if (!isSafeConfigKey(key)) { keyError.value = t('workflowConfig.invalidParameterName'); return }
  if (Object.prototype.hasOwnProperty.call(props.modelValue || {}, key)) { keyError.value = t('workflowConfig.duplicateParameter'); return }
  setObjectItem(key, createConfigValue(newType.value)); newKey.value = ''; keyError.value = ''
}
/** 更新数组中的单个元素。 */
function setArrayItem(index, value) { const next = [...props.modelValue]; next[index] = value; emit('update:modelValue', next) }
/** 删除数组中的单个元素。 */
function removeArrayItem(index) { emit('update:modelValue', props.modelValue.filter((_, itemIndex) => itemIndex !== index)) }
/** 按所选类型新增数组元素。 */
function addArrayItem() { emit('update:modelValue', [...props.modelValue, createConfigValue(newType.value)]) }
</script>

<style scoped>
.config-value-editor { display: flex; min-width: 0; flex-direction: column; gap: 10px; }
.config-value-toolbar { display: flex; justify-content: flex-end; }
.config-value-toolbar .el-select { width: 128px; }
.config-nested-list { display: flex; flex-direction: column; gap: 10px; }
.config-nested-card { min-width: 0; padding: 12px; border: 1px solid #e2e8f0; border-radius: 10px; background: #fff; }
.config-nested-head { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-bottom: 10px; }
.config-nested-head strong { overflow-wrap: anywhere; }
.config-add-row { display: grid; grid-template-columns: minmax(120px, 1fr) 128px auto; gap: 8px; }
.config-add-row--array { grid-template-columns: 128px auto; justify-content: end; }
.config-error { color: var(--el-color-danger); }
.config-depth-warning { padding: 10px; border-radius: 8px; color: #92400e; background: #fffbeb; }
@media (max-width: 600px) { .config-add-row { grid-template-columns: 1fr; } .config-add-row--array { grid-template-columns: 1fr; } }
</style>
