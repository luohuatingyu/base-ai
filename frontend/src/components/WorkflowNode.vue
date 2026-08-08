<template>
  <div class="workflow-node" :class="[`workflow-node--${nodeType.toLowerCase()}`, { 'has-missing-config': missingRequirements.length }]">
    <Handle v-if="!entryTypes.has(nodeType)" type="target" :position="Position.Left" />
    <strong>{{ data?.label || nodeType }}</strong>
    <small>{{ nodeType }}</small>
    <span v-if="missingRequirements.length" class="workflow-node-config-warning" :title="missingHint">
      {{ t('workflowConfig.requiredMissing') }}
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

const props = defineProps({ type: { type: String, required: true }, data: { type: Object, default: () => ({}) } })
const { t, te } = useI18n()
const nodeType = computed(() => props.data?.nodeType || props.type)
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
.workflow-node { position: relative; min-width: 150px; display: flex; flex-direction: column; gap: 4px; padding: 12px 16px; border: 2px solid #8aa8ec; border-radius: 10px; background: #fff; box-shadow: 0 7px 20px rgba(31, 53, 91, 0.12); }
.workflow-node strong { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-node small { color: #748198; font-size: 10px; letter-spacing: .8px; }
.workflow-node.has-missing-config { border-color: var(--el-color-danger); background: var(--el-color-danger-light-9); }
.workflow-node-config-warning { align-self: flex-start; margin-top: 3px; padding: 2px 6px; border-radius: 999px; color: #fff; background: var(--el-color-danger); font-size: 10px; line-height: 1.4; cursor: help; }
.workflow-node--start { border-color: #34a675; background: #effcf6; }
.workflow-node--end { border-color: #e06b72; background: #fff5f5; }
.workflow-node--condition, .workflow-node--loop, .workflow-node--iteration { border-color: #d29b35; background: #fffbeb; }
.workflow-node--agent { border-color: #8b6ef6; background: #f8f5ff; }
</style>
