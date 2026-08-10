import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { WORKFLOW_NODE_TYPES } from '../src/utils/workflowNodeConfig.js'
import { DOCUMENTED_WORKFLOW_NODE_TYPES, WORKFLOW_NODE_DOCUMENTATION, undocumentedWorkflowNodeTypes, workflowNodeDocument } from '../src/utils/workflowNodeDocumentation.js'

const viewSource=readFileSync(new URL('../src/views/WorkflowNodeDocsView.vue',import.meta.url),'utf8')

test('全部原生节点都生成统一说明文档骨架',()=>{
  assert.deepEqual(undocumentedWorkflowNodeTypes(),[])
  assert.deepEqual(new Set(DOCUMENTED_WORKFLOW_NODE_TYPES),new Set(WORKFLOW_NODE_TYPES))
  assert.equal(DOCUMENTED_WORKFLOW_NODE_TYPES.length,new Set(DOCUMENTED_WORKFLOW_NODE_TYPES).size)
  for(const type of WORKFLOW_NODE_TYPES){const doc=WORKFLOW_NODE_DOCUMENTATION[type];assert.ok(doc.input);assert.ok(doc.output);assert.ok(doc.behavior);assert.ok(doc.limitations)}
})

test('系统、导入和自建模板复用原生文档并保留模板元数据',()=>{
  const messages={
    'workflowNodeDocs.behaviors.RAG':'rag behavior','workflowNodeDocs.inputs.upstream':'upstream',
    'workflowNodeDocs.outputs.node':'node output','workflowNodeDocs.defaultLimitations':'limits'
  }
  const translate=(key,params)=>messages[key]||`${key}:${params?.nodeType||''}`
  const doc=workflowNodeDocument({nodeType:'RAG',name:'Imported RAG',description:'custom',source:'DIFY',functionalCategory:'AI',externalVersion:'1.0',config:{topK:3}},translate,key=>Object.hasOwn(messages,key))
  assert.equal(doc.name,'Imported RAG');assert.equal(doc.source,'DIFY');assert.equal(doc.behavior,'rag behavior');assert.equal(doc.externalVersion,'1.0');assert.match(doc.example,/"topK": 3/)
})

test('独立文档页面覆盖搜索、输入输出、配置、示例和限制',()=>{
  for(const token of ['workflowNodeDocs.search','workflowNodeDocs.input','workflowNodeDocs.output','workflowNodeDocs.configuration','workflowNodeDocs.example','workflowNodeDocs.limitations'])assert.match(viewSource,new RegExp(token.replace('.','\\.')))
  assert.match(viewSource,/http\.get\('\/workflow\/nodes'\)/)
  assert.doesNotMatch(viewSource,/v-html|marked|markdown-it/)
})
