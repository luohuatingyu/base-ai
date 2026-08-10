<template>
  <div class="panel docs-page">
    <div class="section-head"><div><h2>{{ t('workflowNodeDocs.title') }}</h2><p>{{ t('workflowNodeDocs.description') }}</p></div></div>
    <div class="docs-layout"><aside><el-input v-model="query" clearable :placeholder="t('workflowNodeDocs.search')"/><el-scrollbar height="calc(100vh - 260px)"><button v-for="item in filtered" :key="item.id" :class="{active:selected?.id===item.id}" @click="select(item)"><strong>{{ templateText(item,'name') }}</strong><small>{{ item.nodeType }} · {{ t(`workflowCatalog.sources.${item.source}`) }}</small></button></el-scrollbar></aside>
      <main v-if="document"><header><div><h3>{{ templateText(selected,'name') }}</h3><p>{{ templateText(selected,'description') }}</p></div><el-tag>{{ document.nodeType }}</el-tag></header>
        <el-descriptions :column="2" border><el-descriptions-item :label="t('workflowNodeDocs.source')">{{ t(`workflowCatalog.sources.${document.source}`) }}</el-descriptions-item><el-descriptions-item :label="t('workflowNodeDocs.category')">{{ t(`workflowCatalog.categories.${document.category}`) }}</el-descriptions-item><el-descriptions-item v-if="document.externalPublisher" :label="t('workflowNodeDocs.publisher')">{{ document.externalPublisher }}</el-descriptions-item><el-descriptions-item v-if="document.externalVersion" :label="t('workflowNodeDocs.version')">{{ document.externalVersion }}</el-descriptions-item></el-descriptions>
        <section><h4>{{ t('workflowNodeDocs.behavior') }}</h4><p>{{ document.behavior }}</p></section><section class="two"><article><h4>{{ t('workflowNodeDocs.input') }}</h4><p>{{ document.input }}</p></article><article><h4>{{ t('workflowNodeDocs.output') }}</h4><p>{{ document.output }}</p></article></section>
        <section><h4>{{ t('workflowNodeDocs.configuration') }}</h4><el-table :data="document.fields" size="small"><el-table-column prop="key" :label="t('workflowNodeDocs.field')" width="190"/><el-table-column :label="t('workflowNodeDocs.fieldName')"><template #default="s">{{ fieldLabel(s.row.key) }}</template></el-table-column><el-table-column prop="requirement" :label="t('workflowNodeDocs.requirement')" width="150"/></el-table></section>
        <section><h4>{{ t('workflowNodeDocs.example') }}</h4><pre>{{ document.example }}</pre></section><section><h4>{{ t('workflowNodeDocs.limitations') }}</h4><p>{{ document.limitations }}</p></section>
      </main><el-empty v-else :description="t('workflowNodeDocs.empty')"/></div>
  </div>
</template>
<script setup>
import { computed,onMounted,ref } from 'vue'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { localizedTemplateText,normalizeTemplateMetadata } from '../utils/workflowTemplateCatalog'
import { workflowNodeDocument } from '../utils/workflowNodeDocumentation'
const { t,te }=useI18n(),rows=ref([]),selected=ref(null),query=ref('')
const filtered=computed(()=>rows.value.filter(item=>`${templateText(item,'name')} ${item.nodeType} ${templateText(item,'description')}`.toLowerCase().includes(query.value.trim().toLowerCase())))
const document=computed(()=>selected.value?workflowNodeDocument({...selected.value,name:templateText(selected.value,'name'),description:templateText(selected.value,'description')},t,te):null)
/** 返回系统本地化文案或模板原始文案。 */
function templateText(item,field){return localizedTemplateText(item,field,t,te)}
/** 选择一个模板并生成只读说明。 */
function select(item){selected.value=item}
/** 返回配置字段本地化名称。 */
function fieldLabel(key){const path=`workflowConfig.fieldLabels.${key}`;return te(path)?t(path):key}
/** 加载所有来源模板，确保文档中心与节点目录一致。 */
async function load(){rows.value=((await http.get('/workflow/nodes')).data||[]).map(normalizeTemplateMetadata);selected.value=rows.value[0]||null}
onMounted(load)
</script>
<style scoped>.docs-page{display:grid;gap:18px}.docs-layout{display:grid;grid-template-columns:280px minmax(0,1fr);gap:20px}.docs-layout aside{display:grid;align-content:start;gap:12px}.docs-layout aside button{display:flex;width:100%;flex-direction:column;gap:4px;padding:12px;border:0;border-radius:9px;background:transparent;text-align:left;cursor:pointer}.docs-layout aside button:hover,.docs-layout aside button.active{background:#eef4ff;color:#2459a9}.docs-layout aside small{color:var(--app-muted)}main{display:grid;align-content:start;gap:20px}main header{display:flex;justify-content:space-between;gap:16px}main h3,main h4,main p{margin:0}main header p,main section p{margin-top:6px;color:var(--app-muted);line-height:1.7}.two{display:grid;grid-template-columns:1fr 1fr;gap:14px}.two article,main>section{padding:16px;border:1px solid var(--app-border);border-radius:12px}pre{overflow:auto;padding:14px;border-radius:9px;background:#172033;color:#e8eef9}@media(max-width:800px){.docs-layout{grid-template-columns:1fr}.two{grid-template-columns:1fr}}</style>
