<template>
  <div class="panel docs-page">
    <div class="section-head"><div><h2>{{ t('workflowNodeDocs.title') }}</h2><p>{{ t('workflowNodeDocs.description') }}</p></div></div>
    <div class="docs-layout">
      <aside>
        <el-input v-model="query" clearable :placeholder="t('workflowNodeDocs.search')"/>
        <el-scrollbar height="calc(100vh - 260px)">
          <button v-for="item in filtered" :key="item.id" :class="{active:current?.id===item.id}" @click="select(item)">
            <strong>{{ templateText(item,'name') }}</strong><small>{{ item.nodeType }} · {{ sourceLabel(item.source) }}</small>
          </button>
          <el-empty v-if="!loading&&!error&&!filtered.length" :description="t('workflowNodeDocs.noResults')"/>
        </el-scrollbar>
      </aside>
      <main>
        <el-skeleton v-if="loading" :rows="8" animated/>
        <el-alert v-else-if="error" type="error" :title="t('workflowNodeDocs.loadFailed')" show-icon :closable="false"/>
        <template v-else-if="document">
          <header><div><h3>{{ templateText(current,'name') }}</h3><p>{{ templateText(current,'description') }}</p></div><el-tag>{{ document.nodeType }}</el-tag></header>
          <el-descriptions :column="2" border>
            <el-descriptions-item :label="t('workflowNodeDocs.source')">{{ sourceLabel(document.source) }}</el-descriptions-item>
            <el-descriptions-item :label="t('workflowNodeDocs.category')">{{ categoryLabel(document.category) }}</el-descriptions-item>
            <el-descriptions-item v-if="document.externalPublisher" :label="t('workflowNodeDocs.publisher')">{{ document.externalPublisher }}</el-descriptions-item>
            <el-descriptions-item v-if="document.externalVersion" :label="t('workflowNodeDocs.version')">{{ document.externalVersion }}</el-descriptions-item>
          </el-descriptions>
          <section><h4>{{ t('workflowNodeDocs.behavior') }}</h4><p>{{ document.behavior }}</p></section>
          <section class="two"><article><h4>{{ t('workflowNodeDocs.input') }}</h4><p>{{ document.input }}</p></article><article><h4>{{ t('workflowNodeDocs.output') }}</h4><p>{{ document.output }}</p></article></section>
          <section><h4>{{ t('workflowNodeDocs.configuration') }}</h4>
            <el-table :data="document.fields" size="small">
              <el-table-column prop="key" :label="t('workflowNodeDocs.field')" min-width="150"/>
              <el-table-column :label="t('workflowNodeDocs.fieldName')" min-width="150"><template #default="scope">{{ fieldLabel(scope.row.key) }}</template></el-table-column>
              <el-table-column :label="t('workflowNodeDocs.fieldType')" min-width="120"><template #default="scope">{{ fieldType(scope.row.editor) }}</template></el-table-column>
              <el-table-column :label="t('workflowNodeDocs.defaultValue')" min-width="160"><template #default="scope"><code>{{ displayValue(scope.row.defaultValue) }}</code></template></el-table-column>
              <el-table-column :label="t('workflowNodeDocs.options')" min-width="180"><template #default="scope">{{ fieldOptions(scope.row) }}</template></el-table-column>
              <el-table-column :label="t('workflowNodeDocs.requirement')" width="130"><template #default="scope">{{ requirementLabel(scope.row.requirement) }}</template></el-table-column>
            </el-table>
          </section>
          <section><h4>{{ t('workflowNodeDocs.example') }}</h4><pre>{{ document.example }}</pre></section>
          <section><h4>{{ t('workflowNodeDocs.limitations') }}</h4><p>{{ document.limitations }}</p></section>
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
import { localizedTemplateText,normalizeTemplateMetadata } from '../utils/workflowTemplateCatalog'
import { workflowNodeDocument } from '../utils/workflowNodeDocumentation'

const { t,te }=useI18n(),rows=ref([]),selected=ref(null),query=ref(''),loading=ref(false),error=ref(false)
const filtered=computed(()=>rows.value.filter(item=>`${templateText(item,'name')} ${item.nodeType} ${templateText(item,'description')}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const current=computed(()=>filtered.value.find(item=>item.id===selected.value?.id)||filtered.value[0]||null)
const document=computed(()=>current.value?workflowNodeDocument({...current.value,name:templateText(current.value,'name'),description:templateText(current.value,'description')},t,te):null)

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
/** 返回配置编辑器类型的用户可读名称。 */
function fieldType(editor){const path=`workflowNodeDocs.editors.${editor}`;return te(path)?t(path):editor}
/** 格式化字段默认值，明确区分空文本、空值和未提供默认值。 */
function displayValue(value){return value===undefined?t('workflowNodeDocs.none'):JSON.stringify(value)}
/** 本地化字段枚举选项；无枚举时显示“无”。 */
function fieldOptions(field){if(!field.options?.length)return t('workflowNodeDocs.none');return field.options.map(value=>{const path=`workflowConfig.options.${field.key}.${value}`;return te(path)?t(path):value}).join(' / ')}
/** 将内部必填级别转换为本地化文案。 */
function requirementLabel(requirement){return requirement?t(`workflowConfig.${requirement}`):t('workflowNodeDocs.optional')}
/** 使用独立只读权限加载节点模板元数据，并提供明确失败状态。 */
async function load(){loading.value=true;error.value=false;try{rows.value=((await http.get('/workflow/node-docs')).data||[]).map(normalizeTemplateMetadata);selected.value=rows.value[0]||null}catch{rows.value=[];selected.value=null;error.value=true}finally{loading.value=false}}
onMounted(load)
</script>
<style scoped>.docs-page{display:grid;gap:18px}.docs-layout{display:grid;grid-template-columns:280px minmax(0,1fr);gap:20px}.docs-layout aside{display:grid;align-content:start;gap:12px}.docs-layout aside button{display:flex;width:100%;flex-direction:column;gap:4px;padding:12px;border:0;border-radius:9px;background:transparent;text-align:left;cursor:pointer}.docs-layout aside button:hover,.docs-layout aside button.active{background:#eef4ff;color:#2459a9}.docs-layout aside small{color:var(--app-muted)}main{display:grid;align-content:start;gap:20px;min-width:0}main header{display:flex;justify-content:space-between;gap:16px}main h3,main h4,main p{margin:0}main header p,main section p{margin-top:6px;color:var(--app-muted);line-height:1.7}.two{display:grid;grid-template-columns:1fr 1fr;gap:14px}.two article,main>section{padding:16px;border:1px solid var(--app-border);border-radius:12px}code{white-space:normal;word-break:break-word}pre{overflow:auto;padding:14px;border-radius:9px;background:#172033;color:#e8eef9}@media(max-width:800px){.docs-layout{grid-template-columns:1fr}.two{grid-template-columns:1fr}}</style>
