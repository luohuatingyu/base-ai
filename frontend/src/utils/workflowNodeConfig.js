/** 创建 AI 节点共用模型字段，模型路由和指定模型统一通过资源下拉选择。 */
function aiModelFields(defaultModelType = 'text_model') { return [
  field('modelMode', 'select', null, ['ROUTE', 'DIRECT']),
  field('featureCode', 'modelRoute', 'DEFAULT'),
  field('modelType', 'modelType', defaultModelType),
  field('modelId', 'model', null),
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
    ...aiModelFields(),
    field('systemPrompt', 'textarea', 'Use the provided tools to complete the task.'),
    field('prompt', 'textarea', ''),
    field('maxSteps', 'number', 5),
    field('tools', 'generic', [])
  ],
  RAG: [
    field('knowledgeBaseId', 'knowledgeBase', null),
    field('query', 'textarea', '{{input.query}}'),
    field('topK', 'number', 5),
    field('scoreThreshold', 'number', 0),
    ...aiModelFields(),
    field('systemPrompt', 'textarea', 'Answer only from the retrieved context and cite sources with [n].'),
    field('promptTemplate', 'textarea', 'Question:\n{{query}}\n\nRetrieved context:\n{{context}}')
  ],
  EMBEDDING: [...aiModelFields('embedding_model').slice(0, 4), field('input', 'generic', null)],
  KNOWLEDGE_RETRIEVAL: [
    field('knowledgeBaseId', 'knowledgeBase', null),
    field('query', 'textarea', '{{input.query}}'),
    field('topK', 'number', 5),
    field('scoreThreshold', 'number', 0)
  ],
  KNOWLEDGE_UPSERT: [
    field('knowledgeBaseId', 'knowledgeBase', null),
    field('inputMode', 'select', null, ['TEXT', 'BASE64']),
    field('content', 'textarea', ''),
    field('base64', 'textarea', ''),
    field('fileName', 'text', 'document.txt'),
    field('contentType', 'text', 'text/plain')
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
  WAIT: [field('durationMode', 'select', null, ['SECONDS', 'MILLISECONDS']), field('seconds', 'number', 1), field('milliseconds', 'number', 1000)],
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
  DOCUMENT_EXTRACTOR: [field('inputMode', 'select', null, ['TEXT', 'BASE64']), field('content', 'textarea', ''), field('base64', 'textarea', ''), field('fileName', 'text', ''), field('maxCharacters', 'number', 1000000)],
  WEBHOOK_TRIGGER: [field('connectionId', 'connection', null)],
  SCHEDULE_TRIGGER: [field('cron', 'text', ''), field('zoneId', 'text', 'Asia/Shanghai')],
  EMAIL_SEND: [field('routeId', 'mailRoute', null), field('subject', 'text', ''), field('body', 'textarea', '')],
  IM_NOTIFY: [field('connectionId', 'connection', null), field('body', 'generic', {}), field('contentType', 'text', 'application/json'), field('timeoutSeconds', 'number', 15)],
  SQL_QUERY: [field('connectionId', 'connection', null), field('query', 'textarea', ''), field('parameters', 'generic', []), field('timeoutSeconds', 'number', 30), field('maxRows', 'number', 1000)],
  REDIS_COMMAND: [field('connectionId', 'connection', null), field('command', 'text', 'GET'), field('arguments', 'generic', [])],
  S3_OBJECT: [field('connectionId', 'connection', null), field('operation', 'select', null, ['GET', 'PUT', 'LIST', 'DELETE']), field('bucket', 'text', ''), field('key', 'text', ''), field('prefix', 'text', ''), field('contentMode', 'select', null, ['TEXT', 'BASE64']), field('content', 'text', ''), field('base64', 'textarea', ''), field('contentType', 'text', 'application/octet-stream'), field('maxKeys', 'number', 100)],
  KAFKA_PUBLISH: [field('connectionId', 'connection', null), field('topic', 'text', ''), field('key', 'text', ''), field('value', 'generic', null), field('timeoutSeconds', 'number', 30)],
  KAFKA_TRIGGER: [field('connectionId', 'connection', null), field('topic', 'text', ''), field('groupId', 'text', '')],
  RABBITMQ_PUBLISH: [field('connectionId', 'connection', null), field('destinationMode', 'select', null, ['EXCHANGE', 'DEFAULT_EXCHANGE']), field('exchange', 'text', ''), field('routingKey', 'text', ''), field('value', 'generic', null), field('timeoutSeconds', 'number', 30)],
  RABBITMQ_TRIGGER: [field('connectionId', 'connection', null), field('queue', 'text', ''), field('exchange', 'text', ''), field('routingKey', 'text', '')],
  TAVILY_TOOL: [field('connectionId', 'connection', null), field('operation', 'select', null, ['SEARCH', 'EXTRACT']),
    field('query', 'text', '{{input.query}}'), field('searchDepth', 'select', 'basic', ['basic', 'advanced', 'fast', 'ultra-fast']),
    field('maxResults', 'number', 5), field('urls', 'text', '{{input.urls}}'),
    field('extractDepth', 'select', 'basic', ['basic', 'advanced']), field('format', 'select', 'markdown', ['markdown', 'text'])],
  PLUGIN_ACTION: [], PLUGIN_TRIGGER: [], PLUGIN_MODEL: [], PLUGIN_DATASOURCE: [],
  PLUGIN_AGENT_STRATEGY: [], PLUGIN_EXTENSION: []
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
/** 记录全部纳入必填校验的原生节点，测试据此阻止新增节点遗漏规则。 */
export const WORKFLOW_NODE_VALIDATION_TYPES = [...WORKFLOW_NODE_TYPES]

export const CONDITION_OPERATORS = ['EQ', 'NE', 'GT', 'GTE', 'LT', 'LTE', 'CONTAINS', 'EXISTS', 'EMPTY']
export const CONFIG_VALUE_TYPES = ['string', 'number', 'boolean', 'null', 'object', 'array']
const NODE_CONNECTION_TYPES = {
  SQL_QUERY: ['MYSQL', 'POSTGRESQL'], REDIS_COMMAND: ['REDIS'], S3_OBJECT: ['S3'],
  KAFKA_PUBLISH: ['KAFKA'], KAFKA_TRIGGER: ['KAFKA'],
  RABBITMQ_PUBLISH: ['RABBITMQ'], RABBITMQ_TRIGGER: ['RABBITMQ'],
  IM_NOTIFY: ['WEBHOOK'], WEBHOOK_TRIGGER: ['WEBHOOK'], TAVILY_TOOL: ['TAVILY'],
  PLUGIN_ACTION: ['PLUGIN'], PLUGIN_TRIGGER: ['PLUGIN'], PLUGIN_MODEL: ['PLUGIN'],
  PLUGIN_DATASOURCE: ['PLUGIN'], PLUGIN_AGENT_STRATEGY: ['PLUGIN'], PLUGIN_EXTENSION: ['PLUGIN']
}
const PLUGIN_NODE_TYPES = new Set(['PLUGIN_ACTION', 'PLUGIN_TRIGGER', 'PLUGIN_MODEL', 'PLUGIN_DATASOURCE', 'PLUGIN_AGENT_STRATEGY', 'PLUGIN_EXTENSION'])
const FORBIDDEN_KEYS = new Set(['__proto__', 'prototype', 'constructor'])
const REQUIRED_CONFIG_FIELDS = {
  LLM: ['modelMode', 'prompt'], HTTP: ['method', 'url'], AGENT: ['modelMode', 'prompt', 'tools'], RAG: ['knowledgeBaseId', 'query', 'topK', 'scoreThreshold', 'modelMode'], EMBEDDING: ['modelMode', 'input'],
  KNOWLEDGE_RETRIEVAL: ['knowledgeBaseId', 'query', 'topK', 'scoreThreshold'], KNOWLEDGE_UPSERT: ['knowledgeBaseId', 'inputMode', 'fileName', 'contentType'], CONDITION: ['condition'], ITERATION: ['collection'], LOOP: ['condition'],
  SWITCH: ['cases', 'defaultBranch'], MERGE: ['mode', 'values'], SUB_WORKFLOW: ['workflowCode'], WAIT: ['durationMode'], SET_VARIABLE: ['output'], TEMPLATE: ['template'],
  JSON_PARSE: ['value'], JSON_VALIDATE: ['value', 'schema'], TRANSFORM: ['output'], FILTER: ['collection', 'condition'],
  SORT: ['collection', 'direction'], AGGREGATE: ['collection', 'operation'], CSV: ['operation', 'value'], QUESTION_CLASSIFIER: ['modelMode', 'input', 'categories'],
  PARAMETER_EXTRACTOR: ['modelMode', 'input', 'schema'], STRUCTURED_OUTPUT: ['value', 'schema'], DOCUMENT_EXTRACTOR: ['inputMode'], WEBHOOK_TRIGGER: ['connectionId'],
  SCHEDULE_TRIGGER: ['cron'], EMAIL_SEND: ['routeId', 'subject'], IM_NOTIFY: ['connectionId'], SQL_QUERY: ['connectionId', 'query'],
  REDIS_COMMAND: ['connectionId', 'command', 'arguments'], S3_OBJECT: ['connectionId', 'operation'], KAFKA_PUBLISH: ['connectionId', 'topic', 'value'],
  KAFKA_TRIGGER: ['connectionId', 'topic'], RABBITMQ_PUBLISH: ['connectionId', 'value'], RABBITMQ_TRIGGER: ['connectionId', 'queue'],
  TAVILY_TOOL: ['connectionId', 'operation']
}
const CONDITIONAL_CONFIG_FIELDS = {
  LLM: ['featureCode', 'modelType', 'modelId'], AGENT: ['featureCode', 'modelType', 'modelId'], RAG: ['featureCode', 'modelType', 'modelId'],
  QUESTION_CLASSIFIER: ['featureCode', 'modelType', 'modelId'], PARAMETER_EXTRACTOR: ['featureCode', 'modelType', 'modelId'], EMBEDDING: ['featureCode', 'modelType', 'modelId'],
  WAIT: ['seconds', 'milliseconds'], DOCUMENT_EXTRACTOR: ['content', 'base64'], KNOWLEDGE_UPSERT: ['content', 'base64'],
  S3_OBJECT: ['key', 'contentMode', 'content', 'base64'], RABBITMQ_PUBLISH: ['destinationMode', 'exchange', 'routingKey'],
  TAVILY_TOOL: ['query', 'searchDepth', 'maxResults', 'urls', 'extractDepth', 'format']
}

/** 创建节点配置字段定义。 */
function field(key, editor, defaultValue, options = []) { return { key, editor, defaultValue, options } }

/** 返回节点类型支持的标准配置字段，并附加必填展示元数据。 */
export function nodeConfigFields(nodeType, config = undefined) {
  const type = String(nodeType || '').toUpperCase()
  if (PLUGIN_NODE_TYPES.has(type)) return pluginConfigFields(config)
  return (NODE_CONFIG_FIELDS[type] || []).map(item => ({ ...item, requirement: nodeConfigFieldRequirement(type, item.key, config) }))
}

/** 把导入时固定的插件 Schema 转换为嵌套参数字段。 */
function pluginConfigFields(config = {}) {
  const value = config && typeof config === 'object' && !Array.isArray(config) ? config : {}
  const fields = Array.isArray(value.parameterSchema) ? value.parameterSchema.map(item => {
    const rawType = String(item?.type || 'string').toLowerCase()
    const editor = ['number', 'integer'].includes(rawType) ? 'number' : rawType === 'boolean' ? 'boolean'
      : ['select', 'options'].includes(rawType) ? 'select' : ['paragraph', 'text-area'].includes(rawType) ? 'textarea' : 'text'
    const options = (Array.isArray(item?.options) ? item.options : []).filter(option =>
      option && typeof option === 'object' ? option.value !== undefined : option !== undefined).map(option =>
      option && typeof option === 'object' ? JSON.parse(JSON.stringify(option)) : option)
    return { key: `parameters.${item?.name || ''}`, editor, defaultValue: item?.default, localization: item?.localization || {},
      options, requirement: item?.required ? 'required' : '', label: item?.label || item?.name || '',
      description: item?.description || '', displayOptions: item?.displayOptions || {} }
  }).filter(item => item.key !== 'parameters.') : []
  if (Array.isArray(value.credentialSchema) && value.credentialSchema.length) {
    fields.unshift({ ...field('connectionId', 'connection', null), requirement: 'required' })
  }
  return fields
}

/** 判断字段是否属于当前已选择的节点方案，避免不同方案字段同时造成歧义。 */
export function nodeConfigFieldApplicable(nodeType, key, config = {}) {
  const type = String(nodeType || '').toUpperCase()
  const value = config && typeof config === 'object' && !Array.isArray(config) ? config : {}
  if (PLUGIN_NODE_TYPES.has(type) && key.startsWith('parameters.')) {
    const definition = pluginConfigFields(value).find(item => item.key === key)
    return matchesDisplayOptions(definition?.displayOptions, value.parameters || {})
  }
  const modelMode = String(value.modelMode || '').toUpperCase()
  if (['LLM', 'AGENT', 'RAG', 'QUESTION_CLASSIFIER', 'PARAMETER_EXTRACTOR', 'EMBEDDING'].includes(type)) {
    if (['featureCode', 'modelType'].includes(key)) return modelMode === 'ROUTE'
    if (key === 'modelId') return modelMode === 'DIRECT'
  }
  const durationMode = String(value.durationMode || '').toUpperCase()
  if (type === 'WAIT' && key === 'seconds') return durationMode === 'SECONDS'
  if (type === 'WAIT' && key === 'milliseconds') return durationMode === 'MILLISECONDS'
  const inputMode = String(value.inputMode || '').toUpperCase()
  if (['DOCUMENT_EXTRACTOR', 'KNOWLEDGE_UPSERT'].includes(type) && key === 'content') return inputMode === 'TEXT'
  if (['DOCUMENT_EXTRACTOR', 'KNOWLEDGE_UPSERT'].includes(type) && key === 'base64') return inputMode === 'BASE64'
  const operation = String(value.operation || '').toUpperCase()
  if (type === 'S3_OBJECT') {
    if (key === 'key') return ['GET', 'PUT', 'DELETE'].includes(operation)
    if (key === 'contentMode') return operation === 'PUT'
    const contentMode = String(value.contentMode || '').toUpperCase()
    if (key === 'content') return operation === 'PUT' && contentMode === 'TEXT'
    if (key === 'base64') return operation === 'PUT' && contentMode === 'BASE64'
  }
  if (type === 'TAVILY_TOOL') {
    if (['query', 'searchDepth', 'maxResults'].includes(key)) return operation === 'SEARCH'
    if (['urls', 'extractDepth', 'format'].includes(key)) return operation === 'EXTRACT'
  }
  const destinationMode = String(value.destinationMode || '').toUpperCase()
  if (type === 'RABBITMQ_PUBLISH' && key === 'exchange') return destinationMode === 'EXCHANGE'
  if (type === 'RABBITMQ_PUBLISH' && key === 'routingKey') return ['EXCHANGE', 'DEFAULT_EXCHANGE'].includes(destinationMode)
  return true
}

/** 按 n8n show/hide 规则决定动态字段是否适用。 */
function matchesDisplayOptions(displayOptions, parameters) {
  if (!displayOptions || typeof displayOptions !== 'object') return true
  const matches = conditions => Object.entries(conditions || {}).every(([key, expected]) => {
    const values = Array.isArray(expected) ? expected : [expected]
    return values.includes(parameters?.[key])
  })
  const hidden = displayOptions.hide && Object.keys(displayOptions.hide).length ? matches(displayOptions.hide) : false
  return matches(displayOptions.show) && !hidden
}

/** 判断字段是否会使用可直接执行的运行默认值，而非仅使用编辑器占位值。 */
export function nodeConfigUsesEffectiveDefault(nodeType, key, config = {}) {
  const type = String(nodeType || '').toUpperCase()
  const value = config && typeof config === 'object' && !Array.isArray(config) ? config : {}
  if (Object.prototype.hasOwnProperty.call(value, key) || !nodeConfigFieldApplicable(type, key, value)) return false
  return Object.prototype.hasOwnProperty.call(withValidDefaults(type, value), key)
}

/** 返回字段实际会采用的运行默认值；不适用或已显式配置时返回 undefined。 */
export function effectiveNodeConfigDefaultValue(nodeType, key, config = {}) {
  if (!nodeConfigUsesEffectiveDefault(nodeType, key, config)) return undefined
  return cloneValue(withValidDefaults(String(nodeType || '').toUpperCase(), config)[key])
}

/** 返回标准字段在当前节点中的必填级别。 */
export function nodeConfigFieldRequirement(nodeType, key, config = undefined) {
  const type = String(nodeType || '').toUpperCase()
  if (PLUGIN_NODE_TYPES.has(type)) return pluginConfigFields(config).find(item => item.key === key)?.requirement || ''
  if (REQUIRED_CONFIG_FIELDS[type]?.includes(key)) return 'required'
  if (!CONDITIONAL_CONFIG_FIELDS[type]?.includes(key)) return ''
  if (config === undefined) return 'conditional'
  const value = config && typeof config === 'object' && !Array.isArray(config) ? config : {}
  const modelMode = String(value.modelMode || '').toUpperCase()
  if (['LLM', 'AGENT', 'RAG', 'QUESTION_CLASSIFIER', 'PARAMETER_EXTRACTOR', 'EMBEDDING'].includes(type)) {
    if (['featureCode', 'modelType'].includes(key)) return modelMode === 'ROUTE' ? 'required' : ''
    if (key === 'modelId') return modelMode === 'DIRECT' ? 'required' : ''
  }
  if (type === 'WAIT') return String(value.durationMode || '').toUpperCase() === (key === 'seconds' ? 'SECONDS' : 'MILLISECONDS') ? 'required' : ''
  if (['DOCUMENT_EXTRACTOR', 'KNOWLEDGE_UPSERT'].includes(type)) return String(value.inputMode || '').toUpperCase() === (key === 'content' ? 'TEXT' : 'BASE64') ? 'required' : ''
  if (type === 'S3_OBJECT') {
    const operation = String(value.operation || '').toUpperCase()
    if (key === 'key') return ['GET', 'PUT', 'DELETE'].includes(operation) ? 'required' : ''
    if (key === 'contentMode') return operation === 'PUT' ? 'required' : ''
    const contentMode = String(value.contentMode || '').toUpperCase()
    if (key === 'content') return operation === 'PUT' && contentMode === 'TEXT' ? 'required' : ''
    if (key === 'base64') return operation === 'PUT' && contentMode === 'BASE64' ? 'required' : ''
  }
  if (type === 'RABBITMQ_PUBLISH') {
    const mode = String(value.destinationMode || '').toUpperCase()
    if (key === 'exchange') return mode === 'EXCHANGE' ? 'required' : ''
    if (key === 'routingKey') return mode === 'DEFAULT_EXCHANGE' ? 'required' : ''
  }
  if (type === 'TAVILY_TOOL') {
    const operation = String(value.operation || '').toUpperCase()
    if (['query', 'searchDepth', 'maxResults'].includes(key)) return operation === 'SEARCH' ? 'required' : ''
    if (['urls', 'extractDepth', 'format'].includes(key)) return operation === 'EXTRACT' ? 'required' : ''
  }
  if (CONDITIONAL_CONFIG_FIELDS[type]?.includes(key)) return 'conditional'
  return ''
}

/** 汇总发布前仍缺失的必填配置标识，供模板和画布提前提示。 */
export function missingNodeConfigRequirements(nodeType, config = {}) {
  const type = String(nodeType || '').toUpperCase()
  const value = withValidDefaults(type, config)
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
    case 'LLM': requireAiModel(value, missing); requireText('prompt'); break
    case 'HTTP': requireEnum('method', ['GET', 'POST', 'PUT', 'PATCH', 'DELETE']); requireText('url'); break
    case 'AGENT': requireAiModel(value, missing); requireText('prompt'); requireTools(); break
    case 'RAG': requireAiModel(value, missing); requirePositive('knowledgeBaseId'); requireText('query');
      if (!Number.isInteger(Number(value.topK)) || Number(value.topK) < 1 || Number(value.topK) > 50) missing.push('topK')
      if (!Number.isFinite(Number(value.scoreThreshold)) || Number(value.scoreThreshold) < 0 || Number(value.scoreThreshold) > 1) missing.push('scoreThreshold'); break
    case 'KNOWLEDGE_RETRIEVAL': requirePositive('knowledgeBaseId'); requireText('query');
      if (!Number.isInteger(Number(value.topK)) || Number(value.topK) < 1 || Number(value.topK) > 50) missing.push('topK')
      if (!Number.isFinite(Number(value.scoreThreshold)) || Number(value.scoreThreshold) < 0 || Number(value.scoreThreshold) > 1) missing.push('scoreThreshold'); break
    case 'KNOWLEDGE_UPSERT': requirePositive('knowledgeBaseId'); requireDocument(); requireText('fileName'); requireText('contentType'); break
    case 'CONDITION': requireCondition('condition'); break
    case 'ITERATION': requireText('collection'); requireObject('bodyGraph'); break
    case 'LOOP': requireCondition('condition'); requireObject('bodyGraph'); break
    case 'SWITCH': requireCases(); requireText('defaultBranch'); break
    case 'MERGE': requireEnum('mode', ['ARRAY', 'OBJECT']); requirePresent('values'); break
    case 'SUB_WORKFLOW': requireText('workflowCode'); break
    case 'WAIT': requireWait(); break
    case 'SET_VARIABLE': case 'TRANSFORM': requirePresent('output'); break
    case 'TEMPLATE': requireText('template'); break
    case 'JSON_PARSE': requirePresent('value'); break
    case 'JSON_VALIDATE': case 'STRUCTURED_OUTPUT': requirePresent('value'); requireObject('schema'); break
    case 'FILTER': requirePresent('collection'); requireCondition('condition'); break
    case 'SORT': requirePresent('collection'); requireEnum('direction', ['ASC', 'DESC']); break
    case 'AGGREGATE': requirePresent('collection'); requireEnum('operation', ['COUNT', 'SUM', 'AVG', 'MIN', 'MAX']); break
    case 'CSV': requireEnum('operation', ['PARSE', 'STRINGIFY']); requirePresent('value'); break
    case 'QUESTION_CLASSIFIER': requireAiModel(value, missing); requireText('input'); requireCategories(); break
    case 'PARAMETER_EXTRACTOR': requireAiModel(value, missing); requireText('input'); requireObject('schema'); break
    case 'EMBEDDING': requireAiModel(value, missing); requireEmbeddingInput(); break
    case 'DOCUMENT_EXTRACTOR': requireDocument(); break
    case 'WEBHOOK_TRIGGER': case 'IM_NOTIFY': case 'SQL_QUERY': case 'REDIS_COMMAND': case 'S3_OBJECT':
    case 'KAFKA_PUBLISH': case 'KAFKA_TRIGGER': case 'RABBITMQ_PUBLISH': case 'RABBITMQ_TRIGGER':
    case 'TAVILY_TOOL':
      requirePositive('connectionId'); break
    case 'EMAIL_SEND': requirePositive('routeId'); requireText('subject'); break
    case 'SCHEDULE_TRIGGER': requireText('cron'); break
    case 'PLUGIN_ACTION': case 'PLUGIN_TRIGGER': case 'PLUGIN_MODEL': case 'PLUGIN_DATASOURCE':
    case 'PLUGIN_AGENT_STRATEGY': case 'PLUGIN_EXTENSION':
      requirePositive('pluginComponentId'); requireText('packageFingerprint'); requireText('componentExternalId'); requireObject('parameters')
      for (const definition of Array.isArray(value.parameterSchema) ? value.parameterSchema : []) {
        if (!definition?.required || !nodeConfigFieldApplicable(type, `parameters.${definition.name}`, value)) continue
        const fieldValue = value.parameters?.[definition.name]
        if (fieldValue === undefined || fieldValue === null || (typeof fieldValue === 'string' && !fieldValue.trim())) {
          missing.push(`parameters.${definition.name}`)
        }
      }
      if (Array.isArray(value.credentialSchema) && value.credentialSchema.length) requirePositive('connectionId')
      break
    default: break
  }
  if (type === 'SQL_QUERY') requireText('query')
  if (type === 'REDIS_COMMAND') { requireEnum('command', ['GET', 'SET', 'DEL', 'HGET', 'HSET', 'LPUSH', 'RPUSH', 'LRANGE', 'PUBLISH']); requireArray('arguments', redisArgumentMinimum(value.command)) }
  if (type === 'S3_OBJECT') requireS3()
  if (['KAFKA_PUBLISH', 'KAFKA_TRIGGER'].includes(type)) requireText('topic')
  if (type === 'KAFKA_PUBLISH' || type === 'RABBITMQ_PUBLISH') requirePresent('value')
  if (type === 'RABBITMQ_TRIGGER') requireText('queue')
  if (type === 'RABBITMQ_PUBLISH') requireRabbitDestination()
  if (type === 'TAVILY_TOOL') requireTavily()
  return [...new Set(missing)]

  /** 校验 AI 模型来源方案。 */
  function requireAiModel(config, target) {
    const mode = String(config.modelMode || '').toUpperCase()
    if (!['ROUTE', 'DIRECT'].includes(mode)) { target.push('modelMode'); return }
    if (mode === 'ROUTE') { requireText('featureCode'); requireText('modelType') }
    else requirePositive('modelId')
  }
  /** 校验向量化输入为单文本或受限文本数组。 */
  function requireEmbeddingInput() {
    if (typeof value.input === 'string') { if (!value.input.trim() || value.input.trim().length > 500) missing.push('input'); return }
    if (!Array.isArray(value.input) || !value.input.length || value.input.length > 256
      || value.input.some(item => typeof item !== 'string' || !item.trim() || item.trim().length > 500)) missing.push('input')
  }
  /** 校验等待单位与对应时长。 */
  function requireWait() {
    const mode = String(value.durationMode || '').toUpperCase()
    if (!['SECONDS', 'MILLISECONDS'].includes(mode)) { missing.push('durationMode'); return }
    requirePositive(mode === 'SECONDS' ? 'seconds' : 'milliseconds')
  }
  /** 校验文档来源方案。 */
  function requireDocument() {
    const mode = String(value.inputMode || '').toUpperCase()
    if (!['TEXT', 'BASE64'].includes(mode)) { missing.push('inputMode'); return }
    requireText(mode === 'TEXT' ? 'content' : 'base64')
  }
  /** 校验 S3 操作及上传内容方案。 */
  function requireS3() {
    const operation = String(value.operation || '').toUpperCase()
    if (!['GET', 'PUT', 'LIST', 'DELETE'].includes(operation)) { missing.push('operation'); return }
    if (operation !== 'LIST') requireText('key')
    if (operation !== 'PUT') return
    const mode = String(value.contentMode || '').toUpperCase()
    if (!['TEXT', 'BASE64'].includes(mode)) { missing.push('contentMode'); return }
    requirePresent(mode === 'TEXT' ? 'content' : 'base64')
  }
  /** 校验 RabbitMQ 目的地方案。 */
  function requireRabbitDestination() {
    const mode = String(value.destinationMode || '').toUpperCase()
    if (!['EXCHANGE', 'DEFAULT_EXCHANGE'].includes(mode)) { missing.push('destinationMode'); return }
    requireText(mode === 'EXCHANGE' ? 'exchange' : 'routingKey')
  }
  /** 校验 Tavily 操作方案及官方关键参数。 */
  function requireTavily() {
    const operation = String(value.operation || '').toUpperCase()
    if (!['SEARCH', 'EXTRACT'].includes(operation)) { missing.push('operation'); return }
    if (operation === 'SEARCH') {
      requireText('query'); requireEnum('searchDepth', ['BASIC', 'ADVANCED', 'FAST', 'ULTRA-FAST'])
      const maximum = Number(value.maxResults); if (!Number.isInteger(maximum) || maximum < 1 || maximum > 20) missing.push('maxResults')
    } else {
      requireText('urls'); requireEnum('extractDepth', ['BASIC', 'ADVANCED']); requireEnum('format', ['MARKDOWN', 'TEXT'])
    }
  }
  /** 校验 Switch 分支结构。 */
  function requireCases() {
    requireArray('cases', 1)
    if (!Array.isArray(value.cases) || value.cases.some(item => !String(item?.branch ?? '').trim())) missing.push('cases')
  }
  /** 校验 Agent 工具的名称、类型和目标。 */
  function requireTools() {
    requireArray('tools', 1)
    const names = new Set()
    if (!Array.isArray(value.tools) || value.tools.some(item => {
      const name = String(item?.name ?? '').trim(); const toolType = String(item?.toolType ?? '').toUpperCase()
      if (!name || names.has(name) || !['HTTP', 'WORKFLOW'].includes(toolType)) return true
      names.add(name)
      return toolType === 'HTTP' ? !String(item?.config?.url ?? '').trim() : !String(item?.workflowCode ?? '').trim()
    })) missing.push('tools')
  }
  /** 校验问题分类至少含有两个唯一名称。 */
  function requireCategories() {
    requireArray('categories', 2)
    const names = new Set()
    if (!Array.isArray(value.categories) || value.categories.some(item => {
      const name = String(item?.name ?? '').trim(); if (!name || names.has(name)) return true; names.add(name); return false
    })) missing.push('categories')
  }
  /** 校验限定枚举值，拒绝依赖运行默认值的模糊行为。 */
  function requireEnum(key, options) { if (!options.includes(String(value[key] || '').toUpperCase())) missing.push(key) }
}

/** 仅回填能够独立满足业务规则的默认值，空占位仍须由用户配置。 */
function withValidDefaults(nodeType, config) {
  const value = config && typeof config === 'object' && !Array.isArray(config) ? { ...config } : {}
  const setDefault = (key, defaultValue) => {
    if (!Object.prototype.hasOwnProperty.call(value, key)) value[key] = cloneValue(defaultValue)
  }
  switch (nodeType) {
    case 'LLM': case 'AGENT': case 'RAG': case 'QUESTION_CLASSIFIER': case 'PARAMETER_EXTRACTOR':
      setDefault('featureCode', 'DEFAULT'); setDefault('modelType', 'text_model');
      if (nodeType === 'RAG') { setDefault('topK', 5); setDefault('scoreThreshold', 0) } break
    case 'EMBEDDING': setDefault('featureCode', 'DEFAULT'); setDefault('modelType', 'embedding_model'); break
    case 'KNOWLEDGE_RETRIEVAL': setDefault('topK', 5); setDefault('scoreThreshold', 0); break
    case 'HTTP': setDefault('method', 'GET'); break
    case 'ITERATION': setDefault('collection', '{{input.items}}'); break
    case 'MERGE': setDefault('mode', 'ARRAY'); setDefault('values', []); break
    case 'WAIT': setDefault('seconds', 1); setDefault('milliseconds', 1000); break
    case 'SET_VARIABLE': case 'TRANSFORM': setDefault('output', {}); break
    case 'SWITCH': setDefault('defaultBranch', 'default'); break
    case 'FILTER': setDefault('collection', []); break
    case 'SORT': setDefault('collection', []); setDefault('direction', 'ASC'); break
    case 'AGGREGATE': setDefault('collection', []); setDefault('operation', 'COUNT'); break
    case 'CSV': setDefault('operation', 'PARSE'); break
    case 'KAFKA_PUBLISH': case 'RABBITMQ_PUBLISH': setDefault('value', null); break
    case 'TAVILY_TOOL': setDefault('searchDepth', 'basic'); setDefault('maxResults', 5); setDefault('extractDepth', 'basic'); setDefault('format', 'markdown'); break
    case 'PLUGIN_ACTION': case 'PLUGIN_TRIGGER': case 'PLUGIN_MODEL': case 'PLUGIN_DATASOURCE':
    case 'PLUGIN_AGENT_STRATEGY': case 'PLUGIN_EXTENSION': setDefault('parameters', {}); break
    default: break
  }
  return value
}

/** 克隆字段默认值，避免校验过程污染调用方配置。 */
function cloneValue(value) { return value === undefined ? undefined : JSON.parse(JSON.stringify(value)) }

/** 返回 Redis 白名单命令执行所需的最少参数数量。 */
function redisArgumentMinimum(command) {
  return { GET: 1, SET: 2, DEL: 1, HGET: 2, HSET: 3, LPUSH: 2, RPUSH: 2, LRANGE: 3, PUBLISH: 2 }[String(command || 'GET').toUpperCase()] || 1
}

/** 返回由专用交互维护、不应出现在附加参数中的字段。 */
export function hiddenConfigKeys(nodeType) {
  const type = String(nodeType || '').toUpperCase()
  if (PLUGIN_NODE_TYPES.has(type)) return ['pluginComponentId', 'packageFingerprint', 'componentExternalId',
    'componentType', 'parameterSchema', 'credentialSchema', 'parameters']
  return ['ITERATION', 'LOOP'].includes(type) ? ['bodyGraph'] : []
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

/** 按当前模型类型筛选 AI 节点可选模型，兼容后续动态扩展的类型编码。 */
export function filterWorkflowModelOptions(options, modelType) {
  if (modelType === null) return options || []
  const type = String(modelType || 'text_model').trim().toLowerCase()
  return (options || []).filter(option => Array.isArray(option?.supportedModelTypes)
    && option.supportedModelTypes.map(value => String(value).toLowerCase()).includes(type))
}

/** 生成包含供应商和真实模型标识的稳定下拉标签。 */
export function workflowModelOptionLabel(option = {}) {
  return `${option.name || option.modelName || option.id} (${option.modelName || '-'}) · ${option.providerName || '-'}`
}

/** 生成模型路由名称和功能编码组成的稳定下拉标签。 */
export function workflowRouteOptionLabel(option = {}) {
  return `${option.name || option.featureCode || option.id} (${option.featureCode || '-'})`
}

/** 保留选项列表中存在的功能编码，并使用服务端返回的规范大小写。 */
export function compatibleWorkflowRouteCode(options, featureCode) {
  const normalized = String(featureCode || '').trim().toUpperCase()
  if (!normalized) return null
  return (options || []).find(option => String(option?.featureCode || '').trim().toUpperCase() === normalized)?.featureCode || null
}

/** 保留兼容当前模型类型的数字 ID，不兼容、空值或非法值统一返回 null。 */
export function compatibleWorkflowModelId(options, modelType, modelId) {
  if (modelId === null || modelId === undefined || modelId === '') return null
  const normalizedId = Number(modelId)
  return Number.isFinite(normalizedId)
    && filterWorkflowModelOptions(options, modelType).some(option => option.id === normalizedId) ? normalizedId : null
}

/** 按节点运行时规则筛选当前节点允许使用的连接类型。 */
export function filterWorkflowConnectionOptions(options, nodeType) {
  const allowed = new Set(NODE_CONNECTION_TYPES[String(nodeType || '').toUpperCase()] || [])
  return (options || []).filter(option => allowed.has(String(option?.connectionType || '').toUpperCase()))
}

/** 生成不包含敏感连接参数的稳定下拉标签。 */
export function workflowConnectionOptionLabel(option = {}) {
  return `${option.name || option.code || option.id} (${option.code || '-'}) · ${option.connectionType || '-'}`
}

/** 生成邮件业务名称和编码组成的稳定下拉标签。 */
export function workflowMailRouteOptionLabel(option = {}) {
  return `${option.name || option.businessCode || option.id} (${option.businessCode || '-'})`
}

/** 保留选项列表中存在的数字资源 ID，空值、非法值或失效值统一返回 null。 */
export function compatibleWorkflowResourceId(options, resourceId) {
  if (resourceId === null || resourceId === undefined || resourceId === '') return null
  const normalizedId = Number(resourceId)
  return Number.isFinite(normalizedId) && (options || []).some(option => option.id === normalizedId) ? normalizedId : null
}
