<template>
  <div class="panel docs-page">
    <div class="docs-layout">
      <aside class="docs-directory" :aria-label="t('workflowNodeDocs.directory')">
        <div class="docs-directory-head">
          <div><strong>{{ t('workflowNodeDocs.directory') }}</strong><small>{{ t('workflowNodeDocs.directoryHint') }}</small></div>
          <el-tag size="small" type="info" effect="plain">{{ filtered.length }}</el-tag>
        </div>
        <el-input v-model="query" clearable :placeholder="t('workflowNodeDocs.search')" :aria-label="t('workflowNodeDocs.search')"/>
        <el-scrollbar class="docs-directory-scroll">
          <nav class="docs-node-list">
            <section v-for="group in grouped" :key="group.category" class="docs-node-group"
              :aria-labelledby="`docs-node-group-${group.category}`">
              <h3 :id="`docs-node-group-${group.category}`" class="docs-node-group-title">
                <span>{{ categoryLabel(group.category) }}</span><small>{{ group.items.length }}</small>
              </h3>
              <button v-for="item in group.items" :key="item.id" type="button" :class="{active:current?.id===item.id}"
                :aria-current="current?.id===item.id?'page':undefined" @click="select(item)">
                <span class="docs-node-name"><strong>{{ templateText(item,'name') }}</strong><code>{{ item.nodeType }}</code></span>
                <small>{{ sourceLabel(item.source) }}</small>
              </button>
            </section>
          </nav>
          <el-empty v-if="!loading&&!error&&!filtered.length" :description="t('workflowNodeDocs.noResults')" :image-size="56"/>
        </el-scrollbar>
      </aside>

      <main class="docs-content">
        <el-skeleton v-if="loading" :rows="10" animated/>
        <div v-else-if="error" class="docs-state">
          <el-alert type="error" :title="t('workflowNodeDocs.loadFailed')" :description="t('workflowNodeDocs.loadFailedHint')" show-icon :closable="false"/>
          <el-button @click="load">{{ t('common.refresh') }}</el-button>
        </div>
        <template v-else-if="document">
          <header class="docs-hero">
            <div class="docs-tags">
              <el-tag effect="dark">{{ document.nodeType }}</el-tag>
              <el-tag effect="plain">{{ categoryLabel(document.category) }}</el-tag>
              <el-tag type="info" effect="plain">{{ sourceLabel(document.source) }}</el-tag>
            </div>
            <h2>{{ templateText(current,'name') }}</h2>
            <p>{{ templateText(current,'description') || document.behavior }}</p>
            <dl v-if="document.externalPublisher||document.externalVersion" class="docs-meta">
              <div v-if="document.externalPublisher"><dt>{{ t('workflowNodeDocs.publisher') }}</dt><dd>{{ document.externalPublisher }}</dd></div>
              <div v-if="document.externalVersion"><dt>{{ t('workflowNodeDocs.version') }}</dt><dd>{{ document.externalVersion }}</dd></div>
            </dl>
            <nav class="docs-section-nav" :aria-label="t('workflowNodeDocs.onThisPage')">
              <a v-for="section in sections" :key="section.id" :href="`#${section.id}`">{{ t(section.label) }}</a>
            </nav>
          </header>

          <section id="node-overview" class="docs-section" aria-labelledby="node-overview-title">
            <div class="docs-section-head"><span>01</span><div><h3 id="node-overview-title">{{ t('workflowNodeDocs.overview') }}</h3><p>{{ t('workflowNodeDocs.overviewHint') }}</p></div></div>
            <div class="docs-overview-grid">
              <article><strong>{{ t('workflowNodeDocs.behavior') }}</strong><p>{{ document.behavior }}</p></article>
              <article><strong>{{ t('workflowNodeDocs.input') }}</strong><p>{{ document.input }}</p></article>
              <article><strong>{{ t('workflowNodeDocs.output') }}</strong><p>{{ document.output }}</p></article>
            </div>
          </section>

          <section id="node-quick-start" class="docs-section" aria-labelledby="node-quick-start-title">
            <div class="docs-section-head"><span>02</span><div><h3 id="node-quick-start-title">{{ t('workflowNodeDocs.quickStart') }}</h3><p>{{ t('workflowNodeDocs.quickStartHint') }}</p></div></div>
            <div class="docs-prerequisites">
              <strong>{{ t('workflowNodeDocs.prerequisites') }}</strong>
              <span v-for="item in prerequisiteLabels(document)" :key="item">{{ item }}</span>
            </div>
            <ol class="docs-steps">
              <li><span>1</span><div><strong>{{ t('workflowNodeDocs.steps.addTitle') }}</strong><p>{{ t('workflowNodeDocs.steps.addDescription') }}</p></div></li>
              <li><span>2</span><div><strong>{{ t('workflowNodeDocs.steps.configureTitle') }}</strong><p>{{ configureStep(document) }}</p></div></li>
              <li><span>3</span><div><strong>{{ t('workflowNodeDocs.steps.verifyTitle') }}</strong><p>{{ t('workflowNodeDocs.steps.verifyDescription') }}</p></div></li>
            </ol>
            <div class="docs-reference-tip"><code v-pre>{{input.field}}</code><span>{{ t('workflowNodeDocs.inputReference') }}</span><code v-pre>{{nodes.nodeCode.field}}</code><span>{{ t('workflowNodeDocs.outputReference') }}</span></div>
          </section>

          <section id="node-configuration" class="docs-section" aria-labelledby="node-configuration-title">
            <div class="docs-section-head"><span>03</span><div><h3 id="node-configuration-title">{{ t('workflowNodeDocs.configuration') }}</h3><p>{{ t('workflowNodeDocs.configurationHint') }}</p></div></div>
            <div v-if="currentCompatibility" class="docs-model-compatibility">
              <div><strong>{{ t('workflowNodeDocs.modelCompatibility') }}</strong><p>{{ t('workflowNodeDocs.modelCompatibilityHint') }}</p></div>
              <div class="docs-table-wrap">
                <table>
                  <thead><tr><th>{{ t('workflowNodeDocs.modelProtocol') }}</th><th>{{ t('workflowNodeDocs.recommendedModelType') }}</th><th>{{ t('workflowNodeDocs.allowedModelTypes') }}</th><th>{{ t('workflowNodeDocs.modelSources') }}</th><th>{{ t('workflowNodeDocs.filteringRule') }}</th></tr></thead>
                  <tbody><tr><td>{{ protocolLabel(currentCompatibility.protocol) }}</td><td>{{ modelTypeLabel(currentCompatibility.recommendedModelType) }}</td><td>{{ currentCompatibility.allowedModelTypes.map(modelTypeLabel).join(t('workflowNodeDocs.listSeparator')) }}</td><td>{{ t('workflowNodeDocs.routeAndDirect') }}</td><td>{{ t('workflowNodeDocs.knownCompatibleOnly') }}</td></tr></tbody>
                </table>
              </div>
            </div>
            <div v-if="document.fields.length" class="docs-field-list">
              <article v-for="field in document.fields" :key="field.key" class="docs-field-card">
                <div class="docs-field-head">
                  <div><strong>{{ fieldLabel(field.key) }}</strong><code>{{ field.key }}</code></div>
                  <div><el-tag size="small" effect="plain">{{ fieldType(field.editor) }}</el-tag><el-tag size="small" :type="field.requirement?'danger':'info'" effect="plain">{{ requirementLabel(field.requirement) }}</el-tag></div>
                </div>
                <p>{{ fieldDescription(field) }}</p>
                <dl>
                  <div><dt>{{ t('workflowNodeDocs.defaultValue') }}</dt><dd><code>{{ displayValue(field.defaultValue) }}</code></dd></div>
                  <div><dt>{{ t('workflowNodeDocs.options') }}</dt><dd>{{ fieldOptions(field) }}</dd></div>
                </dl>
              </article>
            </div>
            <div v-else class="docs-empty-config"><strong>{{ t('workflowNodeDocs.noConfiguration') }}</strong><p>{{ t('workflowNodeDocs.noConfigurationHint') }}</p></div>
          </section>

          <section id="node-examples" class="docs-section" aria-labelledby="node-examples-title">
            <div class="docs-section-head"><span>04</span><div><h3 id="node-examples-title">{{ t('workflowNodeDocs.examples') }}</h3><p>{{ t('workflowNodeDocs.examplesHint') }}</p></div></div>
            <div class="docs-example-grid">
              <article><strong>{{ t('workflowNodeDocs.inputExample') }}</strong><pre>{{ document.inputExample }}</pre></article>
              <article><strong>{{ t('workflowNodeDocs.outputExample') }}</strong><pre>{{ document.outputExample }}</pre></article>
              <article class="docs-config-example"><strong>{{ t('workflowNodeDocs.example') }}</strong><pre>{{ document.example }}</pre></article>
            </div>
          </section>

          <section id="node-troubleshooting" class="docs-section" aria-labelledby="node-troubleshooting-title">
            <div class="docs-section-head"><span>05</span><div><h3 id="node-troubleshooting-title">{{ t('workflowNodeDocs.troubleshooting') }}</h3><p>{{ t('workflowNodeDocs.troubleshootingHint') }}</p></div></div>
            <div class="docs-troubleshooting-list">
              <article v-for="item in troubleshootingItems(document)" :key="item.title"><strong>{{ item.title }}</strong><p>{{ item.description }}</p></article>
            </div>
            <div class="docs-limitation"><strong>{{ t('workflowNodeDocs.limitations') }}</strong><p>{{ document.limitations }}</p></div>
          </section>
        </template>
        <el-empty v-else :description="current?t('workflowNodeDocs.unsupported'):t('workflowNodeDocs.noResults')"/>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed,onMounted,ref } from 'vue'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { groupWorkflowTemplates,localizedTemplateText,normalizeTemplateMetadata } from '../utils/workflowTemplateCatalog'
import { workflowNodeDocument } from '../utils/workflowNodeDocumentation'

const { t,te }=useI18n(),rows=ref([]),compatibilityRows=ref([]),selected=ref(null),query=ref(''),loading=ref(false),error=ref(false)
const sections=[
  {id:'node-overview',label:'workflowNodeDocs.overview'},
  {id:'node-quick-start',label:'workflowNodeDocs.quickStart'},
  {id:'node-configuration',label:'workflowNodeDocs.configuration'},
  {id:'node-examples',label:'workflowNodeDocs.examples'},
  {id:'node-troubleshooting',label:'workflowNodeDocs.troubleshooting'}
]
const filtered=computed(()=>{const keyword=query.value.trim().toLowerCase();return rows.value.filter(item=>`${templateText(item,'name')} ${item.nodeType} ${templateText(item,'description')} ${sourceLabel(item.source)} ${categoryLabel(item.functionalCategory)}`.toLowerCase().includes(keyword))})
/** 将搜索结果按受控功能分类的固定顺序组织，并自动移除空分组。 */
const grouped=computed(()=>groupWorkflowTemplates(filtered.value,true))
const current=computed(()=>filtered.value.find(item=>item.id===selected.value?.id)||filtered.value[0]||null)
const document=computed(()=>current.value?workflowNodeDocument({...current.value,name:templateText(current.value,'name'),description:templateText(current.value,'description')},t,te):null)
const currentCompatibility=computed(()=>compatibilityRows.value.find(item=>item.nodeType===document.value?.nodeType)||null)

/** 返回系统本地化文案或模板原始文案。 */
function templateText(item,field){return localizedTemplateText(item,field,t,te)}
/** 选择一个模板并生成只读说明。 */
function select(item){selected.value=item}
/** 返回模板来源的本地化名称并兼容自定义来源。 */
function sourceLabel(source){const path=`workflowCatalog.sources.${source}`;return te(path)?t(path):String(source||'-')}
/** 返回功能分类的本地化名称。 */
function categoryLabel(category){const path=`workflowCatalog.categories.${category}`;return te(path)?t(path):String(category||'-')}
/** 返回配置字段本地化名称，并复用 Tavily 专用字段词条。 */
function fieldLabel(key){const path=`workflowConfig.fieldLabels.${key}`,tavilyPath=`tavilyConfig.fields.${key}`;return te(path)?t(path):te(tavilyPath)?t(tavilyPath):key}
/** 返回配置字段用途说明，优先使用专属词条并以编辑器类型说明安全回退。 */
function fieldDescription(field){const path=`workflowNodeDocs.fieldDescriptions.${field.key}`,fallback=`workflowNodeDocs.editorDescriptions.${field.editor}`;return te(path)?t(path):t(te(fallback)?fallback:'workflowNodeDocs.editorDescriptions.generic',{field:fieldLabel(field.key)})}
/** 返回配置编辑器类型的用户可读名称。 */
function fieldType(editor){const path=`workflowNodeDocs.editors.${editor}`;return te(path)?t(path):editor}
/** 格式化字段默认值，明确区分空文本、空值和未提供默认值。 */
function displayValue(value){return value===undefined?t('workflowNodeDocs.none'):JSON.stringify(value)}
/** 本地化字段枚举选项；无枚举时显示“无”。 */
function fieldOptions(field){if(!field.options?.length)return t('workflowNodeDocs.none');return field.options.map(value=>{const path=`workflowConfig.options.${field.key}.${value}`;return te(path)?t(path):value}).join(' / ')}
/** 将内部必填级别转换为本地化文案。 */
function requirementLabel(requirement){return requirement?t(`workflowConfig.${requirement}`):t('workflowNodeDocs.optional')}
/** 本地化兼容目录中的模型类型，未知值保留稳定编码。 */
function modelTypeLabel(modelType){const path=`workflowConfig.options.modelType.${modelType}`;return te(path)?t(path):modelType}
/** 本地化后端声明的模型执行协议。 */
function protocolLabel(protocol){const path=`workflowNodeDocs.protocols.${protocol}`;return te(path)?t(path):protocol}
/** 按节点资源字段生成配置前置条件，避免向用户展示无关准备工作。 */
function prerequisiteLabels(doc){const keys=new Set(doc.fields.map(field=>field.key)),items=[];if(keys.has('modelMode'))items.push(t('workflowNodeDocs.prerequisiteItems.model'));if(keys.has('knowledgeBaseId'))items.push(t('workflowNodeDocs.prerequisiteItems.knowledgeBase'));if(keys.has('connectionId'))items.push(t('workflowNodeDocs.prerequisiteItems.connection'));if(keys.has('routeId'))items.push(t('workflowNodeDocs.prerequisiteItems.mailRoute'));if(keys.has('workflowCode'))items.push(t('workflowNodeDocs.prerequisiteItems.workflow'));if(['ITERATION','LOOP'].includes(doc.nodeType))items.push(t('workflowNodeDocs.prerequisiteItems.subgraph'));return items.length?items:[t('workflowNodeDocs.prerequisiteItems.none')]}
/** 依据当前适用分支列出必须完成的配置字段。 */
function configureStep(doc){const names=doc.requiredFields.map(fieldLabel).join(t('workflowNodeDocs.listSeparator'));return names?t('workflowNodeDocs.steps.configureDescription',{fields:names}):t('workflowNodeDocs.steps.noConfigurationDescription')}
/** 构造通用但可结合当前节点配置状态执行的排查清单。 */
function troubleshootingItems(doc){const fields=doc.requiredFields.map(fieldLabel).join(t('workflowNodeDocs.listSeparator'))||t('workflowNodeDocs.none');return[
  {title:t('workflowNodeDocs.troubleshootingItems.configurationTitle'),description:t('workflowNodeDocs.troubleshootingItems.configurationDescription',{fields})},
  {title:t('workflowNodeDocs.troubleshootingItems.executionTitle'),description:t('workflowNodeDocs.troubleshootingItems.executionDescription')},
  {title:t('workflowNodeDocs.troubleshootingItems.outputTitle'),description:t('workflowNodeDocs.troubleshootingItems.outputDescription')}
]}
/** 使用独立只读权限加载节点模板元数据，并提供明确失败状态。 */
async function load(){loading.value=true;error.value=false;try{const[templates,compatibility]=await Promise.all([http.get('/workflow/node-docs'),http.get('/workflow/node-model-compatibility')]);rows.value=(templates.data||[]).map(normalizeTemplateMetadata);compatibilityRows.value=compatibility.data||[];selected.value=rows.value[0]||null}catch{rows.value=[];compatibilityRows.value=[];selected.value=null;error.value=true}finally{loading.value=false}}
onMounted(load)
</script>

<style scoped>
.docs-page{display:flex;height:calc(100vh - 124px);min-height:0;flex-direction:column;padding:0}.docs-layout{display:grid;flex:1;min-height:0;grid-template-columns:270px minmax(0,1fr);overflow:hidden}.docs-directory{display:flex;min-width:0;min-height:0;flex-direction:column;padding:22px 16px;border-right:1px solid var(--app-border);background:#fbfcfe}.docs-directory-head{display:flex;align-items:center;justify-content:space-between;gap:12px;margin-bottom:12px}.docs-directory-head>div{display:flex;min-width:0;flex-direction:column;gap:3px}.docs-directory-head small{color:var(--app-muted);font-size:12px}.docs-directory-scroll{flex:1;min-height:0;margin-top:10px}.docs-node-list{display:flex;flex-direction:column;gap:14px;padding-right:8px}.docs-node-group{display:flex;min-width:0;flex-direction:column;gap:4px}.docs-node-group-title{position:sticky;top:0;z-index:1;display:flex;align-items:center;justify-content:space-between;gap:10px;margin:0;padding:7px 10px;background:#fbfcfe;color:#65738a;font-size:12px;line-height:1.4}.docs-node-group-title span{min-width:0;overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.docs-node-group-title small{display:grid;min-width:20px;height:20px;place-items:center;padding:0 5px;border-radius:999px;background:#edf1f7;color:#65738a;font-size:10px}.docs-node-list button{display:flex;width:100%;min-width:0;flex-direction:column;gap:5px;padding:11px 12px;border:1px solid transparent;border-radius:9px;background:transparent;color:var(--app-text);text-align:left;cursor:pointer;transition:background-color .16s ease,border-color .16s ease,color .16s ease}.docs-node-list button:hover{background:#f1f5fc}.docs-node-list button:focus-visible{outline:2px solid var(--app-primary);outline-offset:1px}.docs-node-list button.active{border-color:#d5e1fb;background:#eef4ff;color:#2459a9}.docs-node-name{display:flex;align-items:center;justify-content:space-between;gap:10px;min-width:0}.docs-node-name strong{overflow:hidden;text-overflow:ellipsis;white-space:nowrap}.docs-node-name code{flex:0 0 auto;color:inherit;font-size:10px}.docs-node-list button>small{overflow:hidden;color:var(--app-muted);font-size:11px;text-overflow:ellipsis;white-space:nowrap}.docs-content{display:block;min-width:0;padding:28px 32px 40px;overflow-y:auto;overscroll-behavior:contain;scroll-behavior:smooth}.docs-state{display:grid;justify-items:start;gap:16px}.docs-hero{padding-bottom:24px;border-bottom:1px solid var(--app-border)}.docs-tags{display:flex;flex-wrap:wrap;gap:8px}.docs-hero h2{margin:14px 0 0;font-size:28px;line-height:1.3}.docs-hero>p{max-width:850px;margin:8px 0 0;color:var(--app-muted);line-height:1.7}.docs-meta{display:flex;flex-wrap:wrap;gap:24px;margin:16px 0 0}.docs-meta div{display:flex;gap:7px;font-size:13px}.docs-meta dt{color:var(--app-muted)}.docs-meta dd{margin:0;font-weight:600}.docs-section-nav{display:flex;flex-wrap:wrap;gap:8px;margin-top:20px}.docs-section-nav a{padding:6px 10px;border-radius:7px;background:#f4f6fa;color:#536179;font-size:12px;text-decoration:none}.docs-section-nav a:hover{background:#eaf1ff;color:var(--app-primary-dark)}.docs-section{padding-top:30px;scroll-margin-top:16px}.docs-section-head{display:flex;align-items:flex-start;gap:12px;margin-bottom:16px}.docs-section-head>span{display:grid;width:28px;height:28px;flex:0 0 auto;place-items:center;border-radius:8px;background:#edf3ff;color:var(--app-primary-dark);font-size:11px;font-weight:700}.docs-section-head h3,.docs-section-head p{margin:0}.docs-section-head h3{font-size:18px}.docs-section-head p{margin-top:4px;color:var(--app-muted);font-size:13px;line-height:1.5}.docs-overview-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px}.docs-overview-grid article{padding:16px;border:1px solid var(--app-border);border-radius:10px;background:#fff}.docs-overview-grid strong{font-size:13px}.docs-overview-grid p{margin:7px 0 0;color:#56637a;font-size:13px;line-height:1.7}.docs-prerequisites{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-bottom:14px}.docs-prerequisites>strong{margin-right:4px;font-size:13px}.docs-prerequisites>span{padding:5px 9px;border:1px solid #dce5f5;border-radius:999px;background:#f8faff;color:#536179;font-size:12px}.docs-steps{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:12px;margin:0;padding:0;list-style:none}.docs-steps li{display:flex;gap:12px;padding:15px;border:1px solid var(--app-border);border-radius:10px}.docs-steps li>span{display:grid;width:24px;height:24px;flex:0 0 auto;place-items:center;border-radius:50%;background:var(--app-primary);color:#fff;font-size:12px;font-weight:700}.docs-steps strong{font-size:13px}.docs-steps p{margin:5px 0 0;color:var(--app-muted);font-size:12px;line-height:1.6}.docs-reference-tip{display:flex;align-items:center;flex-wrap:wrap;gap:8px;margin-top:12px;padding:11px 13px;border-radius:9px;background:#f7f9fc;color:var(--app-muted);font-size:12px}.docs-reference-tip code{color:#315fcb}.docs-model-compatibility{display:grid;gap:10px;margin-bottom:14px;padding:15px;border:1px solid #dce5f5;border-radius:10px;background:#f8faff}.docs-model-compatibility>div:first-child p{margin:4px 0 0;color:var(--app-muted);font-size:12px}.docs-table-wrap{overflow-x:auto}.docs-model-compatibility table{width:100%;min-width:760px;border-collapse:collapse;background:#fff;font-size:12px}.docs-model-compatibility th,.docs-model-compatibility td{padding:10px;border:1px solid #e1e7f0;text-align:left;vertical-align:top}.docs-model-compatibility th{background:#f3f6fb;color:#536179;font-weight:600}.docs-field-list{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:10px}.docs-field-card{min-width:0;padding:15px;border:1px solid var(--app-border);border-radius:10px}.docs-field-head{display:flex;align-items:flex-start;justify-content:space-between;gap:12px}.docs-field-head>div{display:flex;align-items:center;flex-wrap:wrap;gap:7px;min-width:0}.docs-field-head code{color:#65738a;font-size:11px}.docs-field-card>p{min-height:40px;margin:10px 0;color:#56637a;font-size:12px;line-height:1.65}.docs-field-card dl{display:grid;grid-template-columns:minmax(0,1fr) minmax(0,1fr);gap:8px;margin:0;padding-top:10px;border-top:1px solid #edf0f5}.docs-field-card dt{color:var(--app-muted);font-size:11px}.docs-field-card dd{margin:4px 0 0;overflow-wrap:anywhere;color:#3f4d63;font-size:12px}.docs-field-card dd code{white-space:normal;word-break:break-word}.docs-empty-config{padding:18px;border:1px dashed #d5ddea;border-radius:10px;background:#fafbfc;text-align:center}.docs-empty-config p{margin:5px 0 0;color:var(--app-muted);font-size:13px}.docs-example-grid{display:grid;grid-template-columns:repeat(2,minmax(0,1fr));gap:12px}.docs-example-grid article{min-width:0}.docs-example-grid article>strong{display:block;margin-bottom:8px;font-size:13px}.docs-config-example{grid-column:1/-1}.docs-example-grid pre{max-height:340px;overflow:auto;margin:0;padding:15px;border:1px solid #e1e7f0;border-radius:10px;background:#f7f9fc;color:#28364c;font:12px/1.65 ui-monospace,SFMono-Regular,Menlo,Monaco,Consolas,monospace;white-space:pre-wrap;word-break:break-word}.docs-troubleshooting-list{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:10px}.docs-troubleshooting-list article{padding:15px;border:1px solid var(--app-border);border-radius:10px}.docs-troubleshooting-list strong{font-size:13px}.docs-troubleshooting-list p{margin:6px 0 0;color:var(--app-muted);font-size:12px;line-height:1.65}.docs-limitation{margin-top:12px;padding:14px 16px;border-left:3px solid #e6a23c;border-radius:4px 9px 9px 4px;background:#fff9ed}.docs-limitation strong{font-size:13px}.docs-limitation p{margin:5px 0 0;color:#6c5a36;font-size:13px;line-height:1.65}
@media(max-width:1100px){.docs-overview-grid,.docs-steps,.docs-troubleshooting-list{grid-template-columns:1fr}.docs-field-list{grid-template-columns:1fr}}
@media(max-width:900px){.docs-page{height:calc(100vh - 94px)}}
@media(max-width:800px){.docs-page{display:block;height:auto}.docs-layout{grid-template-columns:1fr;overflow:visible}.docs-directory{border-right:0;border-bottom:1px solid var(--app-border)}.docs-directory-scroll{max-height:300px}.docs-content{padding:24px 20px 34px;overflow:visible;overscroll-behavior:auto}.docs-example-grid{grid-template-columns:1fr}.docs-config-example{grid-column:auto}.docs-hero h2{font-size:24px}}
@media(max-width:480px){.docs-content{padding-right:16px;padding-left:16px}.docs-field-head{flex-direction:column}.docs-field-card dl{grid-template-columns:1fr}}
</style>
