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

/** 为节点文档提供可直接理解的数据输入与输出示例，示例不包含真实凭据或敏感信息。 */
const WORKFLOW_NODE_IO_EXAMPLES = Object.freeze({
  START: pair({ orderId: 'ORD-1001', question: '订单什么时候发货？' }, { orderId: 'ORD-1001', question: '订单什么时候发货？' }),
  END: pair({ answer: '订单预计今天发货。' }, { answer: '订单预计今天发货。' }),
  LLM: pair({ text: '请概括本周销售情况。' }, { content: '本周销售额稳步增长。', model: 'example-model', totalTokens: 128 }),
  HTTP: pair({ orderId: 'ORD-1001' }, { httpStatus: 200, durationMs: 86, body: '{"status":"SHIPPED"}', json: { status: 'SHIPPED' } }),
  AGENT: pair({ task: '查询订单并生成回复', orderId: 'ORD-1001' }, { content: '订单已发货。', toolResults: [{ name: 'query_order', success: true }] }),
  RAG: pair({ query: '退款规则是什么？' }, { answer: '签收后 7 天内可以申请退款。[1]', citations: [{ index: 1, source: 'refund-policy.md' }], matches: [] }),
  KNOWLEDGE_RETRIEVAL: pair({ query: '退款规则' }, { knowledgeBaseId: 1, count: 1, matches: [{ source: 'refund-policy.md', score: 0.91 }] }),
  KNOWLEDGE_UPSERT: pair({ document: '退款申请应在签收后 7 天内提交。' }, { documentId: 42, fileName: 'document.txt', status: 'INDEXED', chunkCount: 1 }),
  CONDITION: pair({ status: 'READY' }, { matched: true }),
  ITERATION: pair({ items: [{ id: 1 }, { id: 2 }] }, { iterations: 2, items: [{ id: 1, processed: true }, { id: 2, processed: true }] }),
  LOOP: pair({ approved: false }, { iterations: 3, items: [{ attempt: 1 }, { attempt: 2 }, { attempt: 3 }] }),
  SWITCH: pair({ status: 'APPROVED' }, { matched: true, branch: 'approved' }),
  MERGE: pair({ first: { id: 1 }, second: { status: 'READY' } }, [{ id: 1 }, { status: 'READY' }]),
  SUB_WORKFLOW: pair({ orderId: 'ORD-1001' }, { approved: true, reviewer: 'workflow' }),
  WAIT: pair({ taskId: 'TASK-1001' }, { taskId: 'TASK-1001' }),
  SET_VARIABLE: pair({ orderId: 'ORD-1001' }, { orderId: 'ORD-1001' }),
  TEMPLATE: pair({ orderId: 'ORD-1001' }, { text: '订单 ORD-1001 已处理' }),
  JSON_PARSE: pair({ jsonText: '{"id":1,"enabled":true}' }, { id: 1, enabled: true }),
  JSON_VALIDATE: pair({ payload: { id: 'ORD-1001' } }, { valid: true, value: { id: 'ORD-1001' }, errors: [] }),
  TRANSFORM: pair({ orderId: 'ORD-1001', status: 'READY' }, { id: 'ORD-1001', status: 'READY' }),
  FILTER: pair({ items: [{ id: 1, enabled: true }, { id: 2, enabled: false }] }, [{ id: 1, enabled: true }]),
  SORT: pair({ items: [{ id: 2 }, { id: 1 }] }, [{ id: 1 }, { id: 2 }]),
  AGGREGATE: pair({ items: [{ amount: 12 }, { amount: 8 }] }, { value: 20 }),
  CSV: pair({ csvText: 'id,name\n1,示例' }, [{ id: '1', name: '示例' }]),
  QUESTION_CLASSIFIER: pair({ question: '商品无法开机怎么办？' }, { category: '售后' }),
  PARAMETER_EXTRACTOR: pair({ text: '查询订单 ORD-1001' }, { orderId: 'ORD-1001' }),
  STRUCTURED_OUTPUT: pair({ text: '{"answer":"已完成"}' }, { answer: '已完成' }),
  DOCUMENT_EXTRACTOR: pair({ document: '示例文档正文' }, { text: '示例文档正文', metadata: { fileName: 'example.txt' } }),
  WEBHOOK_TRIGGER: pair({ event: 'order.created', data: { id: 'ORD-1001' } }, { event: 'order.created', data: { id: 'ORD-1001' } }),
  SCHEDULE_TRIGGER: pair({ scheduledAt: '2026-08-10T09:00:00+08:00' }, { scheduledAt: '2026-08-10T09:00:00+08:00' }),
  EMAIL_SEND: pair({ orderId: 'ORD-1001', status: 'SHIPPED' }, { sent: true, routeCode: 'DEFAULT_MAIL' }),
  IM_NOTIFY: pair({ taskId: 'TASK-1001' }, { httpStatus: 200, durationMs: 72, body: 'ok' }),
  SQL_QUERY: pair({ orderId: 'ORD-1001' }, { count: 1, rows: [{ id: 'ORD-1001', status: 'SHIPPED' }] }),
  REDIS_COMMAND: pair({ orderId: 'ORD-1001' }, { value: 'SHIPPED' }),
  S3_OBJECT: pair({ objectKey: 'reports/weekly.json' }, { bucket: 'workflow-files', key: 'reports/weekly.json', contentType: 'application/json' }),
  KAFKA_PUBLISH: pair({ orderId: 'ORD-1001', status: 'SHIPPED' }, { topic: 'order-events', partition: 0, offset: 128 }),
  KAFKA_TRIGGER: pair({ topic: 'order-events', value: { orderId: 'ORD-1001' } }, { topic: 'order-events', value: { orderId: 'ORD-1001' } }),
  RABBITMQ_PUBLISH: pair({ orderId: 'ORD-1001', status: 'CREATED' }, { published: true, exchange: 'order.events' }),
  RABBITMQ_TRIGGER: pair({ queue: 'order.workflow', value: { orderId: 'ORD-1001' } }, { queue: 'order.workflow', value: { orderId: 'ORD-1001' } }),
  TAVILY_TOOL: pair({ query: 'Base AI workflow' }, { httpStatus: 200, durationMs: 310, json: { results: [{ title: 'Example result', url: 'https://example.com' }] } })
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
  const fields = nodeConfigFields(nodeType, exampleConfig).filter(item => nodeConfigFieldApplicable(nodeType, item.key, exampleConfig))
  const examples = WORKFLOW_NODE_IO_EXAMPLES[nodeType] || pair({}, {})
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
    fields,
    requiredFields: fields.filter(item => item.requirement === 'required').map(item => item.key),
    example: JSON.stringify(exampleConfig, null, 2),
    inputExample: JSON.stringify(examples.input, null, 2),
    outputExample: JSON.stringify(examples.output, null, 2),
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

/** 创建输入输出示例对，保持目录定义紧凑且结构统一。 */
function pair(input, output) { return Object.freeze({ input, output }) }
