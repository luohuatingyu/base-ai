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

/** OpenAI 策略：用交织的六边形环表达 OpenAI 原生协议。 */
const OPENAI = [
  { d: 'M12 3.5 16.5 6v5L12 13.5 7.5 11V6L12 3.5ZM12 10.5 16.5 13v5L12 20.5 7.5 18v-5L12 10.5Z' },
  { d: 'M7.5 6 3.5 8.3v5.2l4 2.3M16.5 6l4 2.3v5.2l-4 2.3' }
]

/** 火山方舟策略：用上行箭头表达面向生成服务的请求通道。 */
const ARK = [
  { d: 'M5 18 12 5l7 13-7-3.5L5 18Z' },
  { d: 'M12 5v7.5' }
]

/** Gemini 策略：用单个四角星表达 Gemini 原生多模态协议。 */
const GEMINI = [
  { d: 'M12 2.8 14.9 9.1 21.2 12l-6.3 2.9L12 21.2l-2.9-6.3L2.8 12l6.3-2.9L12 2.8Z', fill: 'currentColor', stroke: 'none' },
  { d: 'M12 6.5 13.9 10.1 17.5 12l-3.6 1.9L12 17.5l-1.9-3.6L6.5 12l3.6-1.9L12 6.5Z', fill: 'var(--el-bg-color, #fff)', stroke: 'none' }
]

/** MiniMax 策略：用方框和中心切口表达图像与视频生成。 */
const MINIMAX = [
  { d: 'M5 4.5h14v15H5zM8 16l4-8 4 8M9.5 13h5' }
]

/** Vidu 策略：用 V 形镜头和上方光点表达视频生成。 */
const VIDU = [
  { d: 'M4.5 6.5 9.7 17l2.3 2 2.3-2 5.2-10.5M17.5 4.5h.01' }
]

/** 策略编码到语义图标的映射；新增或未知策略安全回退为通用图标。 */
const STRATEGY_ICONS = { GENERIC, OPENAI, ARK, GEMINI, MINIMAX, VIDU }
const paths = computed(() => STRATEGY_ICONS[String(props.strategy || 'GENERIC').toUpperCase()] || GENERIC)
</script>
