import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import {
  CONNECTION_TYPES,
  cloneConnectionConfig,
  connectionConfigDefaults,
  connectionConfigFields,
  createConnectionConfig,
  extraConnectionConfigKeys
} from '../src/utils/workflowConnectionConfig.js'

const viewSource = readFileSync(new URL('../src/views/WorkflowConnectionsView.vue', import.meta.url), 'utf8')

test('十二类连接均提供类型化标准字段和安全默认值', () => {
  assert.deepEqual(CONNECTION_TYPES, ['MYSQL', 'POSTGRESQL', 'REDIS', 'S3', 'KAFKA', 'RABBITMQ', 'WEBHOOK', 'TAVILY', 'QDRANT', 'MILVUS', 'ELASTICSEARCH', 'PLUGIN'])
  assert.deepEqual(connectionConfigFields('MYSQL').map(field => field.key), ['url', 'username', 'password', 'allowWrite'])
  assert.deepEqual(connectionConfigFields('WEBHOOK').map(field => field.key), ['url', 'method', 'testMethod', 'headers', 'secret'])
  assert.deepEqual(connectionConfigFields('TAVILY').map(field => field.key), ['apiKey'])
  assert.deepEqual(connectionConfigFields('QDRANT').map(field => field.key), ['url', 'apiKey'])
  assert.deepEqual(connectionConfigFields('MILVUS').map(field => field.key), ['url', 'token', 'database'])
  assert.deepEqual(connectionConfigFields('ELASTICSEARCH').map(field => field.key), ['url', 'username', 'password', 'apiKey', 'product'])
  assert.equal(connectionConfigDefaults('MYSQL').allowWrite, false)
  assert.equal(connectionConfigDefaults('S3').pathStyle, true)
  assert.deepEqual(connectionConfigDefaults('WEBHOOK').headers, {})
  assert.deepEqual(connectionConfigFields('PLUGIN'), [])
})

test('编辑配置时保留脱敏密钥、嵌套值和未知自定义字段', () => {
  const source = { url: 'jdbc:mysql://db/app', password: '******', custom: { retries: [1, 2] } }
  const config = createConnectionConfig('MYSQL', source)
  config.custom.retries.push(3)

  assert.equal(config.password, '******')
  assert.equal(config.allowWrite, false)
  assert.deepEqual(source.custom.retries, [1, 2])
  assert.deepEqual(extraConnectionConfigKeys(config, 'MYSQL'), ['custom'])
  assert.notEqual(cloneConnectionConfig(config), config)
})

test('连接页面只使用卡片及键值输入并由结构化配置直接保存', () => {
  assert.match(viewSource, /class="connection-config-card"/)
  assert.match(viewSource, /class="connection-custom-card"/)
  assert.match(viewSource, /WorkflowConfigValueEditor/)
  assert.match(viewSource, /class="connection-key-value"/)
  assert.match(viewSource, /config: cloneConnectionConfig\(form\.config\)/)
  assert.doesNotMatch(viewSource, /configText|JSON\.parse|type="textarea"/)
  assert.doesNotMatch(zhCN.workflowConnections.config, /JSON/i)
  assert.doesNotMatch(enUS.workflowConnections.config, /JSON/i)
  assert.match(viewSource, /plugin-component-options/)
  assert.match(viewSource, /pluginCredentialFields/)
  assert.match(viewSource, /field\.required/)
  assert.match(viewSource, /configFields\.value\.some\(field => field\.required/)
  assert.match(viewSource, /oauth\/authorize/)
  assert.match(viewSource, /plugin-oauth\/callback/)
})

test('中英文资源覆盖标准卡片、自定义卡片和校验提示', () => {
  const keys = ['configHelp', 'customTitle', 'customHelp', 'customKey', 'customValue', 'addCustom', 'invalidCustomKey', 'duplicateCustomKey']
  for (const key of keys) {
    assert.ok(zhCN.workflowConnections[key], `zh-CN ${key}`)
    assert.ok(enUS.workflowConnections[key], `en-US ${key}`)
  }
  for (const field of connectionConfigFields('S3')) {
    assert.ok(zhCN.workflowConnections.fields[field.key], `zh-CN ${field.key}`)
    assert.ok(enUS.workflowConnections.fields[field.key], `en-US ${field.key}`)
  }
})
