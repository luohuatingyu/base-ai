import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { cloneConfig, configValueType, createConfigValue, extraConfigKeys, isSafeConfigKey, nodeConfigFields, WORKFLOW_NODE_TYPES } from '../src/utils/workflowNodeConfig.js'

const nodeManagementSource = readFileSync(new URL('../src/views/WorkflowNodesView.vue', import.meta.url), 'utf8')
const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')

test('节点管理按功能分类展示卡片并保留模板保护权限', () => {
  assert.match(nodeManagementSource, /v-for="group in groups"/)
  assert.match(nodeManagementSource, /class="node-template-card"/)
  assert.match(nodeManagementSource, /groupWorkflowTemplates\(rows\.value, true\)/)
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
