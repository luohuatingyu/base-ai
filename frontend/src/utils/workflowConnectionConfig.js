/** 创建连接配置字段定义。 */
function field(key, editor, defaultValue, options = []) {
  return { key, editor, defaultValue, options }
}

export const CONNECTION_CONFIG_FIELDS = {
  MYSQL: [
    field('url', 'text', 'jdbc:mysql://host:3306/database'),
    field('username', 'text', ''),
    field('password', 'password', ''),
    field('allowWrite', 'boolean', false)
  ],
  POSTGRESQL: [
    field('url', 'text', 'jdbc:postgresql://host:5432/database'),
    field('username', 'text', ''),
    field('password', 'password', ''),
    field('allowWrite', 'boolean', false)
  ],
  REDIS: [
    field('uri', 'password', 'redis://host:6379/0'),
    field('keyPrefix', 'text', ''),
    field('allowWrite', 'boolean', false)
  ],
  S3: [
    field('endpoint', 'text', ''),
    field('region', 'text', 'us-east-1'),
    field('accessKey', 'password', ''),
    field('secretKey', 'password', ''),
    field('bucket', 'text', ''),
    field('keyPrefix', 'text', ''),
    field('pathStyle', 'boolean', true),
    field('allowDelete', 'boolean', false)
  ],
  KAFKA: [
    field('bootstrapServers', 'text', ''),
    field('topicPrefix', 'text', ''),
    field('securityProtocol', 'select', '', ['', 'PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL']),
    field('saslMechanism', 'select', '', ['', 'PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512']),
    field('username', 'text', ''),
    field('password', 'password', '')
  ],
  RABBITMQ: [
    field('uri', 'password', 'amqp://user:password@host:5672/vhost'),
    field('exchangePrefix', 'text', ''),
    field('queuePrefix', 'text', '')
  ],
  WEBHOOK: [
    field('url', 'text', ''),
    field('method', 'select', 'POST', ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']),
    field('testMethod', 'select', 'GET', ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']),
    field('headers', 'keyValue', {}),
    field('secret', 'password', '')
  ],
  TAVILY: [
    field('apiKey', 'password', '')
  ],
  QDRANT: [
    field('url', 'text', 'https://host:6333'),
    field('apiKey', 'password', '')
  ],
  MILVUS: [
    field('url', 'text', 'https://host:19530'),
    field('token', 'password', ''),
    field('database', 'text', 'default')
  ],
  ELASTICSEARCH: [
    field('url', 'text', 'https://host:9200'),
    field('username', 'text', ''),
    field('password', 'password', ''),
    field('apiKey', 'password', ''),
    field('product', 'text', 'ELASTICSEARCH')
  ],
  PLUGIN: []
}

export const CONNECTION_TYPES = Object.keys(CONNECTION_CONFIG_FIELDS)

/**
 * 连接分类仅用于前端分组展示，不改变后端保存的 connectionType。
 * PostgreSQL 同时支持关系型数据库和向量检索，因此允许出现在两个分类中。
 */
export const CONNECTION_CATEGORIES = [
  { key: 'DATABASE', types: ['MYSQL', 'POSTGRESQL'] },
  { key: 'VECTOR_DATABASE', types: ['POSTGRESQL', 'QDRANT', 'MILVUS', 'ELASTICSEARCH'] },
  { key: 'CACHE', types: ['REDIS'] },
  { key: 'OBJECT_STORAGE', types: ['S3'] },
  { key: 'MESSAGE_QUEUE', types: ['KAFKA', 'RABBITMQ'] },
  { key: 'WEBHOOK', types: ['WEBHOOK'] },
  { key: 'OTHER', types: ['TAVILY', 'PLUGIN'] }
]

const CONNECTION_CATEGORY_STYLE = { backgroundColor: '#f8fafc', borderColor: '#cbd5e1', color: '#475569' }
const CONNECTION_TYPE_SURFACE = { backgroundColor: '#ffffff', borderColor: '#dbe3ee' }
/** 连接产品采用主流品牌图标库使用的品牌主色，通用类型保持中性。 */
const CONNECTION_TYPE_COLORS = {
  MYSQL: '#4479A1', POSTGRESQL: '#4169E1', REDIS: '#FF4438', S3: '#569A31',
  KAFKA: '#231F20', RABBITMQ: '#FF6600', QDRANT: '#DC244C', MILVUS: '#00A1EA',
  ELASTICSEARCH: '#005571', WEBHOOK: '#475569', TAVILY: '#475569', PLUGIN: '#475569'
}

/** 返回指定连接类型的标准字段。 */
export function connectionConfigFields(connectionType) {
  return CONNECTION_CONFIG_FIELDS[String(connectionType || '').toUpperCase()] || []
}

/** 返回分类下的可选连接类型，未知分类返回空数组。 */
export function connectionTypesForCategory(categoryKey) {
  return CONNECTION_CATEGORIES.find(category => category.key === categoryKey)?.types || []
}

/** 返回连接类型所属的全部分类，顺序同时定义列表中的首选分类。 */
export function connectionCategoriesForType(connectionType) {
  const normalized = String(connectionType || '').toUpperCase()
  return CONNECTION_CATEGORIES.filter(category => category.types.includes(normalized)).map(category => category.key)
}

/** 返回连接分类统一使用的中性导航样式，分类差异由语义图形表达。 */
export function connectionCategoryStyle() {
  return { ...CONNECTION_CATEGORY_STYLE }
}

/** 返回连接类型的常规品牌主色，颜色不再由其所属分类决定。 */
export function connectionTypeStyle(connectionType) {
  const normalized = String(connectionType || '').toUpperCase()
  return { ...CONNECTION_TYPE_SURFACE, color: CONNECTION_TYPE_COLORS[normalized] || CONNECTION_CATEGORY_STYLE.color }
}

/** 深复制连接配置，避免表单编辑污染列表数据。 */
export function cloneConnectionConfig(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return JSON.parse(JSON.stringify(value))
}

/** 生成指定连接类型的默认配置。 */
export function connectionConfigDefaults(connectionType) {
  return Object.fromEntries(connectionConfigFields(connectionType).map(item => [item.key, cloneValue(item.defaultValue)]))
}

/** 合并默认字段和已有配置，同时保留历史自定义字段及脱敏值。 */
export function createConnectionConfig(connectionType, value = {}) {
  return { ...connectionConfigDefaults(connectionType), ...cloneConnectionConfig(value) }
}

/** 列出不属于当前连接类型标准字段的自定义配置键。 */
export function extraConnectionConfigKeys(config, connectionType) {
  const standardKeys = new Set(connectionConfigFields(connectionType).map(item => item.key))
  if (String(connectionType || '').toUpperCase() === 'PLUGIN') {
    standardKeys.add('pluginComponentId'); standardKeys.add('credentials')
  }
  return Object.keys(config || {}).filter(key => !standardKeys.has(key))
}

/** 深复制单个默认字段值。 */
function cloneValue(value) {
  return value && typeof value === 'object' ? JSON.parse(JSON.stringify(value)) : value
}
