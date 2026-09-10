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

const viewSource = readFileSync(new URL('../src/views/DataSourcesView.vue', import.meta.url), 'utf8')
const iconSource = readFileSync(new URL('../src/components/DataSourceTypeIcon.vue', import.meta.url), 'utf8')

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

test('分类使用中性色且连接类型遵循外部常规品牌色', () => {
  const neutralCategoryStyle = { backgroundColor: '#f8fafc', borderColor: '#cbd5e1', color: '#475569' }
  const expectedTypeColors = {
    MYSQL: '#4479A1', POSTGRESQL: '#4169E1', REDIS: '#FF4438', S3: '#569A31',
    KAFKA: '#231F20', RABBITMQ: '#FF6600', QDRANT: '#DC244C', MILVUS: '#00A1EA',
    ELASTICSEARCH: '#005571', WEBHOOK: '#475569', TAVILY: '#475569', PLUGIN: '#475569'
  }

  assert.deepEqual(connectionCategoryStyle('DATABASE'), neutralCategoryStyle)
  assert.deepEqual(connectionCategoryStyle('VECTOR_DATABASE'), neutralCategoryStyle)
  assert.deepEqual(connectionCategoryStyle('UNKNOWN'), neutralCategoryStyle)
  Object.entries(expectedTypeColors).forEach(([type, color]) => {
    assert.deepEqual(connectionTypeStyle(type), {
      backgroundColor: '#ffffff', borderColor: '#dbe3ee', color
    })
  })
  assert.deepEqual(connectionTypeStyle('POSTGRESQL', 'VECTOR_DATABASE'), connectionTypeStyle('POSTGRESQL'))
  assert.deepEqual(connectionTypeStyle('UNKNOWN'), {
    backgroundColor: '#ffffff', borderColor: '#dbe3ee', color: '#475569'
  })
})

test('分类图标使用独立语义且数据库产品拥有各自图形', () => {
  assert.match(iconSource, /const CATEGORY_ICONS = \{ DATABASE, VECTOR_DATABASE, CACHE, OBJECT_STORAGE, MESSAGE_QUEUE, WEBHOOK, OTHER \}/)
  assert.match(iconSource, /const TYPE_ICONS = \{ MYSQL, POSTGRESQL,/)
  assert.match(iconSource, /if \(category\) return CATEGORY_ICONS\[category\] \|\| OTHER/)
  assert.match(iconSource, /M117\.688 98\.242c-6\.973-.191/)
  assert.match(iconSource, /M23\.5594 14\.7228a\.5269\.5269/)
  assert.match(iconSource, /const MYSQL = \[\s*\{ fill: 'currentColor', stroke: 'none'/)
  assert.match(iconSource, /const POSTGRESQL = \[\s*\{ fill: 'currentColor', stroke: 'none'/)
  assert.match(iconSource, /const ELASTICSEARCH = \[\s*\{ fill: 'currentColor', stroke: 'none', transform: 'scale\(\.75\)', d: 'M28\.4 5\.8/)
  assert.match(iconSource, /:fill="path\.fill"/)
  assert.match(viewSource, /<DataSourceTypeIcon :category="group\.key" \/>/)
  assert.match(viewSource, /<DataSourceTypeIcon :category="category\.key" \/>/)
  assert.doesNotMatch(viewSource, /typeStyle\([^)]*,/)
  assert.doesNotMatch(viewSource, /categoryIconType/)
  assert.doesNotMatch(iconSource, /MYSQL: DATABASE|POSTGRESQL: DATABASE/)
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

test('数据源页面使用左侧分类类型导航和紧凑参数表单并由结构化配置直接保存', () => {
  assert.match(viewSource, /class="connection-editor-layout"/)
  assert.match(viewSource, /class="connection-picker"/)
  assert.match(viewSource, /class="connection-category-nav"/)
  assert.match(viewSource, /class="connection-category-option"/)
  assert.match(viewSource, /class="connection-type-option"/)
  assert.match(viewSource, /@click="selectCategory\(category\.key\)"/)
  assert.match(viewSource, /@click="selectConnectionType\(type\)"/)
  assert.match(viewSource, /class="connection-config-surface"/)
  assert.match(viewSource, /class="connection-config-field"/)
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
  assert.match(viewSource, /availableConnectionTypes/)
  assert.doesNotMatch(viewSource, /v-model="form\.connectionCategory"/)
  assert.doesNotMatch(viewSource, /@change="selectCategory"/)
  assert.match(viewSource, /preferredCategory\(connectionType\)/)
  assert.match(viewSource, /connectionCategory: '', connectionType: '', config: \{\}/)
  assert.match(viewSource, /v-if="form\.connectionType" class="connection-form-section connection-config-section"/)
  assert.match(viewSource, /categoryStyle/)
  assert.match(viewSource, /typeStyle/)
  assert.match(viewSource, /if \(form\.connectionCategory === category\) return/)
  assert.match(viewSource, /if \(form\.connectionType === type\) return/)
  assert.match(viewSource, /@media \(max-width: 900px\)[\s\S]*\.connection-editor-layout \{ grid-template-columns: 1fr; \}/)
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
