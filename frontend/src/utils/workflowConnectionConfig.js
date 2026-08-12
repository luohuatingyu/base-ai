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

const CONNECTION_CATEGORY_COLORS = {
  DATABASE: ['#eff6ff', '#dbeafe', '#bfdbfe', '#93c5fd'],
  VECTOR_DATABASE: ['#faf5ff', '#f3e8ff', '#e9d5ff', '#d8b4fe', '#c084fc'],
  CACHE: ['#ecfdf5', '#d1fae5'],
  OBJECT_STORAGE: ['#fffbeb', '#fef3c7'],
  MESSAGE_QUEUE: ['#fff1f2', '#ffe4e6', '#fecdd3'],
  WEBHOOK: ['#ecfeff', '#cffafe'],
  OTHER: ['#f8fafc', '#e2e8f0', '#cbd5e1']
}

const CONNECTION_CATEGORY_TEXT_COLORS = {
  DATABASE: '#1d4ed8', VECTOR_DATABASE: '#7e22ce', CACHE: '#047857', OBJECT_STORAGE: '#b45309',
  MESSAGE_QUEUE: '#be123c', WEBHOOK: '#0e7490', OTHER: '#475569'
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

/** 返回连接分类标签使用的稳定颜色。 */
export function connectionCategoryStyle(categoryKey) {
  const colors = CONNECTION_CATEGORY_COLORS[categoryKey] || CONNECTION_CATEGORY_COLORS.OTHER
  const color = CONNECTION_CATEGORY_TEXT_COLORS[categoryKey] || CONNECTION_CATEGORY_TEXT_COLORS.OTHER
  return { backgroundColor: colors[0], borderColor: colors.at(-1), color }
}

/**
 * 返回具体连接类型的颜色；同分类共享色相，并按类型顺序逐步加深。
 * 对于多分类类型，调用方传入当前分类即可保持选择上下文中的色彩语义。
 */
export function connectionTypeStyle(connectionType, categoryKey) {
  const categories = connectionCategoriesForType(connectionType)
  const resolvedCategory = categories.includes(categoryKey) ? categoryKey : categories[0] || 'OTHER'
  const types = connectionTypesForCategory(resolvedCategory)
  const colors = CONNECTION_CATEGORY_COLORS[resolvedCategory] || CONNECTION_CATEGORY_COLORS.OTHER
  const index = Math.max(0, types.indexOf(String(connectionType || '').toUpperCase()))
  return {
    backgroundColor: colors[Math.min(index + 1, colors.length - 1)],
    borderColor: colors.at(-1),
    color: CONNECTION_CATEGORY_TEXT_COLORS[resolvedCategory] || CONNECTION_CATEGORY_TEXT_COLORS.OTHER
  }
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
