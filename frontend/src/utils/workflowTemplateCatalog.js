export const WORKFLOW_TEMPLATE_SOURCES = ['SYSTEM', 'N8N', 'DIFY']

export const WORKFLOW_TEMPLATE_CATEGORIES = [
  'BASIC', 'AI', 'FLOW_CONTROL', 'DATA_TRANSFORM', 'TEXT_DOCUMENT',
  'NETWORK_API', 'TRIGGER', 'NOTIFICATION', 'DATA_STORAGE', 'MESSAGE_QUEUE'
]

const DEFAULT_CATEGORIES = {
  START: 'BASIC', END: 'BASIC',
  LLM: 'AI', AGENT: 'AI', QUESTION_CLASSIFIER: 'AI', PARAMETER_EXTRACTOR: 'AI', STRUCTURED_OUTPUT: 'AI',
  CONDITION: 'FLOW_CONTROL', SWITCH: 'FLOW_CONTROL', ITERATION: 'FLOW_CONTROL', LOOP: 'FLOW_CONTROL',
  MERGE: 'FLOW_CONTROL', SUB_WORKFLOW: 'FLOW_CONTROL', WAIT: 'FLOW_CONTROL',
  SET_VARIABLE: 'DATA_TRANSFORM', JSON_PARSE: 'DATA_TRANSFORM', JSON_VALIDATE: 'DATA_TRANSFORM',
  TRANSFORM: 'DATA_TRANSFORM', FILTER: 'DATA_TRANSFORM', SORT: 'DATA_TRANSFORM', AGGREGATE: 'DATA_TRANSFORM',
  TEMPLATE: 'TEXT_DOCUMENT', CSV: 'TEXT_DOCUMENT', DOCUMENT_EXTRACTOR: 'TEXT_DOCUMENT',
  HTTP: 'NETWORK_API', WEBHOOK_TRIGGER: 'TRIGGER', SCHEDULE_TRIGGER: 'TRIGGER',
  EMAIL_SEND: 'NOTIFICATION', IM_NOTIFY: 'NOTIFICATION', SQL_QUERY: 'DATA_STORAGE',
  REDIS_COMMAND: 'DATA_STORAGE', S3_OBJECT: 'DATA_STORAGE', KAFKA_PUBLISH: 'MESSAGE_QUEUE',
  KAFKA_TRIGGER: 'MESSAGE_QUEUE', RABBITMQ_PUBLISH: 'MESSAGE_QUEUE', RABBITMQ_TRIGGER: 'MESSAGE_QUEUE'
}

const LOCALIZED_TEMPLATE_FIELDS = new Set(['name', 'description'])

/** 返回节点类型的稳定默认功能分类。 */
export function defaultTemplateCategory(nodeType) {
  return DEFAULT_CATEGORIES[String(nodeType || '').toUpperCase()] || 'BASIC'
}

/** 兼容迁移前接口，为模板补齐受控来源和功能分类。 */
export function normalizeTemplateMetadata(template = {}) {
  const source = WORKFLOW_TEMPLATE_SOURCES.includes(String(template.source || '').toUpperCase())
    ? String(template.source).toUpperCase() : 'SYSTEM'
  const requestedCategory = String(template.functionalCategory || '').toUpperCase()
  const functionalCategory = WORKFLOW_TEMPLATE_CATEGORIES.includes(requestedCategory)
    ? requestedCategory : defaultTemplateCategory(template.nodeType)
  return { ...template, source, functionalCategory }
}

/** 返回系统内置模板的本地化词条键，自定义模板继续使用管理员录入文案。 */
export function systemTemplateTranslationKey(template, field) {
  const nodeType = String(template?.nodeType || '').toUpperCase()
  if (!template?.systemTemplate || !nodeType || !LOCALIZED_TEMPLATE_FIELDS.has(field)) return ''
  return `workflowCatalog.templates.${nodeType}.${field}`
}

/** 解析模板展示文案；系统词条缺失或非系统模板时安全回退到原始字段。 */
export function localizedTemplateText(template, field, translate, hasTranslation) {
  const key = systemTemplateTranslationKey(template, field)
  if (key && hasTranslation(key)) return translate(key)
  return String(template?.[field] || '')
}

/** 按固定功能顺序对模板分组，并可按需保留停用模板。 */
export function groupWorkflowTemplates(templates, includeDisabled = false) {
  const normalized = (templates || []).map(normalizeTemplateMetadata)
    .filter(template => includeDisabled || template.enabled)
  return WORKFLOW_TEMPLATE_CATEGORIES.map(category => ({
    category,
    items: normalized.filter(template => template.functionalCategory === category)
  })).filter(group => group.items.length)
}

/** 按必选来源和功能分类过滤模板，并按需保留停用模板。 */
export function filterWorkflowTemplates(templates, source, functionalCategory, includeDisabled = false) {
  if (!WORKFLOW_TEMPLATE_SOURCES.includes(source) || !WORKFLOW_TEMPLATE_CATEGORIES.includes(functionalCategory)) return []
  return (templates || []).map(normalizeTemplateMetadata).filter(template =>
    (includeDisabled || template.enabled) && template.source === source && template.functionalCategory === functionalCategory)
}
