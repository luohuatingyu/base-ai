import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import {
  CONNECTION_CATEGORIES,
  CONNECTION_TYPES,
  cloneConnectionConfig,
  connectionCategoriesForType,
  connectionCategoryStyle,
  connectionConfigDefaults,
  connectionConfigFields,
  connectionTypesForCategory,
  connectionTypeStyle,
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

test('七类连接完整覆盖全部类型并允许 PostgreSQL 双重归属', () => {
  assert.deepEqual(CONNECTION_CATEGORIES.map(category => category.key), [
    'DATABASE', 'VECTOR_DATABASE', 'CACHE', 'OBJECT_STORAGE', 'MESSAGE_QUEUE', 'WEBHOOK', 'OTHER'
  ])
  assert.deepEqual(connectionTypesForCategory('DATABASE'), ['MYSQL', 'POSTGRESQL'])
  assert.deepEqual(connectionTypesForCategory('VECTOR_DATABASE'), ['POSTGRESQL', 'QDRANT', 'MILVUS', 'ELASTICSEARCH'])
  assert.deepEqual(connectionCategoriesForType('POSTGRESQL'), ['DATABASE', 'VECTOR_DATABASE'])
  assert.deepEqual(connectionCategoriesForType('WEBHOOK'), ['WEBHOOK'])
  assert.deepEqual(connectionTypesForCategory('UNKNOWN'), [])
  assert.deepEqual(connectionCategoriesForType('UNKNOWN'), [])

  const coveredTypes = new Set(CONNECTION_CATEGORIES.flatMap(category => category.types))
  assert.deepEqual([...coveredTypes].sort(), [...CONNECTION_TYPES].sort())
})

test('不同分类使用不同色相且同分类连接类型使用递进深度', () => {
  const database = connectionCategoryStyle('DATABASE')
  const vectorDatabase = connectionCategoryStyle('VECTOR_DATABASE')
  const mysql = connectionTypeStyle('MYSQL', 'DATABASE')
  const postgresql = connectionTypeStyle('POSTGRESQL', 'DATABASE')

  assert.notEqual(database.color, vectorDatabase.color)
  assert.equal(mysql.color, postgresql.color)
  assert.notEqual(mysql.backgroundColor, postgresql.backgroundColor)
  assert.equal(connectionTypeStyle('POSTGRESQL', 'VECTOR_DATABASE').color, vectorDatabase.color)
  assert.deepEqual(connectionCategoryStyle('UNKNOWN'), connectionCategoryStyle('OTHER'))
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
  assert.match(viewSource, /v-model="form\.connectionCategory"/)
  assert.match(viewSource, /availableConnectionTypes/)
  assert.match(viewSource, /@change="selectCategory"/)
  assert.match(viewSource, /preferredCategory\(connectionType\)/)
  assert.match(viewSource, /connectionCategory: '', connectionType: '', config: \{\}/)
  assert.match(viewSource, /v-if="form\.connectionType" class="connection-config-section"/)
  assert.match(viewSource, /categoryStyle/)
  assert.match(viewSource, /typeStyle/)
})

test('中英文资源覆盖标准卡片、自定义卡片和校验提示', () => {
  const keys = ['configHelp', 'customTitle', 'customHelp', 'customKey', 'customValue', 'addCustom', 'invalidCustomKey',
    'duplicateCustomKey', 'category', 'connectionType', 'selectCategory', 'selectConnectionType']
  for (const key of keys) {
    assert.ok(zhCN.workflowConnections[key], `zh-CN ${key}`)
    assert.ok(enUS.workflowConnections[key], `en-US ${key}`)
  }
  for (const field of connectionConfigFields('S3')) {
    assert.ok(zhCN.workflowConnections.fields[field.key], `zh-CN ${field.key}`)
    assert.ok(enUS.workflowConnections.fields[field.key], `en-US ${field.key}`)
  }
  for (const category of CONNECTION_CATEGORIES) {
    assert.ok(zhCN.workflowConnections.categories[category.key], `zh-CN ${category.key}`)
    assert.ok(enUS.workflowConnections.categories[category.key], `en-US ${category.key}`)
  }
  for (const type of CONNECTION_TYPES) {
    assert.ok(zhCN.workflowConnections.types[type], `zh-CN ${type}`)
    assert.ok(enUS.workflowConnections.types[type], `en-US ${type}`)
  }
})
