<template>
  <div class="workflow-node" :class="`workflow-node--${nodeType.toLowerCase()}`">
    <Handle v-if="!entryTypes.has(nodeType)" type="target" :position="Position.Left" />
    <strong>{{ data?.label || nodeType }}</strong>
    <small>{{ nodeType }}</small>
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

const props = defineProps({ type: { type: String, required: true }, data: { type: Object, default: () => ({}) } })
const nodeType = computed(() => props.data?.nodeType || props.type)
const entryTypes = new Set(['START', 'WEBHOOK_TRIGGER', 'SCHEDULE_TRIGGER', 'KAFKA_TRIGGER', 'RABBITMQ_TRIGGER'])
const sourceHandles = computed(() => {
  if (nodeType.value === 'CONDITION') return ['true', 'false']
  if (nodeType.value === 'SWITCH') return [...(props.data?.config?.cases || []).map((item, index) => item.branch || `case-${index}`), props.data?.config?.defaultBranch || 'default']
  if (nodeType.value === 'QUESTION_CLASSIFIER') return (props.data?.config?.categories || []).map(item => item.name).filter(Boolean)
  return props.data?.config?.onError === 'BRANCH' ? ['success', 'error'] : []
})
</script>

<style scoped>
.workflow-node { min-width: 150px; display: flex; flex-direction: column; gap: 4px; padding: 12px 16px; border: 2px solid #8aa8ec; border-radius: 10px; background: #fff; box-shadow: 0 7px 20px rgba(31, 53, 91, 0.12); }
.workflow-node strong { max-width: 180px; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.workflow-node small { color: #748198; font-size: 10px; letter-spacing: .8px; }
.workflow-node--start { border-color: #34a675; background: #effcf6; }
.workflow-node--end { border-color: #e06b72; background: #fff5f5; }
.workflow-node--condition, .workflow-node--loop, .workflow-node--iteration { border-color: #d29b35; background: #fffbeb; }
.workflow-node--agent { border-color: #8b6ef6; background: #f8f5ff; }
</style>
