const AI_MODEL_FIELDS = [
  field('featureCode', 'text', 'DEFAULT'),
  field('modelType', 'select', 'text_model', ['text_model', 'vision_model']),
  field('modelId', 'number', null),
  field('temperature', 'number', 0),
  field('enableThinking', 'boolean', false),
  field('thinkingLevel', 'select', 'MEDIUM', ['LOW', 'MEDIUM', 'HIGH', 'EXTRA_HIGH', 'MAX', 'ULTRA'])
]

export const NODE_CONFIG_FIELDS = {
  START: [],
  END: [field('output', 'generic', null)],
  LLM: [
    ...AI_MODEL_FIELDS,
    field('systemPrompt', 'textarea', ''),
    field('prompt', 'textarea', ''),
  ],
  HTTP: [
    field('name', 'text', 'Workflow HTTP'),
    field('method', 'select', 'GET', ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']),
    field('url', 'text', ''),
    field('headers', 'generic', {}),
    field('queryParams', 'generic', {}),
    field('body', 'generic', null),
    field('contentType', 'text', 'application/json'),
    field('timeoutSeconds', 'number', 30)
  ],
  AGENT: [
    ...AI_MODEL_FIELDS,
    field('systemPrompt', 'textarea', 'Use the provided tools to complete the task.'),
    field('prompt', 'textarea', ''),
    field('maxSteps', 'number', 5),
    field('tools', 'generic', [])
  ],
  CONDITION: [field('condition', 'condition', { left: '', operator: 'EQ', right: '' })],
  ITERATION: [
    field('collection', 'text', '{{input.items}}'),
    field('maxIterations', 'number', 100)
  ],
  LOOP: [
    field('condition', 'condition', { left: '', operator: 'EQ', right: '' }),
    field('maxIterations', 'number', 10)
  ],
  SWITCH: [field('cases', 'generic', []), field('defaultBranch', 'text', 'default')],
  MERGE: [field('values', 'generic', []), field('mode', 'select', 'ARRAY', ['ARRAY', 'OBJECT'])],
  SUB_WORKFLOW: [field('workflowCode', 'text', ''), field('inputs', 'generic', {})],
  WAIT: [field('seconds', 'number', 1), field('milliseconds', 'number', 1000)],
  SET_VARIABLE: [field('output', 'generic', {})],
  TEMPLATE: [field('template', 'textarea', '')],
  JSON_PARSE: [field('value', 'generic', '')],
  JSON_VALIDATE: [field('value', 'generic', null), field('schema', 'generic', { type: 'object' }), field('failOnError', 'boolean', true)],
  TRANSFORM: [field('output', 'generic', {})],
  FILTER: [field('collection', 'generic', []), field('condition', 'condition', { left: '', operator: 'EQ', right: '' })],
  SORT: [field('collection', 'generic', []), field('path', 'text', ''), field('direction', 'select', 'ASC', ['ASC', 'DESC'])],
  AGGREGATE: [field('collection', 'generic', []), field('operation', 'select', 'COUNT', ['COUNT', 'SUM', 'AVG', 'MIN', 'MAX']), field('path', 'text', '')],
  CSV: [field('operation', 'select', 'PARSE', ['PARSE', 'STRINGIFY']), field('value', 'generic', '')],
  QUESTION_CLASSIFIER: [...AI_MODEL_FIELDS, field('input', 'text', '{{input}}'), field('categories', 'generic', [])],
  PARAMETER_EXTRACTOR: [...AI_MODEL_FIELDS, field('input', 'text', '{{input}}'), field('schema', 'generic', { type: 'object' })],
  STRUCTURED_OUTPUT: [field('value', 'generic', ''), field('schema', 'generic', { type: 'object' })],
  DOCUMENT_EXTRACTOR: [field('content', 'textarea', ''), field('base64', 'textarea', ''), field('fileName', 'text', ''), field('maxCharacters', 'number', 1000000)],
  WEBHOOK_TRIGGER: [field('connectionId', 'number', null)],
  SCHEDULE_TRIGGER: [field('cron', 'text', ''), field('zoneId', 'text', 'Asia/Shanghai')],
  EMAIL_SEND: [field('routeId', 'number', null), field('subject', 'text', ''), field('body', 'textarea', '')],
  IM_NOTIFY: [field('connectionId', 'number', null), field('body', 'generic', {}), field('contentType', 'text', 'application/json'), field('timeoutSeconds', 'number', 15)],
  SQL_QUERY: [field('connectionId', 'number', null), field('query', 'textarea', ''), field('parameters', 'generic', []), field('timeoutSeconds', 'number', 30), field('maxRows', 'number', 1000)],
  REDIS_COMMAND: [field('connectionId', 'number', null), field('command', 'text', 'GET'), field('arguments', 'generic', [])],
  S3_OBJECT: [field('connectionId', 'number', null), field('operation', 'select', 'GET', ['GET', 'PUT', 'LIST', 'DELETE']), field('bucket', 'text', ''), field('key', 'text', ''), field('prefix', 'text', ''), field('content', 'text', ''), field('base64', 'textarea', ''), field('contentType', 'text', 'application/octet-stream'), field('maxKeys', 'number', 100)],
  KAFKA_PUBLISH: [field('connectionId', 'number', null), field('topic', 'text', ''), field('key', 'text', ''), field('value', 'generic', null), field('timeoutSeconds', 'number', 30)],
  KAFKA_TRIGGER: [field('connectionId', 'number', null), field('topic', 'text', ''), field('groupId', 'text', '')],
  RABBITMQ_PUBLISH: [field('connectionId', 'number', null), field('exchange', 'text', ''), field('routingKey', 'text', ''), field('value', 'generic', null), field('timeoutSeconds', 'number', 30)],
  RABBITMQ_TRIGGER: [field('connectionId', 'number', null), field('queue', 'text', ''), field('exchange', 'text', ''), field('routingKey', 'text', '')]
}

const COMMON_EXECUTION_FIELDS = [
  field('maxAttempts', 'number', 1),
  field('retryDelayMillis', 'number', 0),
  field('onError', 'select', 'FAIL', ['FAIL', 'CONTINUE', 'BRANCH'])
]
const POLICY_EXCLUDED_TYPES = new Set(['START', 'END', 'WAIT', 'WEBHOOK_TRIGGER', 'SCHEDULE_TRIGGER', 'KAFKA_TRIGGER', 'RABBITMQ_TRIGGER'])
Object.entries(NODE_CONFIG_FIELDS).forEach(([type, fields]) => {
  if (!POLICY_EXCLUDED_TYPES.has(type)) fields.push(...COMMON_EXECUTION_FIELDS)
})

export const WORKFLOW_NODE_TYPES = Object.keys(NODE_CONFIG_FIELDS)

export const CONDITION_OPERATORS = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'CONTAINS', 'EXISTS', 'EMPTY']
export const CONFIG_VALUE_TYPES = ['string', 'number', 'boolean', 'null', 'object', 'array']
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor'])

/** 创建节点配置字段定义。 */
function field(key, editor, defaultValue, options = []) { return { key, editor, defaultValue, options } }

/** 返回节点类型支持的标准配置字段。 */
export function nodeConfigFields(nodeType) { return NODE_CONFIG_FIELDS[String(nodeType || '').toUpperCase()] || [] }

/** 返回由专用交互维护、不应出现在附加参数中的字段。 */
export function hiddenConfigKeys(nodeType) {
  return ['ITERATION', 'LOOP'].includes(String(nodeType || '').toUpperCase()) ? ['bodyGraph'] : []
}

/** 深复制可序列化配置，避免编辑器直接污染调用方状态。 */
export function cloneConfig(value) {
  if (!value || typeof value !== 'object' || Array.isArray(value)) return {}
  return JSON.parse(JSON.stringify(value))
}

/** 识别可视化值编辑器所需的数据类型。 */
export function configValueType(value) {
  if (value === null || value === undefined) return 'null'
  if (Array.isArray(value)) return 'array'
  if (typeof value === 'number') return 'number'
  if (typeof value === 'boolean') return 'boolean'
  if (typeof value === 'object') return 'object'
  return 'string'
}

/** 按选择的类型创建安全空值。 */
export function createConfigValue(type) {
  const defaults = { string: '', number: 0, boolean: false, null: null, object: {}, array: [] }
  return Object.prototype.hasOwnProperty.call(defaults, type) ? defaults[type] : ''
}

/** 判断附加参数名能否安全写入普通对象。 */
export function isSafeConfigKey(key) {
  const normalized = String(key || '').trim()
  return Boolean(normalized) && normalized.length <= 120 && !FORBIDDEN_KEYS.has(normalized)
}

/** 列出不属于标准字段或专用隐藏字段的附加配置键。 */
export function extraConfigKeys(config, nodeType) {
  const known = new Set([...nodeConfigFields(nodeType).map(item => item.key), ...hiddenConfigKeys(nodeType)])
  return Object.keys(config || {}).filter(key => !known.has(key))
}
