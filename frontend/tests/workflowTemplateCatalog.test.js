import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import { WORKFLOW_NODE_TYPES } from '../src/utils/workflowNodeConfig.js'
import {
  defaultTemplateCategory, filterWorkflowTemplates, groupWorkflowTemplates, localizedTemplateText,
  marketplaceItemDescription, marketplaceNodeTypeLabel, normalizeTemplateMetadata, systemTemplateTranslationKey,
  workflowTemplateCategoryStyle,
  WORKFLOW_TEMPLATE_CATEGORIES, WORKFLOW_TEMPLATE_SOURCES
} from '../src/utils/workflowTemplateCatalog.js'

const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')
const nodeManagementSource = readFileSync(new URL('../src/views/WorkflowNodesView.vue', import.meta.url), 'utf8')

/** 创建与 vue-i18n 文案查询行为一致的轻量测试替身。 */
function localeAccessors(messages) {
  const find = key => key.split('.').reduce((value, part) => value?.[part], messages)
  return { translate: key => find(key), hasTranslation: key => find(key) !== undefined }
}

/** 计算十六进制前景色与背景色的 WCAG 对比度。 */
function contrastRatio(foreground, background) {
  const luminance = hex => {
    const channels = hex.slice(1).match(/../g).map(value => parseInt(value, 16) / 255)
      .map(value => value <= 0.03928 ? value / 12.92 : ((value + 0.055) / 1.055) ** 2.4)
    return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
  }
  const values = [luminance(foreground), luminance(background)].sort((left, right) => right - left)
  return (values[0] + 0.05) / (values[1] + 0.05)
}

test('全部原生节点属于受控功能分类', () => {
  assert.deepEqual(WORKFLOW_TEMPLATE_SOURCES, ['SYSTEM', 'N8N', 'DIFY'])
  for (const nodeType of WORKFLOW_NODE_TYPES) {
    assert.ok(WORKFLOW_TEMPLATE_CATEGORIES.includes(defaultTemplateCategory(nodeType)), nodeType)
  }
})

test('全部功能类型使用互不相同的统一图标背景色并安全回退', () => {
  const styles = WORKFLOW_TEMPLATE_CATEGORIES.map(category => workflowTemplateCategoryStyle(category))
  assert.equal(new Set(styles.map(style => style.backgroundColor)).size, WORKFLOW_TEMPLATE_CATEGORIES.length)
  assert.ok(styles.every(style => style.color && style.backgroundColor))
  assert.ok(styles.every(style => contrastRatio(style.color, style.backgroundColor) >= 4.5))
  assert.deepEqual(workflowTemplateCategoryStyle('ai'), workflowTemplateCategoryStyle('AI'))
  assert.deepEqual(workflowTemplateCategoryStyle('', 'LLM'), workflowTemplateCategoryStyle('AI'))
  assert.deepEqual(workflowTemplateCategoryStyle('UNKNOWN', 'UNKNOWN'), workflowTemplateCategoryStyle('BASIC'))
})

test('迁移前模板兼容系统来源并按节点类型推导分类', () => {
  assert.deepEqual(
    normalizeTemplateMetadata({ id: 1, nodeType: 'LLM' }),
    { id: 1, nodeType: 'LLM', source: 'SYSTEM', functionalCategory: 'AI' }
  )
  assert.equal(normalizeTemplateMetadata({ nodeType: 'HTTP', source: 'dify', functionalCategory: 'trigger' }).source, 'DIFY')
  assert.equal(normalizeTemplateMetadata({ nodeType: 'HTTP', source: 'unknown', functionalCategory: 'unknown' }).functionalCategory, 'NETWORK_API')
})

test('全部系统节点具有完整的中英文名称和说明', () => {
  for (const nodeType of WORKFLOW_NODE_TYPES) {
    for (const messages of [zhCN, enUS]) {
      assert.ok(messages.workflowCatalog.templates[nodeType].name, `${nodeType} name`)
      assert.ok(messages.workflowCatalog.templates[nodeType].description, `${nodeType} description`)
    }
  }
})

test('系统节点按当前语言展示且自定义或缺失词条安全回退原文', () => {
  const systemStart = { nodeType: 'START', systemTemplate: true, name: '数据库名称', description: '数据库说明' }
  const zh = localeAccessors(zhCN)
  const en = localeAccessors(enUS)
  assert.equal(systemTemplateTranslationKey(systemStart, 'name'), 'workflowCatalog.templates.START.name')
  assert.equal(localizedTemplateText(systemStart, 'name', zh.translate, zh.hasTranslation), '开始')
  assert.equal(localizedTemplateText(systemStart, 'name', en.translate, en.hasTranslation), 'Start')
  assert.equal(localizedTemplateText(systemStart, 'description', en.translate, en.hasTranslation), 'Define workflow inputs and start execution')
  assert.equal(localizedTemplateText({ ...systemStart, systemTemplate: false }, 'name', en.translate, en.hasTranslation), '数据库名称')
  assert.equal(localizedTemplateText({ ...systemStart, nodeType: 'UNKNOWN' }, 'name', en.translate, en.hasTranslation), '数据库名称')
  assert.equal(systemTemplateTranslationKey(systemStart, 'code'), '')
})

test('市场说明优先使用官方内容并为 n8n 缺失说明提供可信本地回退', () => {
  const zh = localeAccessors(zhCN)
  const official = { description: 'Official description', compatible: true, targetNodeType: 'REDIS_COMMAND' }
  const compatibleN8n = { description: '', compatible: true, targetNodeType: 'REDIS_COMMAND' }
  const unsupportedN8n = { description: '', compatible: false, targetNodeType: '' }

  assert.equal(marketplaceItemDescription(official, 'N8N', zh.translate, zh.hasTranslation), 'Official description')
  assert.equal(marketplaceItemDescription(compatibleN8n, 'N8N', zh.translate, zh.hasTranslation),
    zhCN.workflowCatalog.templates.REDIS_COMMAND.description)
  assert.equal(marketplaceItemDescription(unsupportedN8n, 'N8N', zh.translate, zh.hasTranslation),
    zhCN.workflowNodes.n8nDescriptionUnavailable)
  assert.equal(marketplaceNodeTypeLabel(compatibleN8n, zh.translate, zh.hasTranslation),
    zhCN.workflowCatalog.templates.REDIS_COMMAND.name)
})

test('功能分组保持固定顺序并默认隐藏停用节点', () => {
  const templates = [
    { id: 1, nodeType: 'HTTP', enabled: true, source: 'N8N' },
    { id: 2, nodeType: 'LLM', enabled: true, source: 'DIFY' },
    { id: 3, nodeType: 'START', enabled: false, source: 'SYSTEM', systemTemplate: true }
  ]
  assert.deepEqual(groupWorkflowTemplates(templates).map(group => group.category), ['AI', 'NETWORK_API'])
  assert.deepEqual(groupWorkflowTemplates(templates, true).map(group => group.category), ['BASIC', 'AI', 'NETWORK_API'])
})

test('必选来源和功能类型共同过滤节点并可按需保留停用节点', () => {
  const templates = [
    { id: 1, nodeType: 'LLM', enabled: true, source: 'SYSTEM', functionalCategory: 'AI' },
    { id: 2, nodeType: 'LLM', enabled: false, source: 'SYSTEM', functionalCategory: 'AI' },
    { id: 3, nodeType: 'LLM', enabled: true, source: 'DIFY', functionalCategory: 'AI' },
    { id: 4, nodeType: 'HTTP', enabled: true, source: 'SYSTEM', functionalCategory: 'NETWORK_API' }
  ]
  assert.deepEqual(filterWorkflowTemplates(templates, 'SYSTEM', 'AI').map(template => template.id), [1])
  assert.deepEqual(filterWorkflowTemplates(templates, 'SYSTEM', 'AI', true).map(template => template.id), [1, 2])
  assert.deepEqual(filterWorkflowTemplates(templates, 'N8N', 'AI'), [])
  assert.deepEqual(filterWorkflowTemplates(templates, '', 'AI'), [])
  assert.deepEqual(filterWorkflowTemplates(templates, 'SYSTEM', ''), [])
})

test('画布使用右键分类菜单并在点击坐标添加节点', () => {
  assert.match(graphEditorSource, /@pane-context-menu="openTemplateMenu"/)
  assert.match(graphEditorSource, /screenToFlowCoordinate/)
  assert.match(graphEditorSource, /addTemplateFromMenu/)
  assert.match(graphEditorSource, /workflow-template-source/)
  assert.match(graphEditorSource, /templateText\(template, 'name'\)/)
  assert.match(graphEditorSource, /localizedTemplateText\(template, field, t, te\)/)
  assert.match(graphEditorSource, /data:\s*\{\s*label:\s*templateText\(template, 'name'\)/)
  assert.doesNotMatch(graphEditorSource, /<strong>\{\{\s*template\.name\s*\}\}<\/strong>/)
  assert.doesNotMatch(graphEditorSource, /workflow-palette|startDrag|application\/workflow-template/)
})

test('节点管理使用必选来源和功能类型级联标签并校验表单必填项', () => {
  assert.match(nodeManagementSource, /form\.source/)
  assert.match(nodeManagementSource, /form\.functionalCategory/)
  assert.match(nodeManagementSource, /v-model="form\.source"/)
  assert.match(nodeManagementSource, /v-model="selectedSource"/)
  assert.match(nodeManagementSource, /v-model="selectedCategory"/)
  assert.match(nodeManagementSource, /filterWorkflowTemplates\(rows\.value, selectedSource\.value, selectedCategory\.value, true\)/)
  assert.match(nodeManagementSource, /:label="t\('workflowNodes\.source'\)" required/)
  assert.match(nodeManagementSource, /:label="t\('workflowNodes\.category'\)" required/)
  assert.match(nodeManagementSource, /!sources\.includes\(form\.source\)/)
  assert.match(nodeManagementSource, /!categories\.includes\(form\.functionalCategory\)/)
  assert.match(nodeManagementSource, /WORKFLOW_TEMPLATE_SOURCES/)
  assert.match(nodeManagementSource, /WORKFLOW_TEMPLATE_CATEGORIES/)
})

test('节点来源切换后系统使用新增入口且外部来源使用官方市场导入', () => {
  assert.match(nodeManagementSource, /selectedSource === 'SYSTEM'[^>]*workflow:node:create/)
  assert.match(nodeManagementSource, /selectedSource !== 'SYSTEM'[^>]*workflow:node:import/)
  assert.match(nodeManagementSource, /openMarketplace/)
  assert.match(nodeManagementSource, /\/workflow\/node-marketplaces\/\$\{selectedSource\.value\}\/nodes/)
  assert.match(nodeManagementSource, /\/workflow\/node-marketplaces\/\$\{selectedSource\.value\}\/imports/)
})

test('市场目录全量可浏览但只有原生兼容节点可以选中导入', () => {
  assert.match(nodeManagementSource, /v-model="compatibleOnly"/)
  assert.match(nodeManagementSource, /:disabled="!item\.compatible"/)
  assert.match(nodeManagementSource, /item\.actions\?\.length/)
  assert.match(nodeManagementSource, /:disabled="!action\.compatible"/)
  assert.match(nodeManagementSource, /compatibilityLevel === 'NATIVE_SUBSET'/)
  assert.match(nodeManagementSource, /item\.incompatibilityReason/)
  assert.match(nodeManagementSource, /externalIds:\s*\[\.\.\.selectedMarketplaceIds\.value\]/)
  assert.match(nodeManagementSource, /marketplaceHint/)
})

test('市场卡片约束长标题描述和能力项且不覆盖相邻卡片', () => {
  assert.match(nodeManagementSource, /\.marketplace-card\s*\{[^}]*min-width:\s*0;[^}]*overflow:\s*hidden;/)
  assert.match(nodeManagementSource, /\.marketplace-card-head\s*\{[^}]*min-width:\s*0;/)
  assert.match(nodeManagementSource, /\.marketplace-card-head[^}]*\.el-checkbox__label[^}]*overflow-wrap:\s*anywhere;/)
  assert.match(nodeManagementSource, /\.marketplace-card p\s*\{[^}]*overflow-wrap:\s*anywhere;/)
  assert.match(nodeManagementSource, /\.marketplace-actions[^}]*\.el-checkbox__label[^}]*min-width:\s*0;[^}]*white-space:\s*normal;/)
})

test('Dify 市场使用两列插件卡片并把可导入能力展示为独立分区', () => {
  assert.match(nodeManagementSource, /'marketplace-grid--dify':\s*selectedSource === 'DIFY'/)
  assert.match(nodeManagementSource, /class="marketplace-card-description"/)
  assert.match(nodeManagementSource, /class="marketplace-actions-head"/)
  assert.match(nodeManagementSource, /workflowNodes\.marketplaceCapabilities/)
  assert.match(nodeManagementSource, /\.marketplace-grid--dify\s*\{[^}]*grid-template-columns:\s*repeat\(2,\s*minmax\(0,\s*1fr\)\)/)
  assert.match(nodeManagementSource, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.marketplace-grid--dify\s*\{[^}]*grid-template-columns:\s*1fr/)
})

test('市场导入模板锁定外部身份但继续使用原生配置编辑器', () => {
  assert.match(nodeManagementSource, /form\.systemTemplate \|\| form\.importedTemplate/)
  assert.match(nodeManagementSource, /:disabled="form\.importedTemplate \|\| !form\.id"/)
  assert.match(nodeManagementSource, /WorkflowNodeConfigEditor/)
  assert.match(nodeManagementSource, /importedTemplate:\s*false/)
})

test('节点管理卡片仅展示当前语言的功能文案并隐藏技术编码和节点类型', () => {
  assert.match(nodeManagementSource, /templateIcon\(row\)/)
  assert.match(nodeManagementSource, /templateText\(row, 'name'\)/)
  assert.match(nodeManagementSource, /templateText\(row, 'description'\)/)
  assert.doesNotMatch(nodeManagementSource, /\{\{\s*row\.code\s*\}\}/)
  assert.doesNotMatch(nodeManagementSource, /\{\{\s*row\.nodeType\s*\}\}/)
  assert.match(nodeManagementSource, /:style="workflowTemplateCategoryStyle\(row\.functionalCategory, row\.nodeType\)"/)
  assert.doesNotMatch(nodeManagementSource, /node-template-card--agent \.node-template-icon/)
})

test('画布菜单复用功能类型图标配色并把分类保存到新节点', () => {
  assert.match(graphEditorSource, /:style="workflowTemplateCategoryStyle\(template\.functionalCategory, template\.nodeType\)"/)
  assert.match(graphEditorSource, /functionalCategory:\s*template\.functionalCategory/)
})

test('节点来源位于顶部且功能类型在节点列表左侧展示', () => {
  const sourceFilterIndex = nodeManagementSource.indexOf('class="node-template-source-filter"')
  const layoutIndex = nodeManagementSource.indexOf('class="node-template-layout"')
  assert.ok(sourceFilterIndex >= 0 && sourceFilterIndex < layoutIndex)
  assert.match(nodeManagementSource, /<aside class="node-template-category-filter"[\s\S]*?<section class="node-template-group">/)
  assert.match(nodeManagementSource, /\.node-template-layout\s*\{[^}]*grid-template-columns:\s*minmax\(180px, 220px\)\s+minmax\(0, 1fr\)/)
  assert.match(nodeManagementSource, /\.node-template-category-options\s*\{[^}]*display:\s*grid/)
  assert.match(nodeManagementSource, /@media\s*\(max-width:\s*800px\)[\s\S]*?\.node-template-layout\s*\{[^}]*grid-template-columns:\s*1fr/)
})

test('节点列表隐藏重复筛选摘要并增高功能类型选项', () => {
  assert.doesNotMatch(nodeManagementSource, /class="node-template-group-head"/)
  assert.doesNotMatch(nodeManagementSource, /workflowNodes\.filteredDescription/)
  assert.doesNotMatch(nodeManagementSource, /<el-tag round>\{\{ filteredRows\.length \}\}<\/el-tag>/)
  assert.match(nodeManagementSource, /\.node-template-category-options\s+:deep\(\.el-radio-button__inner\)\s*\{[^}]*min-height:\s*48px[^}]*align-items:\s*center/)
})

test('英文功能类型限制在侧栏内并通过省略号和提示展示长名称', () => {
  assert.match(nodeManagementSource, /<el-tooltip v-for="category in categories"[^>]*:content="t\(`workflowCatalog\.categories\.\$\{category\}`\)"[^>]*placement="right"/)
  assert.match(nodeManagementSource, /class="node-template-category-label"/)
  assert.match(nodeManagementSource, /\.node-template-category-options\s+:deep\(\.el-radio-button__inner\)\s*\{[^}]*box-sizing:\s*border-box/)
  assert.match(nodeManagementSource, /\.node-template-category-label\s*\{[^}]*overflow:\s*hidden[^}]*text-overflow:\s*ellipsis[^}]*white-space:\s*nowrap/)
})
