/** 创建带分组、校验和填写提示的连接配置字段定义。 */
function field(key, editor, defaultValue, metadata = {}) {
  return { key, editor, defaultValue, options: [], group: 'CONNECTION', required: false, wide: false, ...metadata }
}

export const CONNECTION_CONFIG_FIELDS = {
  MYSQL: [
    field('url', 'text', '', { required: true, wide: true, placeholder: 'jdbc:mysql://db.example.com:3306/app' }),
    field('username', 'text', '', { group: 'AUTH', placeholder: 'app_reader' }),
    field('password', 'password', '', { group: 'AUTH' }),
    field('allowWrite', 'boolean', false, { group: 'SCOPE', risk: true })
  ],
  POSTGRESQL: [
    field('url', 'text', '', { required: true, wide: true, placeholder: 'jdbc:postgresql://db.example.com:5432/app' }),
    field('username', 'text', '', { group: 'AUTH', placeholder: 'app_reader' }),
    field('password', 'password', '', { group: 'AUTH' }),
    field('allowWrite', 'boolean', false, { group: 'SCOPE', risk: true })
  ],
  REDIS: [
    field('uri', 'password', '', { required: true, wide: true, placeholder: 'redis://user:password@redis.example.com:6379/0' }),
    field('keyPrefix', 'text', '', { group: 'SCOPE', placeholder: 'workflow:' }),
    field('allowWrite', 'boolean', false, { group: 'SCOPE', risk: true })
  ],
  S3: [
    field('endpoint', 'text', '', { wide: true, placeholder: 'https://s3.example.com' }),
    field('region', 'text', 'us-east-1', { required: true, placeholder: 'us-east-1' }),
    field('bucket', 'text', '', { required: true, placeholder: 'workflow-files' }),
    field('accessKey', 'password', '', { group: 'AUTH', required: true }),
    field('secretKey', 'password', '', { group: 'AUTH', required: true }),
    field('keyPrefix', 'text', '', { group: 'SCOPE', placeholder: 'workflows/' }),
    field('allowDelete', 'boolean', false, { group: 'SCOPE', risk: true }),
    field('pathStyle', 'boolean', true, { group: 'BEHAVIOR' })
  ],
  KAFKA: [
    field('bootstrapServers', 'text', '', { required: true, wide: true, placeholder: 'broker-1.example.com:9092,broker-2.example.com:9092' }),
    field('securityProtocol', 'select', '', { group: 'AUTH', options: ['', 'PLAINTEXT', 'SSL', 'SASL_PLAINTEXT', 'SASL_SSL'] }),
    field('saslMechanism', 'select', '', { group: 'AUTH', options: ['', 'PLAIN', 'SCRAM-SHA-256', 'SCRAM-SHA-512'], requiredWhen: { key: 'securityProtocol', values: ['SASL_PLAINTEXT', 'SASL_SSL'] } }),
    field('username', 'text', '', { group: 'AUTH', placeholder: 'workflow-client', requiredWhen: { key: 'securityProtocol', values: ['SASL_PLAINTEXT', 'SASL_SSL'] } }),
    field('password', 'password', '', { group: 'AUTH', requiredWhen: { key: 'securityProtocol', values: ['SASL_PLAINTEXT', 'SASL_SSL'] } }),
    field('topicPrefix', 'text', '', { group: 'SCOPE', placeholder: 'workflow.' })
  ],
  RABBITMQ: [
    field('uri', 'password', '', { required: true, wide: true, placeholder: 'amqps://user:password@rabbit.example.com:5671/vhost' }),
    field('exchangePrefix', 'text', '', { group: 'SCOPE', placeholder: 'workflow.' }),
    field('queuePrefix', 'text', '', { group: 'SCOPE', placeholder: 'workflow.' })
  ],
  WEBHOOK: [
    field('url', 'text', '', { required: true, wide: true, placeholder: 'https://hooks.example.com/events' }),
    field('method', 'select', 'POST', { group: 'BEHAVIOR', required: true, options: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] }),
    field('testMethod', 'select', 'GET', { group: 'BEHAVIOR', required: true, options: ['GET', 'POST', 'PUT', 'PATCH', 'DELETE'] }),
    field('headers', 'keyValue', {}, { group: 'AUTH', wide: true })
  ],
  TAVILY: [
    field('apiKey', 'password', '', { group: 'AUTH', required: true, wide: true })
  ],
  QDRANT: [
    field('url', 'text', '', { required: true, wide: true, placeholder: 'https://qdrant.example.com:6333' }),
    field('apiKey', 'password', '', { group: 'AUTH', wide: true })
  ],
  MILVUS: [
    field('url', 'text', '', { required: true, wide: true, placeholder: 'https://milvus.example.com:19530' }),
    field('database', 'text', 'default', { required: true, placeholder: 'default' }),
    field('token', 'password', '', { group: 'AUTH', required: true, wide: true, placeholder: 'username:password' })
  ],
  ELASTICSEARCH: [
    field('url', 'text', '', { required: true, wide: true, placeholder: 'https://elasticsearch.example.com:9200' }),
    field('product', 'select', 'ELASTICSEARCH', { required: true, options: ['ELASTICSEARCH'] }),
    field('username', 'text', '', { group: 'AUTH', placeholder: 'elastic' }),
    field('password', 'password', '', { group: 'AUTH' }),
    field('apiKey', 'password', '', { group: 'AUTH' })
  ],
  PLUGIN: []
}

export const CONNECTION_CONFIG_GROUPS = ['CONNECTION', 'AUTH', 'SCOPE', 'BEHAVIOR']

const LEGACY_CONNECTION_CONFIG_FIELDS = { WEBHOOK: ['secret'] }

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
  KAFKA: '#231F20', RABBITMQ: '#FF6600', QDRANT: '#DC244C', MILVUS: '#00B3FF',
  ELASTICSEARCH: '#005571', WEBHOOK: '#475569', TAVILY: '#475569', PLUGIN: '#475569'
}

/** 返回指定连接类型的标准字段。 */
export function connectionConfigFields(connectionType) {
  return CONNECTION_CONFIG_FIELDS[String(connectionType || '').toUpperCase()] || []
}

/** 判断字段在当前配置下是否必填，覆盖固定必填和条件必填。 */
export function isConnectionConfigFieldRequired(fieldDefinition, config = {}) {
  if (fieldDefinition?.required) return true
  const condition = fieldDefinition?.requiredWhen
  return Boolean(condition && condition.values.includes(config?.[condition.key]))
}

/** 返回当前配置缺少的必填字段，供保存前提示使用。 */
export function missingConnectionConfigFields(connectionType, config = {}) {
  return connectionConfigFields(connectionType)
    .filter(item => isConnectionConfigFieldRequired(item, config) && isEmptyConfigValue(config?.[item.key]))
    .map(item => item.key)
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
  const normalizedType = String(connectionType || '').toUpperCase()
  const standardKeys = new Set(connectionConfigFields(normalizedType).map(item => item.key))
  for (const key of LEGACY_CONNECTION_CONFIG_FIELDS[normalizedType] || []) standardKeys.add(key)
  if (normalizedType === 'PLUGIN') {
    standardKeys.add('pluginComponentId'); standardKeys.add('credentials')
  }
  return Object.keys(config || {}).filter(key => !standardKeys.has(key))
}

/** 判断必填校验中的值是否为空，布尔 false 和数字零均视为有效。 */
function isEmptyConfigValue(value) {
  return value === undefined || value === null || typeof value === 'string' && value.trim() === ''
}

/** 深复制单个默认字段值。 */
function cloneValue(value) {
  return value && typeof value === 'object' ? JSON.parse(JSON.stringify(value)) : value
}
