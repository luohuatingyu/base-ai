<template>
  <div class="workflow-node" :class="[`workflow-node--${nodeType.toLowerCase()}`, { 'has-missing-config': missingRequirements.length }]">
    <Handle v-if="!entryTypes.has(nodeType)" type="target" :position="Position.Left" />
    <span class="workflow-node-icon" :style="categoryStyle">{{ iconText }}</span>
    <span class="workflow-node-content">
      <strong>{{ data?.label || nodeType }}</strong>
      <small>{{ nodeType }}</small>
      <span v-if="missingRequirements.length" class="workflow-node-config-warning" :title="missingHint">
        {{ t('workflowConfig.requiredMissing') }}
      </span>
    </span>
    <template v-if="sourceHandles.length">
      <Handle v-for="(handle, index) in sourceHandles" :id="handle" :key="handle" type="source"
              :position="Position.Right" :style="{ top: `${((index + 1) * 100) / (sourceHandles.length + 1)}%` }" />
    </template>
    <Handle v-else-if="nodeType !== 'END'" type="source" :position="Position.Right" />
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { Handle, Position } from '@vue-flow/core'
import { useI18n } from 'vue-i18n'
import { missingNodeConfigRequirements } from '../utils/workflowNodeConfig'
import { workflowTemplateCategoryStyle } from '../utils/workflowTemplateCatalog'

const props = defineProps({ type: { type: String, required: true }, data: { type: Object, default: () => ({}) } })
const { t, te } = useI18n()
const nodeType = computed(() => props.data?.nodeType || props.type)
const categoryStyle = computed(() => workflowTemplateCategoryStyle(props.data?.functionalCategory, nodeType.value))
const iconText = computed(() => String(props.data?.label || nodeType.value).trim().slice(0, 2).toUpperCase() || '·')
const missingRequirements = computed(() => missingNodeConfigRequirements(nodeType.value, props.data?.config))
const missingHint = computed(() => `${t('workflowConfig.requiredMissing')}：${missingRequirements.value.map(requirementLabel).join(t('workflowConfig.fieldSeparator'))}`)
const entryTypes = new Set(['START', 'WEBHOOK_TRIGGER', 'SCHEDULE_TRIGGER', 'KAFKA_TRIGGER', 'RABBITMQ_TRIGGER'])
const sourceHandles = computed(() => {
  if (nodeType.value === 'CONDITION') return ['true', 'false']
  if (nodeType.value === 'SWITCH') return [...(props.data?.config?.cases || []).map((item, index) => item.branch || `case-${index}`), props.data?.config?.defaultBranch || 'default']
  if (nodeType.value === 'QUESTION_CLASSIFIER') return (props.data?.config?.categories || []).map(item => item.name).filter(Boolean)
  return props.data?.config?.onError === 'BRANCH' ? ['success', 'error'] : []
})

/** 返回节点缺失配置提示中的本地化字段名称。 */
function requirementLabel(key) {
  const requirementPath = `workflowConfig.requirementLabels.${key}`
  const fieldPath = `workflowConfig.fieldLabels.${key}`
  if (te(requirementPath)) return t(requirementPath)
  return te(fieldPath) ? t(fieldPath) : key
}
</script>

<style scoped>
.workflow-node { position: relative; min-width: 150px; display: grid; grid-template-columns: 36px minmax(0, 1fr); gap: 10px; align-items: center; padding: 12px 16px; border: 2px solid #8aa8ec; border-radius: 10px; background: #fff; box-shadow: 0 7px 20px rgba(31, 53, 91, 0.12); }
.workflow-node-icon { display: grid; place-items: center; width: 36px; height: 36px; border-radius: 9px; font-size: 11px; font-weight: 800; }
.workflow-node-content { min-width: 0; display: flex; flex-direction: column; gap: 4px; }
.workflow-node strong { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-node small { color: #748198; font-size: 10px; letter-spacing: .8px; }
.workflow-node.has-missing-config { border-color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.workflow-node-config-warning { align-self: flex-start; margin-top: 3px; padding: 2px 6px; border-radius: 999px; color: #fff; background: var(--el-color-danger); font-size: 10px; line-height: 1.4; cursor: help; }
:deep(.vue-flow__handle) { width: 40px; height: 40px; min-width: 40px; min-height: 40px; border: 0; border-radius: 50%; background: transparent; }
:deep(.vue-flow__handle::after) { position: absolute; top: 50%; left: 50%; width: 6px; height: 6px; box-sizing: border-box; border: 1px solid #fff; border-radius: 50%; background: var(--vf-handle, #555); content: ''; transform: translate(-50%, -50%); transition: width .12s ease, height .12s ease, background-color .12s ease, box-shadow .12s ease; }
:deep(.vue-flow__handle:hover::after),
:deep(.vue-flow__handle.connecting::after) { width: 12px; height: 12px; box-shadow: 0 0 0 6px rgb(59 130 246 / 18%); }
:deep(.vue-flow__handle.valid::after) { background: var(--el-color-success); box-shadow: 0 0 0 6px rgb(103 194 58 / 20%); }
</style>
