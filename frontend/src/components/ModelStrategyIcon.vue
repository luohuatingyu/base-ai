<template>
  <svg class="model-strategy-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
       stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-for="(path, index) in paths" :key="index">
      <circle v-if="path.kind === 'circle'" :cx="path.cx" :cy="path.cy" :r="path.r" />
      <path v-else :d="path.d" :fill="path.fill" :stroke="path.stroke" />
    </template>
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  strategy: { type: String, default: 'GENERIC' }
})

/** 通用兼容策略：用中心节点连接多个外围节点，表达跨供应商适配能力。 */
const GENERIC = [
  { d: 'M12 12 5.5 6M12 12l6.5-6M12 12l6.5 6M12 12l-6.5 6' },
  { kind: 'circle', cx: 12, cy: 12, r: 3.1 },
  { kind: 'circle', cx: 5, cy: 5.5, r: 2 },
  { kind: 'circle', cx: 19, cy: 5.5, r: 2 },
  { kind: 'circle', cx: 19, cy: 18.5, r: 2 },
  { kind: 'circle', cx: 5, cy: 18.5, r: 2 }
]

/** OpenAI 策略：用对话框表达聊天兼容协议，不复刻品牌标志。 */
const OPENAI = [
  { d: 'M5 6.5A2.5 2.5 0 0 1 7.5 4h9A2.5 2.5 0 0 1 19 6.5v6a2.5 2.5 0 0 1-2.5 2.5H12l-4.5 4v-4H7.5A2.5 2.5 0 0 1 5 12.5v-6Z' },
  { d: 'M8.5 9.5h7M8.5 12h4.5' }
]

/** 火山方舟策略：用上行箭头表达面向生成服务的请求通道。 */
const ARK = [
  { d: 'M5 18 12 5l7 13-7-3.5L5 18Z' },
  { d: 'M12 5v7.5' }
]

/** Gemini 策略：用四向闪光表达多模态生成能力。 */
const GEMINI = [
  { d: 'm12 3 1.7 6.3L20 11l-6.3 1.7L12 19l-1.7-6.3L4 11l6.3-1.7L12 3Z' },
  { d: 'm19 16 .7 2.3L22 19l-2.3.7L19 22l-.7-2.3L16 19l2.3-.7L19 16Z' }
]

/** 策略编码到语义图标的映射；新增或未知策略安全回退为通用图标。 */
const STRATEGY_ICONS = { GENERIC, OPENAI, ARK, GEMINI }
const paths = computed(() => STRATEGY_ICONS[String(props.strategy || 'GENERIC').toUpperCase()] || GENERIC)
</script>
