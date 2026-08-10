import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import { nodeConfigFields, WORKFLOW_NODE_TYPES } from '../src/utils/workflowNodeConfig.js'
import { DOCUMENTED_WORKFLOW_NODE_TYPES, WORKFLOW_NODE_DOCUMENTATION, undocumentedWorkflowNodeTypes, workflowNodeDocument, workflowNodeExample } from '../src/utils/workflowNodeDocumentation.js'

const viewSource=readFileSync(new URL('../src/views/WorkflowNodeDocsView.vue',import.meta.url),'utf8')
const locales=[['zh-CN',zhCN],['en-US',enUS]]

/** 按 vue-i18n 点路径读取测试词条。 */
function message(locale,path){return path.split('.').reduce((value,key)=>value?.[key],locale)}
/** 创建与页面一致的最小翻译函数。 */
function translator(locale){return {translate:(key,params)=>message(locale,key)||`${key}:${params?.nodeType||''}`,has:key=>message(locale,key)!==undefined}}

test('全部原生节点都有不重复的完整双语说明',()=>{
  assert.deepEqual(undocumentedWorkflowNodeTypes(),[])
  assert.deepEqual(new Set(DOCUMENTED_WORKFLOW_NODE_TYPES),new Set(WORKFLOW_NODE_TYPES))
  assert.equal(DOCUMENTED_WORKFLOW_NODE_TYPES.length,new Set(DOCUMENTED_WORKFLOW_NODE_TYPES).size)
  for(const type of WORKFLOW_NODE_TYPES){
    const doc=WORKFLOW_NODE_DOCUMENTATION[type]
    for(const section of ['input','output','behavior','limitations']){
      assert.ok(doc[section],`${type} ${section} key`)
      for(const [name,locale] of locales) assert.ok(message(locale,doc[section]),`${name} ${type} ${section}`)
    }
  }
})

test('系统、导入和自建模板复用原生文档并保留模板元数据',()=>{
  const {translate,has}=translator(enUS)
  const doc=workflowNodeDocument({nodeType:'RAG',name:'Imported RAG',description:'custom',source:'DIFY',functionalCategory:'AI',externalVersion:'1.0',externalPublisher:'vendor',config:{topK:3}},translate,has)
  assert.equal(doc.name,'Imported RAG');assert.equal(doc.source,'DIFY');assert.match(doc.behavior,/Retrieves knowledge chunks/)
  assert.equal(doc.externalVersion,'1.0');assert.equal(doc.externalPublisher,'vendor');assert.match(doc.example,/"topK": 3/)
  assert.equal(workflowNodeDocument({nodeType:'UNKNOWN'},translate,has),null)
})

test('全部节点提供可解析的输入输出示例和当前分支必填字段清单',()=>{
  for(const [name,locale] of locales){
    const {translate,has}=translator(locale)
    for(const type of WORKFLOW_NODE_TYPES){
      const doc=workflowNodeDocument({nodeType:type},translate,has)
      assert.ok(doc,`${name} ${type} document`)
      assert.doesNotThrow(()=>JSON.parse(doc.inputExample),`${name} ${type} input example`)
      assert.doesNotThrow(()=>JSON.parse(doc.outputExample),`${name} ${type} output example`)
      assert.deepEqual(doc.requiredFields,doc.fields.filter(field=>field.requirement==='required').map(field=>field.key),`${name} ${type} required fields`)
    }
  }
})

test('全部配置字段都有双语名称和用途说明或编辑器说明回退',()=>{
  for(const type of WORKFLOW_NODE_TYPES){
    for(const field of nodeConfigFields(type)){
      for(const [name,locale] of locales){
        assert.ok(locale.workflowConfig.fieldLabels[field.key]||locale.tavilyConfig.fields[field.key],`${name} ${type}.${field.key} label`)
        assert.ok(locale.workflowNodeDocs.fieldDescriptions[field.key]||locale.workflowNodeDocs.editorDescriptions[field.editor]||locale.workflowNodeDocs.editorDescriptions.generic,`${name} ${type}.${field.key} description`)
      }
    }
  }
})

test('配置示例合并人工关键参数、字段默认值和模板配置且仅保留适用分支',()=>{
  const rag=workflowNodeExample('RAG',{topK:8,modelMode:'DIRECT',modelId:9})
  assert.equal(rag.knowledgeBaseId,1);assert.equal(rag.topK,8);assert.equal(rag.modelId,9)
  assert.ok(!Object.hasOwn(rag,'featureCode'));assert.ok(!Object.hasOwn(rag,'modelType'))
  const upsert=workflowNodeExample('KNOWLEDGE_UPSERT',{inputMode:'BASE64',base64:'ZGF0YQ=='})
  assert.equal(upsert.base64,'ZGF0YQ==');assert.ok(!Object.hasOwn(upsert,'content'))
})

test('配置文档展示类型、默认值、选项和动态必填状态',()=>{
  const {translate,has}=translator(zhCN)
  const http=workflowNodeDocument({nodeType:'HTTP',config:{}},translate,has)
  const method=http.fields.find(field=>field.key==='method'),url=http.fields.find(field=>field.key==='url')
  assert.equal(method.editor,'select');assert.equal(method.defaultValue,'GET');assert.deepEqual(method.options,['GET','POST','PUT','PATCH','DELETE'])
  assert.equal(url.requirement,'required')
  const rag=workflowNodeDocument({nodeType:'RAG',config:{modelMode:'DIRECT',modelId:2}},translate,has)
  assert.equal(rag.fields.find(field=>field.key==='modelId').requirement,'required')
  assert.ok(!rag.fields.some(field=>field.key==='featureCode'))
})

test('Tavily 专用配置字段具备完整双语名称',()=>{
  for(const key of ['searchDepth','maxResults','urls','extractDepth','format']){
    for(const [name,locale] of locales) assert.ok(locale.tavilyConfig.fields[key],`${name} ${key}`)
  }
})

test('独立文档页面覆盖完整使用说明、响应式目录和安全异常状态',()=>{
  for(const token of ['workflowNodeDocs.search','workflowNodeDocs.overview','workflowNodeDocs.quickStart','workflowNodeDocs.prerequisites','workflowNodeDocs.input','workflowNodeDocs.output','workflowNodeDocs.configuration','workflowNodeDocs.defaultValue','workflowNodeDocs.options','workflowNodeDocs.examples','workflowNodeDocs.inputExample','workflowNodeDocs.outputExample','workflowNodeDocs.troubleshooting','workflowNodeDocs.limitations','workflowNodeDocs.loadFailed','workflowNodeDocs.noResults'])assert.match(viewSource,new RegExp(token.replaceAll('.', '\\.')))
  assert.match(viewSource,/http\.get\('\/workflow\/node-docs'\)/)
  assert.match(viewSource,/http\.get\('\/workflow\/node-model-compatibility'\)/)
  assert.match(viewSource,/catch\{rows\.value=\[\];compatibilityRows\.value=\[\];selected\.value=null;error\.value=true\}/)
  assert.match(viewSource,/aria-current/)
  assert.match(viewSource,/docs-field-card/)
  assert.match(viewSource,/@media\(max-width:800px\)/)
  assert.doesNotMatch(viewSource,/<el-table/)
  assert.doesNotMatch(viewSource,/v-html|marked|markdown-it/)
})

test('模型节点文档展示后端统一兼容目录表格',()=>{
  for(const token of ['workflowNodeDocs.modelCompatibility','workflowNodeDocs.modelProtocol','workflowNodeDocs.recommendedModelType','workflowNodeDocs.allowedModelTypes','workflowNodeDocs.modelSources','workflowNodeDocs.filteringRule'])assert.match(viewSource,new RegExp(token.replaceAll('.', '\\.')))
  assert.match(viewSource,/v-if="currentCompatibility" class="docs-model-compatibility"/)
  assert.match(viewSource,/<table>/)
  assert.match(viewSource,/currentCompatibility\.allowedModelTypes\.map\(modelTypeLabel\)/)
  for(const locale of [zhCN,enUS]) assert.ok(locale.workflowNodeDocs.protocols.EMBEDDINGS)
})

test('桌面文档目录固定且说明区域独立滚动并在窄屏恢复自然布局',()=>{
  assert.doesNotMatch(viewSource,/<section class="docs-intro"/)
  assert.doesNotMatch(viewSource,/workflowNodeDocs\.(handbook|guideTitle|catalogOverview|templates|nodeTypes|matches)/)
  assert.match(viewSource,/<el-scrollbar class="docs-directory-scroll">/)
  assert.doesNotMatch(viewSource,/docs-directory-scroll" max-height=/)
  assert.match(viewSource,/\.docs-page\{[^}]*height:calc\(100vh - 124px\)[^}]*flex-direction:column/)
  assert.match(viewSource,/\.docs-layout\{[^}]*min-height:0[^}]*overflow:hidden/)
  assert.match(viewSource,/\.docs-directory\{[^}]*min-height:0[^}]*flex-direction:column/)
  assert.match(viewSource,/\.docs-directory-scroll\{[^}]*flex:1[^}]*min-height:0/)
  assert.match(viewSource,/\.docs-content\{[^}]*overflow-y:auto/)
  assert.match(viewSource,/@media\(max-width:800px\)\{\.docs-page\{[^}]*height:auto/)
  assert.match(viewSource,/@media\(max-width:800px\)[\s\S]*?\.docs-content\{[^}]*overflow:visible/)
})
