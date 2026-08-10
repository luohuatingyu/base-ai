import { nodeConfigFields, WORKFLOW_NODE_TYPES } from './workflowNodeConfig.js'

/** 显式维护已完成人工审阅的节点说明目录，新增节点时测试会要求同步登记。 */
export const DOCUMENTED_WORKFLOW_NODE_TYPES = Object.freeze([
  'START', 'END', 'LLM', 'HTTP', 'AGENT', 'RAG', 'CONDITION', 'ITERATION', 'LOOP', 'SWITCH', 'MERGE',
  'SUB_WORKFLOW', 'WAIT', 'SET_VARIABLE', 'TEMPLATE', 'JSON_PARSE', 'JSON_VALIDATE', 'TRANSFORM', 'FILTER',
  'SORT', 'AGGREGATE', 'CSV', 'QUESTION_CLASSIFIER', 'PARAMETER_EXTRACTOR', 'STRUCTURED_OUTPUT',
  'DOCUMENT_EXTRACTOR', 'WEBHOOK_TRIGGER', 'SCHEDULE_TRIGGER', 'EMAIL_SEND', 'IM_NOTIFY', 'SQL_QUERY',
  'REDIS_COMMAND', 'S3_OBJECT', 'KAFKA_PUBLISH', 'KAFKA_TRIGGER', 'RABBITMQ_PUBLISH', 'RABBITMQ_TRIGGER',
  'TAVILY_TOOL'
])

/** 每个原生节点都必须拥有稳定的说明骨架；内容由模板元数据和标准配置共同补齐。 */
export const WORKFLOW_NODE_DOCUMENTATION = Object.freeze(Object.fromEntries(DOCUMENTED_WORKFLOW_NODE_TYPES.map(nodeType => [nodeType, Object.freeze({
  nodeType,
  input: nodeType === 'START' ? 'workflowNodeDocs.inputs.workflow' : 'workflowNodeDocs.inputs.upstream',
  output: nodeType === 'END' ? 'workflowNodeDocs.outputs.workflow' : 'workflowNodeDocs.outputs.node',
  behavior: `workflowNodeDocs.behaviors.${nodeType}`,
  limitations: 'workflowNodeDocs.defaultLimitations'
})])))

/** 将任意系统、市场或自建模板转换成统一只读文档。 */
export function workflowNodeDocument(template, translate, hasTranslation) {
  const nodeType = String(template?.nodeType || '').toUpperCase()
  const base = WORKFLOW_NODE_DOCUMENTATION[nodeType]
  if (!base) return null
  const behavior = hasTranslation(base.behavior) ? translate(base.behavior) : translate('workflowNodeDocs.genericBehavior', { nodeType })
  return { ...base, name: template?.name || nodeType, summary: template?.description || behavior,
    source: template?.source || 'SYSTEM', category: template?.functionalCategory || 'BASIC', behavior,
    input: translate(base.input), output: translate(base.output), limitations: translate(base.limitations),
    fields: nodeConfigFields(nodeType, template?.config || {}), example: JSON.stringify(template?.config || {}, null, 2),
    externalVersion: template?.externalVersion || '', externalPublisher: template?.externalPublisher || '' }
}

/** 返回缺少说明骨架的节点类型，测试和页面均可显式发现目录漂移。 */
export function undocumentedWorkflowNodeTypes() { return WORKFLOW_NODE_TYPES.filter(type => !WORKFLOW_NODE_DOCUMENTATION[type]) }
