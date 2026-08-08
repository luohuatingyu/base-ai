import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import { WORKFLOW_NODE_TYPES } from '../src/utils/workflowNodeConfig.js'
import {
  defaultTemplateCategory, filterWorkflowTemplates, groupWorkflowTemplates, localizedTemplateText, normalizeTemplateMetadata,
  systemTemplateTranslationKey,
  WORKFLOW_TEMPLATE_CATEGORIES, WORKFLOW_TEMPLATE_SOURCES
} from '../src/utils/workflowTemplateCatalog.js'

const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')
const nodeManagementSource = readFileSync(new URL('../src/views/WorkflowNodesView.vue', import.meta.url), 'utf8')

/** 创建与 vue-i18n 文案查询行为一致的轻量测试替身。 */
function localeAccessors(messages) {
  const find = key => key.split('.').reduce((value, part) => value?.[part], messages)
  return { translate: key => find(key), hasTranslation: key => find(key) !== undefined }
}

test('全部原生节点属于受控功能分类', () => {
  assert.deepEqual(WORKFLOW_TEMPLATE_SOURCES, ['SYSTEM', 'N8N', 'DIFY'])
  for (const nodeType of WORKFLOW_NODE_TYPES) {
    assert.ok(WORKFLOW_TEMPLATE_CATEGORIES.includes(defaultTemplateCategory(nodeType)), nodeType)
  }
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

test('节点管理卡片仅展示当前语言的功能文案并隐藏技术编码和节点类型', () => {
  assert.match(nodeManagementSource, /templateIcon\(row\)/)
  assert.match(nodeManagementSource, /templateText\(row, 'name'\)/)
  assert.match(nodeManagementSource, /templateText\(row, 'description'\)/)
  assert.doesNotMatch(nodeManagementSource, /\{\{\s*row\.code\s*\}\}/)
  assert.doesNotMatch(nodeManagementSource, /\{\{\s*row\.nodeType\s*\}\}/)
})
