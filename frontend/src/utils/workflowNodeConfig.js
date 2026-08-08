/** 创建 AI 节点共用模型字段，并允许特定节点使用资源下拉编辑器。 */
function aiModelFields(modelEditor = 'number') { return [
  field('featureCode', 'text', 'DEFAULT'),
  field('modelType', 'select', 'text_model', ['text_model', 'vision_model']),
  field('modelId', modelEditor, null),
  field('temperature', 'number', 0),
  field('enableThinking', 'boolean', false),
  field('thinkingLevel', 'select', 'MEDIUM', ['LOW', 'MEDIUM', 'HIGH', 'EXTRA_HIGH', 'MAX', 'ULTRA'])
] }

export const NODE_CONFIG_FIELDS = {
  START: [],
  END: [field('output', 'generic', null)],
  LLM: [
    ...aiModelFields(),
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
    ...aiModelFields('model'),
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
  QUESTION_CLASSIFIER: [...aiModelFields(), field('input', 'text', '{{input}}'), field('categories', 'generic', [])],
  PARAMETER_EXTRACTOR: [...aiModelFields(), field('input', 'text', '{{input}}'), field('schema', 'generic', { type: 'object' })],
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
const REQUIRED_CONFIG_FIELDS = {
  HTTP: ['url'], AGENT: ['tools'], CONDITION: ['condition'], ITERATION: ['collection'], LOOP: ['condition'],
  SWITCH: ['cases'], MERGE: ['values'], SUB_WORKFLOW: ['workflowCode'], SET_VARIABLE: ['output'], TEMPLATE: ['template'],
  JSON_PARSE: ['value'], JSON_VALIDATE: ['value', 'schema'], TRANSFORM: ['output'], FILTER: ['collection', 'condition'],
  SORT: ['collection'], AGGREGATE: ['collection'], CSV: ['value'], QUESTION_CLASSIFIER: ['input', 'categories'],
  PARAMETER_EXTRACTOR: ['input', 'schema'], STRUCTURED_OUTPUT: ['value', 'schema'], WEBHOOK_TRIGGER: ['connectionId'],
  SCHEDULE_TRIGGER: ['cron'], EMAIL_SEND: ['routeId'], IM_NOTIFY: ['connectionId'], SQL_QUERY: ['connectionId', 'query'],
  REDIS_COMMAND: ['connectionId', 'arguments'], S3_OBJECT: ['connectionId'], KAFKA_PUBLISH: ['connectionId', 'topic', 'value'],
  KAFKA_TRIGGER: ['connectionId', 'topic'], RABBITMQ_PUBLISH: ['connectionId', 'value'], RABBITMQ_TRIGGER: ['connectionId', 'queue']
}
const CONDITIONAL_CONFIG_FIELDS = {
  DOCUMENT_EXTRACTOR: ['content', 'base64'], S3_OBJECT: ['key'], RABBITMQ_PUBLISH: ['exchange', 'routingKey']
}

/** 创建节点配置字段定义。 */
function field(key, editor, defaultValue, options = []) { return { key, editor, defaultValue, options } }

/** 返回节点类型支持的标准配置字段，并附加必填展示元数据。 */
export function nodeConfigFields(nodeType) {
  const type = String(nodeType || '').toUpperCase()
  return (NODE_CONFIG_FIELDS[type] || []).map(item => ({ ...item, requirement: nodeConfigFieldRequirement(type, item.key) }))
}

/** 返回标准字段在当前节点中的必填级别。 */
export function nodeConfigFieldRequirement(nodeType, key) {
  const type = String(nodeType || '').toUpperCase()
  if (REQUIRED_CONFIG_FIELDS[type]?.includes(key)) return 'required'
  if (CONDITIONAL_CONFIG_FIELDS[type]?.includes(key)) return 'conditional'
  return ''
}

/** 汇总发布前仍缺失的必填配置标识，供模板和画布提前提示。 */
export function missingNodeConfigRequirements(nodeType, config = {}) {
  const type = String(nodeType || '').toUpperCase()
  const value = config && typeof config === 'object' && !Array.isArray(config) ? config : {}
  const missing = []
  const requirePresent = key => { if (!Object.prototype.hasOwnProperty.call(value, key)) missing.push(key) }
  const requireText = key => { if (!String(value[key] ?? '').trim()) missing.push(key) }
  const requirePositive = key => { if (!Number.isFinite(Number(value[key])) || Number(value[key]) <= 0) missing.push(key) }
  const requireObject = key => { if (!value[key] || typeof value[key] !== 'object' || Array.isArray(value[key])) missing.push(key) }
  const requireArray = (key, minimum = 0) => { if (!Array.isArray(value[key]) || value[key].length < minimum) missing.push(key) }
  const requireCondition = key => {
    const condition = value[key]
    const operator = String(condition?.operator || 'EQ').toUpperCase()
    if (!condition || typeof condition !== 'object' || Array.isArray(condition) || !String(condition.left ?? '').trim()
      || (!['EXISTS', 'EMPTY'].includes(operator) && !Object.prototype.hasOwnProperty.call(condition, 'right'))) missing.push(key)
  }

  switch (type) {
    case 'HTTP': requireText('url'); break
    case 'AGENT': requireArray('tools', 1); break
    case 'CONDITION': requireCondition('condition'); break
    case 'ITERATION': requireText('collection'); requireObject('bodyGraph'); break
    case 'LOOP': requireCondition('condition'); requireObject('bodyGraph'); break
    case 'SWITCH': requireArray('cases', 1); break
    case 'MERGE': requirePresent('values'); break
    case 'SUB_WORKFLOW': requireText('workflowCode'); break
    case 'SET_VARIABLE': case 'TRANSFORM': requirePresent('output'); break
    case 'TEMPLATE': requireText('template'); break
    case 'JSON_PARSE': case 'CSV': requirePresent('value'); break
    case 'JSON_VALIDATE': case 'STRUCTURED_OUTPUT': requirePresent('value'); requireObject('schema'); break
    case 'FILTER': requirePresent('collection'); requireCondition('condition'); break
    case 'SORT': case 'AGGREGATE': requirePresent('collection'); break
    case 'QUESTION_CLASSIFIER': requireText('input'); requireArray('categories', 2); break
    case 'PARAMETER_EXTRACTOR': requireText('input'); requireObject('schema'); break
    case 'DOCUMENT_EXTRACTOR':
      if (!String(value.content ?? '').trim() && !String(value.base64 ?? '').trim()) missing.push('contentOrBase64')
      break
    case 'WEBHOOK_TRIGGER': case 'IM_NOTIFY': case 'SQL_QUERY': case 'REDIS_COMMAND': case 'S3_OBJECT':
    case 'KAFKA_PUBLISH': case 'KAFKA_TRIGGER': case 'RABBITMQ_PUBLISH': case 'RABBITMQ_TRIGGER':
      requirePositive('connectionId'); break
    case 'EMAIL_SEND': requirePositive('routeId'); break
    case 'SCHEDULE_TRIGGER': requireText('cron'); break
    default: break
  }
  if (type === 'SQL_QUERY') requireText('query')
  if (type === 'REDIS_COMMAND') requireArray('arguments', redisArgumentMinimum(value.command))
  if (type === 'S3_OBJECT' && String(value.operation || 'GET').toUpperCase() !== 'LIST') requireText('key')
  if (['KAFKA_PUBLISH', 'KAFKA_TRIGGER'].includes(type)) requireText('topic')
  if (type === 'KAFKA_PUBLISH' || type === 'RABBITMQ_PUBLISH') requirePresent('value')
  if (type === 'RABBITMQ_TRIGGER') requireText('queue')
  if (type === 'RABBITMQ_PUBLISH' && !String(value.exchange ?? '').trim() && !String(value.routingKey ?? '').trim()) missing.push('rabbitDestination')
  return [...new Set(missing)]
}

/** 返回 Redis 白名单命令执行所需的最少参数数量。 */
function redisArgumentMinimum(command) {
  return { GET: 1, SET: 2, DEL: 1, HGET: 2, HSET: 3, LPUSH: 2, RPUSH: 2, LRANGE: 3, PUBLISH: 2 }[String(command || 'GET').toUpperCase()] || 1
}

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

/** 按当前模型类型筛选 Agent 可选模型，兼容后续动态扩展的类型编码。 */
export function filterWorkflowModelOptions(options, modelType) {
  const type = String(modelType || 'text_model').trim().toLowerCase()
  return (options || []).filter(option => Array.isArray(option?.supportedModelTypes)
    && option.supportedModelTypes.map(value => String(value).toLowerCase()).includes(type))
}

/** 生成包含供应商和真实模型标识的稳定下拉标签。 */
export function workflowModelOptionLabel(option = {}) {
  return `${option.name || option.modelName || option.id} (${option.modelName || '-'}) · ${option.providerName || '-'}`
}

/** 保留兼容当前模型类型的数字 ID，不兼容、空值或非法值统一返回 null。 */
export function compatibleWorkflowModelId(options, modelType, modelId) {
  if (modelId === null || modelId === undefined || modelId === '') return null
  const normalizedId = Number(modelId)
  return Number.isFinite(normalizedId)
    && filterWorkflowModelOptions(options, modelType).some(option => option.id === normalizedId) ? normalizedId : null
}
