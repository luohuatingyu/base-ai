<template>
  <svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" stroke-linecap="round"
       stroke-linejoin="round" aria-hidden="true">
    <template v-for="(path, index) in paths" :key="index">
      <circle v-if="path.kind === 'circle'" :cx="path.cx" :cy="path.cy" :r="path.r" />
      <ellipse v-else-if="path.kind === 'ellipse'" :cx="path.cx" :cy="path.cy" :rx="path.rx" :ry="path.ry" />
      <path v-else :d="path.d" />
    </template>
  </svg>
</template>

<script setup>
import { computed } from 'vue'

const props = defineProps({ type: { type: String, default: '' } })

/** 数据库圆柱体：关系型与向量数据库共用同一形态，用外层配色区分。 */
const DATABASE = [
  { kind: 'ellipse', cx: 12, cy: 6, rx: 8, ry: 3 },
  { d: 'M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6' },
  { d: 'M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3' }
]
/** Redis：三层堆叠结构。 */
const LAYERS = [
  { d: 'M12 3 4 7.5 12 12l8-4.5L12 3Z' },
  { d: 'M4 12l8 4.5L20 12' },
  { d: 'M4 16.5 12 21l8-4.5' }
]
/** S3：存储桶。 */
const BUCKET = [
  { kind: 'ellipse', cx: 12, cy: 5.5, rx: 7, ry: 2.5 },
  { d: 'M5 5.5 7 19a2 2 0 0 0 2 1.6h6A2 2 0 0 0 17 19l2-13.5' },
  { d: 'M6.2 11.5c1.5.9 3.6 1.4 5.8 1.4s4.3-.5 5.8-1.4' }
]
/** Kafka：管道与节点。 */
const PIPELINE = [
  { kind: 'circle', cx: 5, cy: 12, r: 2.2 },
  { kind: 'circle', cx: 18, cy: 5.5, r: 2.2 },
  { kind: 'circle', cx: 18, cy: 12, r: 2.2 },
  { kind: 'circle', cx: 18, cy: 18.5, r: 2.2 },
  { d: 'M7.2 11 16 6.2M7.2 13l8.8 4.8M7.2 12h8.6' }
]
/** RabbitMQ：消息队列信箱。 */
const QUEUE = [
  { d: 'M4 7a2 2 0 0 1 2-2h12a2 2 0 0 1 2 2v10a2 2 0 0 1-2 2H6a2 2 0 0 1-2-2V7Z' },
  { d: 'M8 10.5h8M8 14h5' }
]
/** Webhook：链路。 */
const LINK = [
  { d: 'M10.5 13.5a4 4 0 0 0 6 0l2.8-2.8a4.2 4.2 0 0 0-6-6L11.5 6.5' },
  { d: 'M13.5 10.5a4 4 0 0 0-6 0l-2.8 2.8a4.2 4.2 0 0 0 6 6l1.8-1.8' }
]
/** Tavily：全球搜索。 */
const GLOBE = [
  { kind: 'circle', cx: 12, cy: 12, r: 8.5 },
  { d: 'M3.5 12h17' },
  { d: 'M12 3.5c2.5 2.3 3.8 5.3 3.8 8.5s-1.3 6.2-3.8 8.5c-2.5-2.3-3.8-5.3-3.8-8.5s1.3-6.2 3.8-8.5Z' }
]
/** Qdrant：六边形网格。 */
const HEX = [
  { d: 'M12 3l7.8 4.5v9L12 21l-7.8-4.5v-9L12 3Z' },
  { kind: 'circle', cx: 12, cy: 12, r: 3 }
]
/** Milvus：三角节点。 */
const TRIANGLE = [
  { d: 'M12 4l8.5 15h-17L12 4Z' },
  { d: 'M12 11l4.5 8h-9l4.5-8Z' }
]
/** Elasticsearch：检索刻度。 */
const SEARCH = [
  { kind: 'circle', cx: 10.5, cy: 10.5, r: 6 },
  { d: 'M15 15l5 5' },
  { d: 'M7.5 10.5h6M10.5 7.5v6' }
]
/** Plugin：插块。 */
const PUZZLE = [
  { d: 'M9 4h3v2.2a1.8 1.8 0 1 0 0 3.6V12h2.2a1.8 1.8 0 1 1 3.6 0H20v3h-2.2a1.8 1.8 0 1 0-3.6 0H12v2.2a1.8 1.8 0 1 1-3.6 0H4V12h2.2a1.8 1.8 0 1 0 0-3.6H4V5h5V4Z' }
]

/** 连接类型到图标路径集合的映射，未知类型回退为通用插块。 */
const ICONS = { MYSQL: DATABASE, POSTGRESQL: DATABASE, REDIS: LAYERS, S3: BUCKET, KAFKA: PIPELINE,
  RABBITMQ: QUEUE, WEBHOOK: LINK, TAVILY: GLOBE, QDRANT: HEX, MILVUS: TRIANGLE,
  ELASTICSEARCH: SEARCH, PLUGIN: PUZZLE }

const paths = computed(() => ICONS[String(props.type || '').toUpperCase()] || PUZZLE)
</script>
