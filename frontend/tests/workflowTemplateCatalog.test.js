import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { WORKFLOW_NODE_TYPES } from '../src/utils/workflowNodeConfig.js'
import {
  defaultTemplateCategory, groupWorkflowTemplates, normalizeTemplateMetadata,
  WORKFLOW_TEMPLATE_CATEGORIES, WORKFLOW_TEMPLATE_SOURCES
} from '../src/utils/workflowTemplateCatalog.js'

const graphEditorSource = readFileSync(new URL('../src/components/WorkflowGraphEditor.vue', import.meta.url), 'utf8')
const nodeManagementSource = readFileSync(new URL('../src/views/WorkflowNodesView.vue', import.meta.url), 'utf8')

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

test('功能分组保持固定顺序并默认隐藏停用节点', () => {
  const templates = [
    { id: 1, nodeType: 'HTTP', enabled: true, source: 'N8N' },
    { id: 2, nodeType: 'LLM', enabled: true, source: 'DIFY' },
    { id: 3, nodeType: 'START', enabled: false, source: 'SYSTEM', systemTemplate: true }
  ]
  assert.deepEqual(groupWorkflowTemplates(templates).map(group => group.category), ['AI', 'NETWORK_API'])
  assert.deepEqual(groupWorkflowTemplates(templates, true).map(group => group.category), ['BASIC', 'AI', 'NETWORK_API'])
})

test('画布使用右键分类菜单并在点击坐标添加节点', () => {
  assert.match(graphEditorSource, /@pane-context-menu="openTemplateMenu"/)
  assert.match(graphEditorSource, /screenToFlowCoordinate/)
  assert.match(graphEditorSource, /addTemplateFromMenu/)
  assert.match(graphEditorSource, /workflow-template-source/)
  assert.doesNotMatch(graphEditorSource, /workflow-palette|startDrag|application\/workflow-template/)
})

test('节点管理可维护来源和功能分类', () => {
  assert.match(nodeManagementSource, /form\.source/)
  assert.match(nodeManagementSource, /form\.functionalCategory/)
  assert.match(nodeManagementSource, /v-model="form\.source"/)
  assert.match(nodeManagementSource, /WORKFLOW_TEMPLATE_SOURCES/)
  assert.match(nodeManagementSource, /WORKFLOW_TEMPLATE_CATEGORIES/)
})
