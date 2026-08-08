import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { cloneConfig, compatibleWorkflowModelId, compatibleWorkflowResourceId, configValueType, createConfigValue, extraConfigKeys, filterWorkflowConnectionOptions, filterWorkflowModelOptions, isSafeConfigKey, missingNodeConfigRequirements, nodeConfigFieldApplicable, nodeConfigFieldRequirement, nodeConfigFields, workflowConnectionOptionLabel, workflowMailRouteOptionLabel, workflowModelOptionLabel, WORKFLOW_NODE_TYPES, WORKFLOW_NODE_VALIDATION_TYPES } from '../src/utils/workflowNodeConfig.js'

const nodeManagementSource = readFileSync(new URL('../src/views/WorkflowNodesView.vue', import.meta.url), 'utf8')
const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')
const configEditorSource = readFileSync(new URL('../src/components/WorkflowNodeConfigEditor.vue', import.meta.url), 'utf8')

test('节点管理按两层必选条件展示卡片并保留模板保护权限', () => {
  assert.match(nodeManagementSource, /v-for="row in filteredRows"/)
  assert.match(nodeManagementSource, /class="node-template-card"/)
  assert.match(nodeManagementSource, /filterWorkflowTemplates\(rows\.value, selectedSource\.value, selectedCategory\.value, true\)/)
  assert.match(nodeManagementSource, /!row\.systemTemplate/)
  assert.match(nodeManagementSource, /workflow:node:update/)
  assert.match(nodeManagementSource, /workflow:node:delete/)
})

test('模板和画布节点配置统一使用可视化编辑器', () => {
  for (const source of [nodeManagementSource, graphEditorSource]) {
    assert.match(source, /WorkflowNodeConfigEditor/)
    assert.doesNotMatch(source, /configText|实例配置 JSON|Default Configuration JSON|默认配置 JSON/)
  }
})

test('全部原生节点都有可视化配置定义', () => {
  const expected = ['START', 'END', 'LLM', 'HTTP', 'AGENT', 'CONDITION', 'ITERATION', 'LOOP', 'SWITCH', 'MERGE', 'SQL_QUERY', 'RABBITMQ_TRIGGER']
  expected.forEach(type => assert.ok(WORKFLOW_NODE_TYPES.includes(type), type))
  assert.equal(nodeConfigFields('unknown').length, 0)
  assert.deepEqual(nodeConfigFields('WAIT').map(field => field.key), ['durationMode', 'seconds', 'milliseconds'])
  assert.deepEqual(nodeConfigFields('SQL_QUERY').map(field => field.key), ['connectionId', 'query', 'parameters', 'timeoutSeconds', 'maxRows', 'maxAttempts', 'retryDelayMillis', 'onError'])
  assert.deepEqual(nodeConfigFields('WEBHOOK_TRIGGER').map(field => field.key), ['connectionId'])
  assert.deepEqual(nodeConfigFields('SCHEDULE_TRIGGER').map(field => field.key), ['cron', 'zoneId'])
  assert.deepEqual(nodeConfigFields('LLM').slice(-3).map(field => field.key), ['maxAttempts', 'retryDelayMillis', 'onError'])
  for (const type of ['LLM', 'AGENT', 'QUESTION_CLASSIFIER', 'PARAMETER_EXTRACTOR']) {
    assert.equal(nodeConfigFields(type).find(field => field.key === 'modelId').editor, 'model', type)
  }
  assert.equal(nodeConfigFields('EMAIL_SEND').find(field => field.key === 'routeId').editor, 'mailRoute')
  for (const type of ['WEBHOOK_TRIGGER', 'IM_NOTIFY', 'SQL_QUERY', 'REDIS_COMMAND', 'S3_OBJECT', 'KAFKA_PUBLISH',
    'KAFKA_TRIGGER', 'RABBITMQ_PUBLISH', 'RABBITMQ_TRIGGER']) {
    assert.equal(nodeConfigFields(type).find(field => field.key === 'connectionId').editor, 'connection', type)
  }
  assert.equal(Object.values(WORKFLOW_NODE_TYPES).flatMap(type => nodeConfigFields(type))
    .some(field => ['modelId', 'routeId', 'connectionId'].includes(field.key) && field.editor === 'number'), false)
})

test('Agent 模型下拉按动态模型类型过滤并生成可识别标签', () => {
  const options = [
    { id: 1, name: 'Text', modelName: 'text-v1', providerName: 'OpenAI', supportedModelTypes: ['text_model'] },
    { id: 2, name: 'Vision', modelName: 'vision-v1', providerName: 'Vendor', supportedModelTypes: ['TEXT_MODEL', 'vision_model'] },
    { id: 3, name: 'Audio', modelName: 'audio-v1', providerName: 'Vendor', supportedModelTypes: ['audio_model'] }
  ]
  assert.deepEqual(filterWorkflowModelOptions(options, 'text_model').map(option => option.id), [1, 2])
  assert.deepEqual(filterWorkflowModelOptions(options, 'VISION_MODEL').map(option => option.id), [2])
  assert.deepEqual(filterWorkflowModelOptions(options, 'audio_model').map(option => option.id), [3])
  assert.deepEqual(filterWorkflowModelOptions(options, 'unknown'), [])
  assert.equal(workflowModelOptionLabel(options[0]), 'Text (text-v1) · OpenAI')
  assert.equal(compatibleWorkflowModelId(options, 'text_model', '2'), 2)
  assert.equal(compatibleWorkflowModelId(options, 'vision_model', 1), null)
  assert.equal(compatibleWorkflowModelId(options, 'text_model', ''), null)
  assert.equal(compatibleWorkflowModelId(options, 'text_model', 'invalid'), null)
})

test('连接下拉按节点运行时允许类型过滤并生成可识别标签', () => {
  const options = [
    { id: 1, code: 'MYSQL_MAIN', name: '主库', connectionType: 'MYSQL' },
    { id: 2, code: 'PG_REPORT', name: '报表库', connectionType: 'POSTGRESQL' },
    { id: 3, code: 'CACHE', name: '缓存', connectionType: 'REDIS' },
    { id: 4, code: 'NOTICE', name: '通知', connectionType: 'WEBHOOK' }
  ]
  assert.deepEqual(filterWorkflowConnectionOptions(options, 'SQL_QUERY').map(option => option.id), [1, 2])
  assert.deepEqual(filterWorkflowConnectionOptions(options, 'REDIS_COMMAND').map(option => option.id), [3])
  assert.deepEqual(filterWorkflowConnectionOptions(options, 'WEBHOOK_TRIGGER').map(option => option.id), [4])
  assert.deepEqual(filterWorkflowConnectionOptions(options, 'UNKNOWN'), [])
  assert.equal(workflowConnectionOptionLabel(options[0]), '主库 (MYSQL_MAIN) · MYSQL')
})

test('邮件路由和连接资源 ID 仅保留现有数字选项', () => {
  const options = [{ id: 7, businessCode: 'DEFAULT', name: '默认邮件路由' }]
  assert.equal(workflowMailRouteOptionLabel(options[0]), '默认邮件路由 (DEFAULT)')
  assert.equal(compatibleWorkflowResourceId(options, '7'), 7)
  assert.equal(compatibleWorkflowResourceId(options, 8), null)
  assert.equal(compatibleWorkflowResourceId(options, ''), null)
  assert.equal(compatibleWorkflowResourceId(options, 'invalid'), null)
})

test('全部 AI 节点指定模型使用可搜索可清空下拉并在类型不兼容时移除旧值', () => {
  assert.match(configEditorSource, /field\.editor === 'model'/)
  assert.match(configEditorSource, /filterable clearable[^>]*:loading="modelOptionsLoading"/)
  assert.match(configEditorSource, /http\.get\('\/workflow\/model-options'\)/)
  assert.match(configEditorSource, /@update:model-value="setModelId"/)
  assert.match(configEditorSource, /compatibleWorkflowModelId\(modelOptions\.value, type, config\.value\.modelId\) === null/)
  assert.match(configEditorSource, /removeField\('modelId'\)/)
  assert.doesNotMatch(configEditorSource, /field\.editor === 'model'[\s\S]{0,300}<el-input-number/)
})

test('邮件路由和连接配置使用精简资源接口且加载失败时保留原值', () => {
  assert.match(configEditorSource, /field\.editor === 'mailRoute'/)
  assert.match(configEditorSource, /field\.editor === 'connection'/)
  assert.match(configEditorSource, /http\.get\('\/workflow\/mail-route-options'\)/)
  assert.match(configEditorSource, /http\.get\('\/workflow\/connection-options'\)/)
  assert.match(configEditorSource, /workflowMailRouteOptionLabel\(option\)/)
  assert.match(configEditorSource, /workflowConnectionOptionLabel\(option\)/)
  assert.match(configEditorSource, /if \(!mailRouteOptionsLoaded\.value \|\| !hasField\('routeId'\)\) return/)
  assert.match(configEditorSource, /if \(!connectionOptionsLoaded\.value \|\| !hasField\('connectionId'\)\) return/)
  assert.match(configEditorSource, /catch \{ mailRouteOptionsError\.value = true \}/)
  assert.match(configEditorSource, /catch \{ connectionOptionsError\.value = true \}/)
})

test('Agent 指定模型下拉限制为输入框宽度并为溢出标签提供完整标题', () => {
  assert.match(configEditorSource, /filterable clearable fit-input-width :loading="modelOptionsLoading"/)
  assert.match(configEditorSource, /#label="\{ label \}"[\s\S]*workflow-model-option-label" truncated>\{\{ label \}\}/)
  assert.match(configEditorSource, /workflow-model-option-label" truncated>\{\{ workflowModelOptionLabel\(option\) \}\}/)
  assert.match(configEditorSource, /\.workflow-model-option-label \{[^}]*width: 100%;[^}]*min-width: 0;/)
})

test('配置字段区分必填、条件必填和具有运行默认值的可选项', () => {
  assert.equal(nodeConfigFieldRequirement('HTTP', 'url'), 'required')
  assert.equal(nodeConfigFieldRequirement('S3_OBJECT', 'key'), 'conditional')
  assert.equal(nodeConfigFieldRequirement('LLM', 'modelMode'), 'required')
  assert.equal(nodeConfigFieldRequirement('LLM', 'featureCode'), 'conditional')
  assert.equal(nodeConfigFieldRequirement('LLM', 'featureCode', { modelMode: 'ROUTE' }), 'required')
  assert.equal(nodeConfigFieldRequirement('LLM', 'modelId', { modelMode: 'DIRECT' }), 'required')
  assert.equal(nodeConfigFieldRequirement('LLM', 'modelId', { modelMode: 'ROUTE' }), '')
  assert.equal(nodeConfigFieldRequirement('LLM', 'prompt'), 'required')
  assert.equal(nodeConfigFieldRequirement('EMAIL_SEND', 'subject'), 'required')
  assert.equal(nodeConfigFields('HTTP').find(field => field.key === 'url').requirement, 'required')
})

test('全部原生节点都有显式验证策略，防止新增节点绕过必填校验', () => {
  assert.deepEqual([...WORKFLOW_NODE_VALIDATION_TYPES].sort(), [...WORKFLOW_NODE_TYPES].sort())
})

test('AI 节点按模型路由或指定模型方案校验且提示词必填', () => {
  assert.deepEqual(missingNodeConfigRequirements('LLM', {}), ['modelMode', 'prompt'])
  assert.deepEqual(missingNodeConfigRequirements('LLM', { modelMode: 'ROUTE', prompt: 'hello' }), ['featureCode', 'modelType'])
  assert.deepEqual(missingNodeConfigRequirements('LLM', {
    modelMode: 'ROUTE', featureCode: 'CHAT', modelType: 'text_model', prompt: 'hello'
  }), [])
  assert.deepEqual(missingNodeConfigRequirements('LLM', { modelMode: 'DIRECT', modelId: 7, prompt: 'hello' }), [])
  assert.deepEqual(missingNodeConfigRequirements('LLM', { modelMode: 'DIRECT', modelId: 0, prompt: ' ' }), ['modelId', 'prompt'])
  assert.deepEqual(missingNodeConfigRequirements('AGENT', {
    modelMode: 'DIRECT', modelId: 7, prompt: 'complete task', tools: []
  }), ['tools'])
})

test('方案选择只展示当前方案适用字段', () => {
  assert.equal(nodeConfigFieldApplicable('LLM', 'featureCode', {}), false)
  assert.equal(nodeConfigFieldApplicable('LLM', 'featureCode', { modelMode: 'ROUTE' }), true)
  assert.equal(nodeConfigFieldApplicable('LLM', 'modelId', { modelMode: 'ROUTE' }), false)
  assert.equal(nodeConfigFieldApplicable('LLM', 'modelId', { modelMode: 'DIRECT' }), true)
  assert.equal(nodeConfigFieldApplicable('WAIT', 'seconds', { durationMode: 'SECONDS' }), true)
  assert.equal(nodeConfigFieldApplicable('WAIT', 'milliseconds', { durationMode: 'SECONDS' }), false)
  assert.equal(nodeConfigFieldApplicable('DOCUMENT_EXTRACTOR', 'base64', { inputMode: 'BASE64' }), true)
  assert.equal(nodeConfigFieldApplicable('RABBITMQ_PUBLISH', 'exchange', { destinationMode: 'DEFAULT_EXCHANGE' }), false)
})

test('发布提示覆盖固定必填、方案条件、操作条件和参数数量', () => {
  assert.deepEqual(missingNodeConfigRequirements('HTTP', {}), ['method', 'url'])
  assert.deepEqual(missingNodeConfigRequirements('HTTP', { method: 'GET', url: 'https://example.test' }), [])
  assert.deepEqual(missingNodeConfigRequirements('ITERATION', { collection: '{{input.items}}' }), ['bodyGraph'])
  assert.deepEqual(missingNodeConfigRequirements('DOCUMENT_EXTRACTOR', {}), ['inputMode'])
  assert.deepEqual(missingNodeConfigRequirements('DOCUMENT_EXTRACTOR', { inputMode: 'TEXT', content: 'text' }), [])
  assert.deepEqual(missingNodeConfigRequirements('S3_OBJECT', { connectionId: 1 }), ['operation'])
  assert.deepEqual(missingNodeConfigRequirements('S3_OBJECT', { connectionId: 1, operation: 'LIST' }), [])
  assert.deepEqual(missingNodeConfigRequirements('S3_OBJECT', { connectionId: 1, operation: 'GET' }), ['key'])
  assert.deepEqual(missingNodeConfigRequirements('REDIS_COMMAND', { connectionId: 1, command: 'HSET', arguments: ['key', 'field'] }), ['arguments'])
  assert.deepEqual(missingNodeConfigRequirements('RABBITMQ_PUBLISH', { connectionId: 1, value: null }), ['destinationMode'])
  assert.deepEqual(missingNodeConfigRequirements('RABBITMQ_PUBLISH', {
    connectionId: 1, destinationMode: 'DEFAULT_EXCHANGE', routingKey: 'orders', value: null
  }), [])
  assert.deepEqual(missingNodeConfigRequirements('EMAIL_SEND', { routeId: 2, subject: '', body: '' }), ['subject'])
  assert.deepEqual(missingNodeConfigRequirements('EMAIL_SEND', { routeId: 2, subject: 'Notice' }), [])
})

test('必填字段直接编辑且非必填和条件必填字段通过开关启停', () => {
  assert.match(configEditorSource, /v-if="fieldCanToggle\(field\)" class="workflow-config-enable"/)
  assert.match(configEditorSource, /:model-value="hasField\(field\.key\)" @change="toggleConfigured\(field, \$event\)"/)
  assert.match(configEditorSource, /function fieldCanToggle\(field\).*field\.requirement !== 'required'/)
  assert.match(configEditorSource, /function toggleConfigured\(field, enabled\)/)
  assert.match(configEditorSource, /:model-value="fieldValue\(field\)"/)
  assert.match(configEditorSource, /<fieldset[^>]*:disabled="fieldDisabled\(field\)"/)
  assert.match(configEditorSource, /missingNodeConfigRequirements\(props\.nodeType, config\.value\)/)
})

test('非必填默认值字段未启用时显示禁用且必填缺失提示保持最高优先级', () => {
  assert.match(configEditorSource, /function hasDefault\(field\).*hasOwnProperty\.call\(field, 'defaultValue'\)/)
  assert.match(configEditorSource, /if \(hasField\(field\.key\)\) return 'workflowConfig\.configured'/)
  assert.match(configEditorSource, /if \(fieldCanToggle\(field\)\) return 'workflowConfig\.disabled'/)
  assert.match(configEditorSource, /hasDefault\(field\) \? 'workflowConfig\.defaultValue' : 'workflowConfig\.notConfigured'/)
  assert.match(configEditorSource, /v-if="fieldRequirementMissing\(field\)"[\s\S]*v-else[\s\S]*fieldStatusKey\(field\)/)
  assert.match(configEditorSource, /disabled: fieldDisabled\(field\)/)
})

test('零、false、null、空对象和空数组均可作为启用时写入的默认值', () => {
  const examples = [
    nodeConfigFields('LLM').find(field => field.key === 'temperature').defaultValue,
    nodeConfigFields('LLM').find(field => field.key === 'enableThinking').defaultValue,
    nodeConfigFields('LLM').find(field => field.key === 'modelId').defaultValue,
    nodeConfigFields('HTTP').find(field => field.key === 'headers').defaultValue,
    nodeConfigFields('AGENT').find(field => field.key === 'tools').defaultValue
  ]
  assert.deepEqual(examples, [0, false, null, {}, []])
  assert.match(configEditorSource, /setField\(field\.key, cloneValue\(field\.defaultValue\)\)/)
})

test('模板基础信息完整标记必填并说明发布前校验', () => {
  assert.match(nodeManagementSource, /:label="t\('common\.code'\)" required/)
  assert.match(nodeManagementSource, /:label="t\('common\.name'\)" required/)
  assert.match(nodeManagementSource, /:label="t\('common\.type'\)" required/)
  assert.match(nodeManagementSource, /:label="t\('workflowNodes\.source'\)" required/)
  assert.match(nodeManagementSource, /:label="t\('workflowNodes\.category'\)" required/)
  assert.match(nodeManagementSource, /workflowNodes\.requiredHint/)
})

test('配置副本保留深层对象、数组和标量且不污染原值', () => {
  const source = { text: 'hello', count: 0, enabled: false, empty: null, nested: { list: [1, 'two', { ok: true }] } }
  const copy = cloneConfig(source)
  copy.nested.list[2].ok = false
  assert.equal(source.nested.list[2].ok, true)
  assert.deepEqual(Object.values(source).map(configValueType), ['string', 'number', 'boolean', 'null', 'object'])
})

test('可视化参数类型生成正确的空值', () => {
  assert.deepEqual(['string', 'number', 'boolean', 'null', 'object', 'array'].map(createConfigValue), ['', 0, false, null, {}, []])
})

test('附加参数排除标准字段和子画布但保留未知配置', () => {
  const config = { collection: '{{input.items}}', maxIterations: 10, bodyGraph: { nodes: [], edges: [] }, customFlag: true }
  assert.deepEqual(extraConfigKeys(config, 'ITERATION'), ['customFlag'])
})

test('参数名拒绝空值和原型污染键并允许常用请求头', () => {
  for (const key of ['', ' ', '__proto__', 'prototype', 'constructor']) assert.equal(isSafeConfigKey(key), false)
  for (const key of ['Authorization', 'x-request-id', 'nested.value']) assert.equal(isSafeConfigKey(key), true)
  assert.equal(isSafeConfigKey('x'.repeat(121)), false)
})
