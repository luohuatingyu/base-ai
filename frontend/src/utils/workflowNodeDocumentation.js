import { nodeConfigFieldApplicable, nodeConfigFields, WORKFLOW_NODE_TYPES } from './workflowNodeConfig.js'

/** 显式维护已完成人工审阅的节点说明目录，新增节点时测试会要求同步登记。 */
export const DOCUMENTED_WORKFLOW_NODE_TYPES = Object.freeze([
  'START', 'END', 'LLM', 'HTTP', 'AGENT', 'RAG', 'CONDITION', 'ITERATION', 'LOOP', 'SWITCH', 'MERGE',
  'SUB_WORKFLOW', 'WAIT', 'SET_VARIABLE', 'TEMPLATE', 'JSON_PARSE', 'JSON_VALIDATE', 'TRANSFORM', 'FILTER',
  'SORT', 'AGGREGATE', 'CSV', 'QUESTION_CLASSIFIER', 'PARAMETER_EXTRACTOR', 'STRUCTURED_OUTPUT',
  'DOCUMENT_EXTRACTOR', 'WEBHOOK_TRIGGER', 'SCHEDULE_TRIGGER', 'EMAIL_SEND', 'IM_NOTIFY', 'SQL_QUERY',
  'REDIS_COMMAND', 'S3_OBJECT', 'KAFKA_PUBLISH', 'KAFKA_TRIGGER', 'RABBITMQ_PUBLISH', 'RABBITMQ_TRIGGER',
  'TAVILY_TOOL', 'KNOWLEDGE_RETRIEVAL', 'KNOWLEDGE_UPSERT'
])

/** 为每种原生节点提供可执行配置示例中的关键业务参数。 */
const WORKFLOW_NODE_EXAMPLE_OVERRIDES = Object.freeze({
  START: {}, END: { output: '{{nodes}}' },
  LLM: { modelMode: 'ROUTE', featureCode: 'DEFAULT', modelType: 'text_model', prompt: '请总结：{{input.text}}' },
  HTTP: { name: '查询订单', method: 'GET', url: 'https://api.example.com/orders/{{input.orderId}}' },
  AGENT: { modelMode: 'ROUTE', featureCode: 'DEFAULT', modelType: 'text_model', prompt: '{{input.task}}', tools: [{ name: 'query_order', toolType: 'HTTP', config: { method: 'GET', url: 'https://api.example.com/orders/{{input.orderId}}' } }] },
  RAG: { knowledgeBaseId: 1, query: '{{input.query}}', modelMode: 'ROUTE', featureCode: 'DEFAULT', modelType: 'text_model' },
  KNOWLEDGE_RETRIEVAL: { knowledgeBaseId: 1, query: '{{input.query}}', topK: 5, scoreThreshold: 0 },
  KNOWLEDGE_UPSERT: { knowledgeBaseId: 1, inputMode: 'TEXT', content: '{{input.document}}', fileName: 'document.txt', contentType: 'text/plain' },
  CONDITION: { condition: { left: '{{input.status}}', operator: 'EQ', right: 'READY' } },
  ITERATION: { collection: '{{input.items}}', bodyGraph: { nodes: [], edges: [] } },
  LOOP: { condition: { left: '{{loop.index}}', operator: 'LT', right: 3 }, bodyGraph: { nodes: [], edges: [] } },
  SWITCH: { cases: [{ branch: 'approved', condition: { left: '{{input.status}}', operator: 'EQ', right: 'APPROVED' } }], defaultBranch: 'default' },
  MERGE: { mode: 'ARRAY', values: ['{{nodes.first}}', '{{nodes.second}}'] },
  SUB_WORKFLOW: { workflowCode: 'ORDER_REVIEW', inputs: { orderId: '{{input.orderId}}' } },
  WAIT: { durationMode: 'SECONDS', seconds: 5 }, SET_VARIABLE: { output: { orderId: '{{input.orderId}}' } },
  TEMPLATE: { template: '订单 {{input.orderId}} 已处理' }, JSON_PARSE: { value: '{{input.jsonText}}' },
  JSON_VALIDATE: { value: '{{input.payload}}', schema: { type: 'object', required: ['id'] }, failOnError: true },
  TRANSFORM: { output: { id: '{{input.orderId}}', status: '{{input.status}}' } },
  FILTER: { collection: '{{input.items}}', condition: { left: '{{item.enabled}}', operator: 'EQ', right: true } },
  SORT: { collection: '{{input.items}}', path: 'createdAt', direction: 'DESC' },
  AGGREGATE: { collection: '{{input.items}}', operation: 'SUM', path: 'amount' },
  CSV: { operation: 'PARSE', value: '{{input.csvText}}' },
  QUESTION_CLASSIFIER: { modelMode: 'ROUTE', featureCode: 'DEFAULT', modelType: 'text_model', input: '{{input.question}}', categories: [{ name: '售前' }, { name: '售后' }] },
  PARAMETER_EXTRACTOR: { modelMode: 'ROUTE', featureCode: 'DEFAULT', modelType: 'text_model', input: '{{input.text}}', schema: { type: 'object', properties: { orderId: { type: 'string' } } } },
  STRUCTURED_OUTPUT: { value: '{{nodes.llm.content}}', schema: { type: 'object', required: ['answer'] } },
  DOCUMENT_EXTRACTOR: { inputMode: 'TEXT', content: '{{input.document}}', fileName: 'example.txt' },
  WEBHOOK_TRIGGER: { connectionId: 1 }, SCHEDULE_TRIGGER: { cron: '0 0 9 * * ?', zoneId: 'Asia/Shanghai' },
  EMAIL_SEND: { routeId: 1, subject: '订单 {{input.orderId}} 状态', body: '当前状态：{{input.status}}' },
  IM_NOTIFY: { connectionId: 1, body: { text: '任务 {{input.taskId}} 已完成' } },
  SQL_QUERY: { connectionId: 1, query: 'SELECT id, status FROM orders WHERE id = ?', parameters: ['{{input.orderId}}'] },
  REDIS_COMMAND: { connectionId: 1, command: 'GET', arguments: ['order:{{input.orderId}}'] },
  S3_OBJECT: { connectionId: 1, operation: 'GET', bucket: 'workflow-files', key: '{{input.objectKey}}' },
  KAFKA_PUBLISH: { connectionId: 1, topic: 'order-events', key: '{{input.orderId}}', value: '{{input}}' },
  KAFKA_TRIGGER: { connectionId: 1, topic: 'order-events', groupId: 'workflow-consumer' },
  RABBITMQ_PUBLISH: { connectionId: 1, destinationMode: 'EXCHANGE', exchange: 'order.events', routingKey: 'order.updated', value: '{{input}}' },
  RABBITMQ_TRIGGER: { connectionId: 1, queue: 'order.workflow', exchange: 'order.events', routingKey: 'order.created' },
  TAVILY_TOOL: { connectionId: 1, operation: 'SEARCH', query: '{{input.query}}', searchDepth: 'basic', maxResults: 5 }
})

/** 每个原生节点都使用独立的双语行为、输入、输出和限制说明。 */
export const WORKFLOW_NODE_DOCUMENTATION = Object.freeze(Object.fromEntries(DOCUMENTED_WORKFLOW_NODE_TYPES.map(nodeType => [nodeType, Object.freeze({
  nodeType,
  input: `workflowNodeDocs.nodes.${nodeType}.input`,
  output: `workflowNodeDocs.nodes.${nodeType}.output`,
  behavior: `workflowNodeDocs.nodes.${nodeType}.behavior`,
  limitations: `workflowNodeDocs.nodes.${nodeType}.limitations`
})])))

/** 合并字段默认值、人工示例和模板默认配置，生成当前模板的可参考配置。 */
export function workflowNodeExample(nodeType, templateConfig = {}) {
  const type = String(nodeType || '').toUpperCase()
  const fields = nodeConfigFields(type)
  const example = {}
  for (const item of fields) example[item.key] = clone(item.defaultValue)
  Object.assign(example, clone(WORKFLOW_NODE_EXAMPLE_OVERRIDES[type] || {}), cloneObject(templateConfig))
  for (const item of fields) {
    if (!nodeConfigFieldApplicable(type, item.key, example)) delete example[item.key]
  }
  return example
}

/** 将任意系统、市场或自建模板转换成统一只读文档。 */
export function workflowNodeDocument(template, translate, hasTranslation) {
  const nodeType = String(template?.nodeType || '').toUpperCase()
  const base = WORKFLOW_NODE_DOCUMENTATION[nodeType]
  if (!base) return null
  const exampleConfig = workflowNodeExample(nodeType, template?.config)
  return {
    ...base,
    name: template?.name || nodeType,
    summary: template?.description || translate(base.behavior),
    source: template?.source || 'SYSTEM',
    category: template?.functionalCategory || 'BASIC',
    behavior: translated(base.behavior, 'workflowNodeDocs.genericBehavior'),
    input: translated(base.input, 'workflowNodeDocs.genericInput'),
    output: translated(base.output, 'workflowNodeDocs.genericOutput'),
    limitations: translated(base.limitations, 'workflowNodeDocs.defaultLimitations'),
    fields: nodeConfigFields(nodeType, exampleConfig).filter(item => nodeConfigFieldApplicable(nodeType, item.key, exampleConfig)),
    example: JSON.stringify(exampleConfig, null, 2),
    externalVersion: template?.externalVersion || '',
    externalPublisher: template?.externalPublisher || ''
  }

  /** 在词条意外缺失时提供安全回退，同时让完整性测试阻止原生词条漂移。 */
  function translated(key, fallback) {
    return hasTranslation(key) ? translate(key) : translate(fallback, { nodeType })
  }
}

/** 返回缺少说明骨架的节点类型，测试和页面均可显式发现目录漂移。 */
export function undocumentedWorkflowNodeTypes() {
  return WORKFLOW_NODE_TYPES.filter(type => !WORKFLOW_NODE_DOCUMENTATION[type])
}

/** 深复制可序列化示例值，避免共享对象被页面或测试污染。 */
function clone(value) { return value === undefined ? undefined : JSON.parse(JSON.stringify(value)) }

/** 只接受普通模板配置对象，忽略数组和空值。 */
function cloneObject(value) { return value && typeof value === 'object' && !Array.isArray(value) ? clone(value) : {} }
