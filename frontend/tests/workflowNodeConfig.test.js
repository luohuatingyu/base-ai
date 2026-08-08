import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { cloneConfig, configValueType, createConfigValue, extraConfigKeys, isSafeConfigKey, missingNodeConfigRequirements, nodeConfigFieldRequirement, nodeConfigFields, WORKFLOW_NODE_TYPES } from '../src/utils/workflowNodeConfig.js'

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
  assert.deepEqual(nodeConfigFields('WAIT').map(field => field.key), ['seconds', 'milliseconds'])
  assert.deepEqual(nodeConfigFields('SQL_QUERY').map(field => field.key), ['connectionId', 'query', 'parameters', 'timeoutSeconds', 'maxRows', 'maxAttempts', 'retryDelayMillis', 'onError'])
  assert.deepEqual(nodeConfigFields('WEBHOOK_TRIGGER').map(field => field.key), ['connectionId'])
  assert.deepEqual(nodeConfigFields('SCHEDULE_TRIGGER').map(field => field.key), ['cron', 'zoneId'])
  assert.deepEqual(nodeConfigFields('LLM').slice(-3).map(field => field.key), ['maxAttempts', 'retryDelayMillis', 'onError'])
})

test('配置字段区分必填、条件必填和具有运行默认值的可选项', () => {
  assert.equal(nodeConfigFieldRequirement('HTTP', 'url'), 'required')
  assert.equal(nodeConfigFieldRequirement('S3_OBJECT', 'key'), 'conditional')
  assert.equal(nodeConfigFieldRequirement('LLM', 'featureCode'), '')
  assert.equal(nodeConfigFields('HTTP').find(field => field.key === 'url').requirement, 'required')
})

test('发布提示覆盖固定必填、组合条件、操作条件和参数数量', () => {
  assert.deepEqual(missingNodeConfigRequirements('LLM', {}), [])
  assert.deepEqual(missingNodeConfigRequirements('HTTP', {}), ['url'])
  assert.deepEqual(missingNodeConfigRequirements('HTTP', { url: 'https://example.test' }), [])
  assert.deepEqual(missingNodeConfigRequirements('ITERATION', { collection: '{{input.items}}' }), ['bodyGraph'])
  assert.deepEqual(missingNodeConfigRequirements('DOCUMENT_EXTRACTOR', {}), ['contentOrBase64'])
  assert.deepEqual(missingNodeConfigRequirements('DOCUMENT_EXTRACTOR', { content: 'text' }), [])
  assert.deepEqual(missingNodeConfigRequirements('S3_OBJECT', { connectionId: 1, operation: 'LIST' }), [])
  assert.deepEqual(missingNodeConfigRequirements('S3_OBJECT', { connectionId: 1, operation: 'GET' }), ['key'])
  assert.deepEqual(missingNodeConfigRequirements('REDIS_COMMAND', { connectionId: 1, command: 'HSET', arguments: ['key', 'field'] }), ['arguments'])
  assert.deepEqual(missingNodeConfigRequirements('RABBITMQ_PUBLISH', { connectionId: 1, value: null }), ['rabbitDestination'])
  assert.deepEqual(missingNodeConfigRequirements('RABBITMQ_PUBLISH', { connectionId: 1, routingKey: 'orders', value: null }), [])
})

test('标准字段无需显式启用且保留清除配置和缺失必填提示', () => {
  assert.doesNotMatch(configEditorSource, /workflowConfig\.enableField|toggleConfigured|workflow-config-enable/)
  assert.match(configEditorSource, /:model-value="fieldValue\(field\)"/)
  assert.match(configEditorSource, /@update:model-value="setField\(field\.key, \$event\)"/)
  assert.match(configEditorSource, /v-if="hasField\(field\.key\)"[\s\S]*workflowConfig\.clearField/)
  assert.match(configEditorSource, /missingNodeConfigRequirements\(props\.nodeType, config\.value\)/)
})

test('默认值字段使用独立状态且必填缺失提示保持最高优先级', () => {
  assert.match(configEditorSource, /function hasDefault\(field\).*hasOwnProperty\.call\(field, 'defaultValue'\)/)
  assert.match(configEditorSource, /if \(hasField\(field\.key\)\) return 'workflowConfig\.configured'/)
  assert.match(configEditorSource, /hasDefault\(field\) \? 'workflowConfig\.defaultValue' : 'workflowConfig\.notConfigured'/)
  assert.match(configEditorSource, /v-if="fieldRequirementMissing\(field\)"[\s\S]*v-else[\s\S]*fieldStatusKey\(field\)/)
  assert.match(configEditorSource, /defaulted: !hasField\(field\.key\) && hasDefault\(field\)/)
})

test('零、false、null、空对象和空数组均保留为可展示默认值', () => {
  const examples = [
    nodeConfigFields('LLM').find(field => field.key === 'temperature').defaultValue,
    nodeConfigFields('LLM').find(field => field.key === 'enableThinking').defaultValue,
    nodeConfigFields('LLM').find(field => field.key === 'modelId').defaultValue,
    nodeConfigFields('HTTP').find(field => field.key === 'headers').defaultValue,
    nodeConfigFields('AGENT').find(field => field.key === 'tools').defaultValue
  ]
  assert.deepEqual(examples, [0, false, null, {}, []])
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
