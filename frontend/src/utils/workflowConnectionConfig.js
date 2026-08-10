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
  ]
}

export const CONNECTION_TYPES = Object.keys(CONNECTION_CONFIG_FIELDS)

/** 返回指定连接类型的标准字段。 */
export function connectionConfigFields(connectionType) {
  return CONNECTION_CONFIG_FIELDS[String(connectionType || '').toUpperCase()] || []
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
  return Object.keys(config || {}).filter(key => !standardKeys.has(key))
}

/** 深复制单个默认字段值。 */
function cloneValue(value) {
  return value && typeof value === 'object' ? JSON.parse(JSON.stringify(value)) : value
}
