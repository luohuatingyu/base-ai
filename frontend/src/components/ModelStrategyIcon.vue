<template>
  <svg class="model-strategy-icon" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8"
       stroke-linecap="round" stroke-linejoin="round" aria-hidden="true">
    <template v-for="(path, index) in paths" :key="index">
      <circle v-if="path.kind === 'circle'" :cx="path.cx" :cy="path.cy" :r="path.r" :fill="path.fill" :stroke="path.stroke" :opacity="path.opacity" />
      <path v-else :d="path.d" :fill="path.fill" :stroke="path.stroke" :stroke-width="path.strokeWidth" :opacity="path.opacity" />
    </template>
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({
  strategy: { type: String, default: 'GENERIC' }
})

/** 通用兼容协议：用网络节点和双层核心表达跨供应商适配能力。 */
const GENERIC = [
  { d: 'M12 3.1a8.9 8.9 0 1 0 0 17.8 8.9 8.9 0 1 0 0-17.8Z', strokeWidth: 1.1, opacity: 0.32 },
  { d: 'M12 12 5.4 6.1M12 12l6.6-5.9M12 12l6.6 5.9M12 12l-6.6 5.9', strokeWidth: 1.45 },
  { kind: 'circle', cx: 12, cy: 12, r: 3.15, fill: 'currentColor', stroke: 'none', opacity: 0.92 },
  { kind: 'circle', cx: 12, cy: 12, r: 1.15, fill: 'var(--el-bg-color, #fff)', stroke: 'none' },
  { kind: 'circle', cx: 5, cy: 5.7, r: 2.05, fill: 'currentColor', stroke: 'none' },
  { kind: 'circle', cx: 19, cy: 5.7, r: 2.05, fill: 'currentColor', stroke: 'none' },
  { kind: 'circle', cx: 19, cy: 18.3, r: 2.05, fill: 'currentColor', stroke: 'none' },
  { kind: 'circle', cx: 5, cy: 18.3, r: 2.05, fill: 'currentColor', stroke: 'none' }
]

/** OpenAI 协议：使用交织环的品牌几何轮廓，并保留清晰的中心留白。 */
const OPENAI = [
  { d: 'M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231l-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813l-.0001 4.3576Z', fill: 'currentColor', stroke: 'none' }
]

/** 火山方舟协议：用立体方舟、上行箭头和航迹点表达生成通道。 */
const ARK = [
  { d: 'M12 2.8 20.7 7.7v8.6L12 21.2l-8.7-4.9V7.7L12 2.8Z', strokeWidth: 1.25, opacity: 0.45 },
  { d: 'M5.5 16.6 12 5l6.5 11.6-6.5-3.2-6.5 3.2Z', fill: 'currentColor', stroke: 'none', opacity: 0.94 },
  { d: 'M12 5v9.2M9.2 10.2 12 5l2.8 5.2', stroke: 'var(--el-bg-color, #fff)', strokeWidth: 1.25 },
  { kind: 'circle', cx: 18.7, cy: 5.2, r: 1.05, fill: 'currentColor', stroke: 'none' }
]

/** Gemini 协议：使用多层四向光芒表达多模态生成能力。 */
const GEMINI = [
  { d: 'M11.04 19.32Q12 21.51 12 24q0-2.49.93-4.68.96-2.19 2.58-3.81t3.81-2.55Q21.51 12 24 12q-2.49 0-4.68-.93a12.3 12.3 0 0 1-3.81-2.58 12.3 12.3 0 0 1-2.58-3.81Q12 2.49 12 0q0 2.49-.96 4.68-.93 2.19-2.55 3.81a12.3 12.3 0 0 1-3.81 2.58Q2.49 12 0 12q2.49 0 4.68.96 2.19.93 3.81 2.55t2.55 3.81', fill: 'currentColor', stroke: 'none' },
  { d: 'M12 7.1 13.2 10.8 16.9 12l-3.7 1.2L12 16.9l-1.2-3.7L7.1 12l3.7-1.2L12 7.1Z', fill: 'var(--el-bg-color, #fff)', stroke: 'none', opacity: 0.72 }
]

/** MiniMax 协议：用带胶片边框的画面、山峰和高光表达媒体生成。 */
const MINIMAX = [
  { d: 'M5.2 4.5h13.6a1.2 1.2 0 0 1 1.2 1.2v12.6a1.2 1.2 0 0 1-1.2 1.2H5.2A1.2 1.2 0 0 1 4 18.3V5.7a1.2 1.2 0 0 1 1.2-1.2Z', strokeWidth: 1.35 },
  { d: 'M7.1 16.7 11.3 9l2.7 4.8 1.5-2.1 2.5 5M8.1 7.5h.01M15.9 7.5h.01', strokeWidth: 1.55 },
  { d: 'M4 8h2.2M17.8 8H20M4 16h2.2M17.8 16H20', strokeWidth: 1.25, opacity: 0.68 }
]

/** Vidu 协议：用镜头框、V 形画面和播放光点表达视频生成。 */
const VIDU = [
  { d: 'M5.2 4.5h13.6a1.3 1.3 0 0 1 1.3 1.3v12.4a1.3 1.3 0 0 1-1.3 1.3H5.2a1.3 1.3 0 0 1-1.3-1.3V5.8a1.3 1.3 0 0 1 1.3-1.3Z', strokeWidth: 1.25, opacity: 0.42 },
  { d: 'M6.4 7.2 10.1 16l1.9 1.7 1.9-1.7 3.7-8.8', strokeWidth: 1.9 },
  { d: 'M16.9 6.7 18.4 8.2 16.9 9.7 15.4 8.2 16.9 6.7Z', fill: 'currentColor', stroke: 'none' },
  { d: 'M8.2 19.5h2.2M13.6 19.5h2.2', strokeWidth: 1.15, opacity: 0.7 }
]

/** 协议编码到语义图标的映射；新增或未知协议安全回退为通用图标。 */
const STRATEGY_ICONS = { GENERIC, OPENAI, ARK, GEMINI, MINIMAX, VIDU }
const paths = computed(() => STRATEGY_ICONS[String(props.strategy || 'GENERIC').toUpperCase()] || GENERIC)
</script>
