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

const props = defineProps({
  type: { type: String, default: '' },
  category: { type: String, default: '' }
})

/** 通用数据库分类：使用圆柱体表达结构化数据存储。 */
const DATABASE = [
  { kind: 'ellipse', cx: 12, cy: 6, rx: 8, ry: 3 },
  { d: 'M4 6v12c0 1.7 3.6 3 8 3s8-1.3 8-3V6' },
  { d: 'M4 12c0 1.7 3.6 3 8 3s8-1.3 8-3' }
]
/** 向量数据库分类：在数据库轮廓中展示相互连接的向量节点。 */
const VECTOR_DATABASE = [
  { kind: 'ellipse', cx: 12, cy: 5.5, rx: 8, ry: 2.8 },
  { d: 'M4 5.5v13c0 1.5 3.6 2.7 8 2.7s8-1.2 8-2.7v-13' },
  { kind: 'circle', cx: 8, cy: 12.5, r: 1.2 },
  { kind: 'circle', cx: 15.8, cy: 10.5, r: 1.2 },
  { kind: 'circle', cx: 14, cy: 17, r: 1.2 },
  { d: 'm9.2 12.2 5.4-1.4m-5.5 2.4 3.8 3.2m2.6-4.7-1.1 4.1' }
]
/** MySQL：以跃动的海豚轮廓形成独立产品识别。 */
const MYSQL = [
  { d: 'M3.5 9.5C7 5.8 12.3 4.5 17 6l3.5-2-.8 3.8c1.1 1 1.5 2.3 1.1 3.6-.6 2-2.7 3.4-5.2 3.4h-2.8c-1.4 0-2.5 1-2.7 2.4L9.7 21l-2.5-2.7.5-3.1c.4-2.7 2.6-4.6 5.4-4.6h2.7' },
  { kind: 'circle', cx: 17.2, cy: 8.3, r: 0.7 }
]
/** PostgreSQL：以象首和长鼻轮廓形成独立产品识别。 */
const POSTGRESQL = [
  { d: 'M7 8.5V7a5 5 0 0 1 10 0v5.8c0 4-2 7.1-5 8.2-1.8-.7-3-2-3-3.7 0-1.6 1.3-2.9 2.9-2.9H14V9.5' },
  { d: 'M7.2 8.5C4.8 8.5 3 10 3 12c0 1.7 1.4 3 3.2 3H9m7.8-6.5C19.2 8.5 21 10 21 12c0 1.7-1.4 3-3.2 3H15' },
  { kind: 'circle', cx: 10, cy: 9.5, r: 0.65 }
]
/** 缓存分类：使用闪电表达高速读写和短期存取。 */
const CACHE = [
  { d: 'M5 4h14v16H5z' },
  { d: 'm13.5 6-5 7H12l-1.5 5 5-7H12l1.5-5Z' }
]
/** 对象存储分类：使用立方体表达按对象组织的存储空间。 */
const OBJECT_STORAGE = [
  { d: 'm12 3 8 4.5v9L12 21l-8-4.5v-9L12 3Z' },
  { d: 'm4.3 7.7 7.7 4.4 7.7-4.4M12 12.1V21' }
]
/** 消息队列分类：使用顺序消息与流向箭头表达异步传递。 */
const MESSAGE_QUEUE = [
  { kind: 'circle', cx: 5, cy: 7, r: 2 },
  { kind: 'circle', cx: 5, cy: 17, r: 2 },
  { d: 'M8 7h11m-3-3 3 3-3 3M8 17h11m-3-3 3 3-3 3' }
]
/** Webhook 分类：使用事件节点和外发链路表达回调连接。 */
const WEBHOOK = [
  { kind: 'circle', cx: 6, cy: 12, r: 3 },
  { d: 'M9 12h10m-3-3 3 3-3 3' },
  { d: 'M6 7V4m0 16v-3' }
]
/** 其他分类：使用四宫格表达可扩展的通用连接。 */
const OTHER = [
  { d: 'M4 4h6v6H4zM14 4h6v6h-6zM4 14h6v6H4zM14 14h6v6h-6z' }
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

/** 连接分类到语义图标的映射，分类图标不借用具体产品图标。 */
const CATEGORY_ICONS = { DATABASE, VECTOR_DATABASE, CACHE, OBJECT_STORAGE, MESSAGE_QUEUE, WEBHOOK, OTHER }

/** 连接类型到产品图标的映射，未知类型回退为通用插块。 */
const TYPE_ICONS = { MYSQL, POSTGRESQL, REDIS: LAYERS, S3: BUCKET, KAFKA: PIPELINE,
  RABBITMQ: QUEUE, WEBHOOK: LINK, TAVILY: GLOBE, QDRANT: HEX, MILVUS: TRIANGLE,
  ELASTICSEARCH: SEARCH, PLUGIN: PUZZLE }

/** 分类优先使用分类语义图标，否则按具体连接类型选择产品图标。 */
const paths = computed(() => {
  const category = String(props.category || '').toUpperCase()
  if (category) return CATEGORY_ICONS[category] || OTHER
  return TYPE_ICONS[String(props.type || '').toUpperCase()] || PUZZLE
})
</script>
