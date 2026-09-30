import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import {
  CONNECTION_CATEGORIES,
  CONNECTION_CONFIG_GROUPS,
  CONNECTION_TYPES,
  cloneConnectionConfig,
  connectionCategoriesForType,
  connectionCategoryStyle,
  connectionConfigDefaults,
  connectionConfigFields,
  connectionTypesForCategory,
  connectionTypeStyle,
  createConnectionConfig,
  extraConnectionConfigKeys,
  hasAdvancedConnectionConfig,
  hasMeaningfulConnectionConfig,
  isConnectionConfigFieldRequired,
  isConnectionConfigValueEmpty,
  missingConnectionConfigFields,
  saveConnectionWithOptionalTest
} from '../src/utils/workflowConnectionConfig.js'

const viewSource = readFileSync(new URL('../src/views/DataSourcesView.vue', import.meta.url), 'utf8')
const iconSource = readFileSync(new URL('../src/components/DataSourceTypeIcon.vue', import.meta.url), 'utf8')

test('十三类连接均提供类型化标准字段和安全默认值', () => {
  assert.deepEqual(CONNECTION_TYPES, ['MYSQL', 'POSTGRESQL', 'REDIS', 'S3', 'OSS', 'KAFKA', 'RABBITMQ', 'WEBHOOK', 'TAVILY', 'QDRANT', 'MILVUS', 'ELASTICSEARCH', 'PLUGIN'])
  assert.deepEqual(connectionConfigFields('MYSQL').map(field => field.key), ['url', 'username', 'password', 'allowWrite'])
  assert.deepEqual(connectionConfigFields('REDIS').map(field => field.key), ['uri', 'keyPrefix', 'allowWrite'])
  assert.deepEqual(connectionConfigFields('S3').map(field => field.key), ['endpoint', 'region', 'bucket', 'accessKey', 'secretKey', 'keyPrefix', 'allowDelete', 'pathStyle'])
  assert.deepEqual(connectionConfigFields('OSS').map(field => field.key), ['endpoint', 'bucket', 'accessKey', 'secretKey', 'region', 'keyPrefix', 'allowDelete'])
  assert.deepEqual(connectionConfigFields('WEBHOOK').map(field => field.key), ['url', 'method', 'testMethod', 'headers'])
  assert.deepEqual(connectionConfigFields('TAVILY').map(field => field.key), ['apiKey'])
  assert.deepEqual(connectionConfigFields('QDRANT').map(field => field.key), ['url', 'apiKey'])
  assert.deepEqual(connectionConfigFields('MILVUS').map(field => field.key), ['url', 'database', 'token'])
  assert.deepEqual(connectionConfigFields('ELASTICSEARCH').map(field => field.key), ['url', 'product', 'username', 'password', 'apiKey'])
  assert.equal(connectionConfigDefaults('MYSQL').url, '')
  assert.equal(connectionConfigDefaults('REDIS').uri, '')
  assert.equal(connectionConfigDefaults('MYSQL').allowWrite, false)
  assert.equal(connectionConfigDefaults('REDIS').allowWrite, false)
  assert.equal(connectionConfigDefaults('S3').pathStyle, true)
  assert.equal(connectionConfigDefaults('OSS').allowDelete, false)
  assert.deepEqual(connectionConfigDefaults('WEBHOOK').headers, {})
  assert.deepEqual(connectionConfigFields('PLUGIN'), [])
})

test('连接字段按用途分组并提供必填、条件必填和示例元数据', () => {
  assert.deepEqual(CONNECTION_CONFIG_GROUPS, ['CONNECTION', 'AUTH', 'SCOPE', 'BEHAVIOR'])
  const mysqlUrl = connectionConfigFields('MYSQL').find(field => field.key === 'url')
  const redisWrite = connectionConfigFields('REDIS').find(field => field.key === 'allowWrite')
  const kafkaMechanism = connectionConfigFields('KAFKA').find(field => field.key === 'saslMechanism')

  assert.equal(mysqlUrl.required, true)
  assert.equal(mysqlUrl.wide, true)
  assert.match(mysqlUrl.placeholder, /^jdbc:mysql:/)
  assert.deepEqual({ group: redisWrite.group, defaultValue: redisWrite.defaultValue, risk: redisWrite.risk },
    { group: 'SCOPE', defaultValue: false, risk: true })
  assert.equal(isConnectionConfigFieldRequired(kafkaMechanism, { securityProtocol: 'PLAINTEXT' }), false)
  assert.equal(isConnectionConfigFieldRequired(kafkaMechanism, { securityProtocol: 'SASL_SSL' }), true)
})

test('标准参数校验覆盖空值、正常值和 Kafka SASL 条件分支', () => {
  assert.deepEqual(missingConnectionConfigFields('MYSQL', { url: '  ' }), ['url'])
  assert.deepEqual(missingConnectionConfigFields('MYSQL', { url: 'jdbc:mysql://db/app', allowWrite: false }), [])
  assert.deepEqual(missingConnectionConfigFields('S3', {
    endpoint: '', region: 'us-east-1', bucket: 'files', accessKey: 'key', secretKey: 'secret', pathStyle: false
  }), [])
  assert.deepEqual(missingConnectionConfigFields('KAFKA', {
    bootstrapServers: 'broker:9092', securityProtocol: 'PLAINTEXT'
  }), [])
  assert.deepEqual(missingConnectionConfigFields('KAFKA', {
    bootstrapServers: 'broker:9092', securityProtocol: 'SASL_SSL', saslMechanism: '', username: '', password: ''
  }), ['saslMechanism', 'username', 'password'])
  assert.deepEqual(missingConnectionConfigFields('KAFKA', {
    bootstrapServers: 'broker:9092', securityProtocol: 'SASL_SSL', saslMechanism: 'PLAIN', username: 'client', password: '******'
  }), [])
})

test('类型切换保护区分安全默认值、实际输入和历史敏感配置', () => {
  assert.equal(isConnectionConfigValueEmpty('  '), true)
  assert.equal(isConnectionConfigValueEmpty(false), false)
  assert.equal(hasMeaningfulConnectionConfig('MYSQL', createConnectionConfig('MYSQL')), false)
  assert.equal(hasMeaningfulConnectionConfig('MYSQL', createConnectionConfig('MYSQL', { url: 'jdbc:mysql://db/app' })), true)
  assert.equal(hasMeaningfulConnectionConfig('WEBHOOK', createConnectionConfig('WEBHOOK', { secret: '******' })), true)
  assert.equal(hasMeaningfulConnectionConfig('PLUGIN', { pluginComponentId: null, credentials: {} }), false)
  assert.equal(hasMeaningfulConnectionConfig('PLUGIN', { pluginComponentId: 8, credentials: {} }), true)
})

test('高级选项仅在偏离默认值或存在自定义参数时自动展开', () => {
  assert.equal(hasAdvancedConnectionConfig('MYSQL', createConnectionConfig('MYSQL')), false)
  assert.equal(hasAdvancedConnectionConfig('MYSQL', createConnectionConfig('MYSQL', { allowWrite: true })), true)
  assert.equal(hasAdvancedConnectionConfig('S3', createConnectionConfig('S3')), false)
  assert.equal(hasAdvancedConnectionConfig('S3', createConnectionConfig('S3', { pathStyle: false })), true)
  assert.equal(hasAdvancedConnectionConfig('MYSQL', createConnectionConfig('MYSQL', { vendorOption: 'enabled' })), true)
})

test('保存并检测按权限执行且检测失败不会丢失已保存结果', async () => {
  const calls = []
  const command = { code: 'MAIN_DB' }
  const success = await saveConnectionWithOptionalTest({
    connectionId: null,
    command,
    shouldTest: true,
    persist: async (id, payload) => { calls.push(['save', id, payload]); return { id: 42 } },
    testConnection: async id => { calls.push(['test', id]); return { connected: true } }
  })
  assert.deepEqual(calls, [['save', null, command], ['test', 42]])
  assert.deepEqual(success, { saved: { id: 42 }, tested: true, testResult: { connected: true }, testError: null })

  let testedWithoutPermission = false
  const savedOnly = await saveConnectionWithOptionalTest({
    connectionId: 42,
    command,
    shouldTest: false,
    persist: async () => ({ id: 42 }),
    testConnection: async () => { testedWithoutPermission = true }
  })
  assert.equal(testedWithoutPermission, false)
  assert.equal(savedOnly.tested, false)

  const testError = new Error('unreachable')
  const partialSuccess = await saveConnectionWithOptionalTest({
    connectionId: 42,
    command,
    shouldTest: true,
    persist: async () => ({ id: 42 }),
    testConnection: async () => { throw testError }
  })
  assert.equal(partialSuccess.saved.id, 42)
  assert.equal(partialSuccess.tested, true)
  assert.equal(partialSuccess.testError, testError)
})

test('持久化失败时不执行检测并向调用方传播错误', async () => {
  let tested = false
  const saveError = new Error('conflict')
  await assert.rejects(saveConnectionWithOptionalTest({
    connectionId: null,
    command: { code: 'DUPLICATE' },
    shouldTest: true,
    persist: async () => { throw saveError },
    testConnection: async () => { tested = true }
  }), saveError)
  assert.equal(tested, false)
})

test('七类连接完整覆盖全部类型并允许 PostgreSQL 双重归属', () => {
  assert.deepEqual(CONNECTION_CATEGORIES.map(category => category.key), [
    'DATABASE', 'VECTOR_DATABASE', 'CACHE', 'OBJECT_STORAGE', 'MESSAGE_QUEUE', 'WEBHOOK', 'OTHER'
  ])
  assert.deepEqual(connectionTypesForCategory('DATABASE'), ['MYSQL', 'POSTGRESQL'])
  assert.deepEqual(connectionTypesForCategory('VECTOR_DATABASE'), ['POSTGRESQL', 'QDRANT', 'MILVUS', 'ELASTICSEARCH'])
  assert.deepEqual(connectionTypesForCategory('OBJECT_STORAGE'), ['S3', 'OSS'])
  assert.deepEqual(connectionCategoriesForType('POSTGRESQL'), ['DATABASE', 'VECTOR_DATABASE'])
  assert.deepEqual(connectionCategoriesForType('OSS'), ['OBJECT_STORAGE'])
  assert.deepEqual(connectionCategoriesForType('WEBHOOK'), ['WEBHOOK'])
  assert.deepEqual(connectionTypesForCategory('UNKNOWN'), [])
  assert.deepEqual(connectionCategoriesForType('UNKNOWN'), [])

  const coveredTypes = new Set(CONNECTION_CATEGORIES.flatMap(category => category.types))
  assert.deepEqual([...coveredTypes].sort(), [...CONNECTION_TYPES].sort())
})

test('分类使用中性色且连接类型遵循外部常规品牌色', () => {
  const neutralCategoryStyle = { backgroundColor: '#f8fafc', borderColor: '#cbd5e1', color: '#475569' }
  const expectedTypeColors = {
    MYSQL: '#4479A1', POSTGRESQL: '#4169E1', REDIS: '#FF4438', S3: '#569A31', OSS: '#FF6A00',
    KAFKA: '#231F20', RABBITMQ: '#FF6600', QDRANT: '#DC244C', MILVUS: '#00B3FF',
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

test('分类图标使用独立语义且连接产品使用官方或明确的语义图形', () => {
  assert.match(iconSource, /const CATEGORY_ICONS = \{ DATABASE, VECTOR_DATABASE, CACHE, OBJECT_STORAGE, MESSAGE_QUEUE, WEBHOOK, OTHER \}/)
  assert.match(iconSource, /const TYPE_ICONS = \{ MYSQL, POSTGRESQL, REDIS, S3, OSS, KAFKA, RABBITMQ,/)
  assert.match(iconSource, /if \(category\) return CATEGORY_ICONS\[category\] \|\| OTHER/)
  assert.match(iconSource, /M117\.688 98\.242c-6\.973-.191/)
  assert.match(iconSource, /M23\.5594 14\.7228a\.5269\.5269/)
  assert.match(iconSource, /M201\.816 230\.216c-16\.186 0-30\.697 7\.171-40\.634 18\.461/)
  assert.match(iconSource, /M86\.6 0 0 50 0 150 86\.6 200 119\.08 181\.25/)
  assert.match(iconSource, /M21\.1411 22\.5376C25\.208 22\.5376 28\.5048 19\.1691/)
  assert.match(iconSource, /M38\.8088 0C44\.4762 0 47\.3101 9\.1895e-5 49\.4748 1\.10306/)
  assert.match(iconSource, /const MYSQL = \[\s*\{ fill: 'currentColor', stroke: 'none'/)
  assert.match(iconSource, /const POSTGRESQL = \[\s*\{ fill: 'currentColor', stroke: 'none'/)
  assert.match(iconSource, /const ELASTICSEARCH = \[\s*\{ fill: '#F4BD19', stroke: 'none', transform: 'scale\(\.09375\)'/)
  assert.match(iconSource, /\{ fill: '#3CBEB1', stroke: 'none', transform: 'scale\(\.09375\)'/)
  assert.doesNotMatch(iconSource, /const PIPELINE|const GLOBE|const HEX|const TRIANGLE/)
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

test('旧 Webhook 签名密钥继续透传但不再作为有效标准或自定义参数展示', () => {
  const config = createConnectionConfig('WEBHOOK', { url: 'https://hooks.example.com', secret: '******', custom: true })

  assert.equal(config.secret, '******')
  assert.deepEqual(extraConnectionConfigKeys(config, 'WEBHOOK'), ['custom'])
})

test('数据源页面使用两步向导、配置助手和高级选项形成明显的新填写流程', () => {
  assert.match(viewSource, /class="connection-wizard-steps"/)
  assert.match(viewSource, /editorStep === 'TYPE'/)
  assert.match(viewSource, /class="connection-type-stage"/)
  assert.match(viewSource, /class="connection-category-tabs"/)
  assert.match(viewSource, /class="connection-type-card-grid"/)
  assert.match(viewSource, /class="connection-type-card"/)
  assert.match(viewSource, /@click="selectCategory\(category\.key\)"/)
  assert.match(viewSource, /@click="selectConnectionType\(type\)"/)
  assert.match(viewSource, /@click="applyTypeSelection"/)
  assert.match(viewSource, /hasMeaningfulConnectionConfig\(form\.connectionType, form\.config\)/)
  assert.match(viewSource, /changeTypeWarning/)
  assert.match(viewSource, /class="connection-config-layout"/)
  assert.match(viewSource, /class="connection-config-assistant"/)
  assert.match(viewSource, /class="connection-assistant-progress"/)
  assert.match(viewSource, /class="connection-assistant-checklist"/)
  assert.match(viewSource, /class="connection-advanced-toggle"/)
  assert.match(viewSource, /advancedOpen\.value \? groups\.filter/)
  assert.match(viewSource, /class="connection-config-grid"/)
  assert.match(viewSource, /class="connection-config-field"/)
  assert.match(viewSource, /class="connection-field-requirement"/)
  assert.match(viewSource, /class="connection-inline-error"/)
  assert.match(viewSource, /class="connection-boolean-control"/)
  assert.match(viewSource, /connectionTypeGuide\(form\.connectionType\)/)
  assert.match(viewSource, /fieldPlaceholder\(field\)/)
  assert.match(viewSource, /missingConnectionConfigFields\(form\.connectionType, form\.config\)/)
  assert.match(viewSource, /class="connection-custom-card"/)
  assert.match(viewSource, /WorkflowConfigValueEditor/)
  assert.match(viewSource, /class="connection-key-value"/)
  assert.match(viewSource, /config: cloneConnectionConfig\(form\.config\)/)
  assert.match(viewSource, /saveConnectionWithOptionalTest/)
  assert.match(viewSource, /savedTestFailedTitle/)
  assert.match(viewSource, /testConnection: async id => \(await http\.post/)
  assert.doesNotMatch(viewSource, /configText|JSON\.parse|type="textarea"/)
  assert.doesNotMatch(zhCN.workflowConnections.config, /JSON/i)
  assert.doesNotMatch(enUS.workflowConnections.config, /JSON/i)
  assert.match(viewSource, /plugin-component-options/)
  assert.match(viewSource, /pluginCredentialFields/)
  assert.match(viewSource, /field\.required/)
  assert.match(viewSource, /configFields\.value\.filter\(field => field\.required/)
  assert.match(viewSource, /oauth\/authorize/)
  assert.match(viewSource, /plugin-oauth\/callback/)
  assert.match(viewSource, /availableConnectionTypes/)
  assert.doesNotMatch(viewSource, /v-model="form\.connectionCategory"/)
  assert.doesNotMatch(viewSource, /@change="selectCategory"/)
  assert.match(viewSource, /preferredCategory\(connectionType\)/)
  assert.match(viewSource, /connectionCategory: '', connectionType: '', config: \{\}/)
  assert.match(viewSource, /categoryStyle/)
  assert.match(viewSource, /typeStyle/)
  assert.doesNotMatch(viewSource, /class="connection-picker"/)
  assert.doesNotMatch(viewSource, /class="connection-editor-layout"/)
  assert.match(viewSource, /@media \(max-width: 1040px\)[\s\S]*\.connection-config-layout \{ grid-template-columns: 1fr; \}/)
  assert.match(viewSource, /@media \(max-width: 720px\)[\s\S]*\.connection-config-grid[^{]*\{ grid-template-columns: 1fr; \}/)
})

test('中英文资源覆盖向导、配置助手、保存检测和校验提示', () => {
  const keys = ['configHelp', 'customTitle', 'customHelp', 'customKey', 'customValue', 'addCustom', 'invalidCustomKey',
    'duplicateCustomKey', 'category', 'connectionType', 'selectCategory', 'selectConnectionType', 'codePlaceholder',
    'codeHelp', 'codeInvalid', 'configRequired', 'requiredField', 'optionalField', 'conditionalRequired', 'sensitiveOption',
    'wizardProgress', 'chooseStep', 'configureStep', 'chooseTitle', 'parameterSummary', 'identityTitle', 'configAssistant',
    'requiredProgress', 'fieldGuide', 'formatExample', 'parameterChecklist', 'advancedTitle', 'changeTypeWarning',
    'continueConfigure', 'saveAndTest', 'savedAndTested', 'savedTestFailedTitle', 'savedTestFailedDescription']
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
    assert.ok(zhCN.workflowConnections.typeGuides[type], `zh-CN guide ${type}`)
    assert.ok(enUS.workflowConnections.typeGuides[type], `en-US guide ${type}`)
  }
  for (const group of CONNECTION_CONFIG_GROUPS) {
    assert.ok(zhCN.workflowConnections.configGroups[group]?.label, `zh-CN group ${group}`)
    assert.ok(enUS.workflowConnections.configGroups[group]?.label, `en-US group ${group}`)
  }
})
