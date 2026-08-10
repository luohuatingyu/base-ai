<template>
  <div class="panel knowledge-page">
    <div class="section-head"><div><h2>{{ t('knowledgeBases.title') }}</h2><p>{{ t('knowledgeBases.description') }}</p></div><el-button v-if="auth.hasPermission('knowledge:base:create')" type="primary" @click="open()">{{ t('knowledgeBases.add') }}</el-button></div>
    <el-alert :title="t('knowledgeBases.connectionHint')" type="info" show-icon :closable="false" />
    <el-table :data="rows" table-layout="auto">
      <el-table-column prop="code" :label="t('common.code')" min-width="140"/><el-table-column prop="name" :label="t('common.name')" min-width="160"/>
      <el-table-column prop="storageType" :label="t('knowledgeBases.storageType')" width="150"/><el-table-column prop="resourceMode" :label="t('knowledgeBases.resourceMode')" width="140"/>
      <el-table-column :label="t('knowledgeBases.dimension')" width="110"><template #default="s">{{ s.row.embeddingDimension || '-' }}</template></el-table-column>
      <el-table-column :label="t('common.status')" width="100"><template #default="s"><el-tag :type="s.row.enabled?'success':'info'">{{ s.row.enabled?t('common.enabled'):t('common.disabled') }}</el-tag></template></el-table-column>
      <el-table-column :label="t('common.operation')" width="250" fixed="right"><template #default="s"><div class="table-actions"><el-button link type="success" @click="openDocuments(s.row)">{{ t('knowledgeBases.documents') }}</el-button><el-button v-if="auth.hasPermission('knowledge:base:update')" link type="primary" @click="open(s.row)">{{ t('common.edit') }}</el-button><el-button v-if="auth.hasPermission('knowledge:base:delete')" link type="danger" @click="remove(s.row)">{{ t('common.delete') }}</el-button></div></template></el-table-column>
    </el-table>
    <el-dialog v-model="visible" :title="form.id?t('knowledgeBases.edit'):t('knowledgeBases.add')" width="min(820px,94vw)">
      <el-form label-position="top"><div class="form-grid"><el-form-item :label="t('common.code')"><el-input v-model="form.code"/></el-form-item><el-form-item :label="t('common.name')"><el-input v-model="form.name"/></el-form-item></div>
        <el-form-item :label="t('common.description')"><el-input v-model="form.description" type="textarea"/></el-form-item>
        <el-form-item :label="t('knowledgeBases.vectorConnection')"><el-select v-model="form.connectionId" filterable class="full"><el-option v-for="item in vectorConnections" :key="item.id" :label="`${item.name} (${item.connectionType}) · ${t(`workflowConnections.vectorStatuses.${item.vectorStatus}`)}`" :value="item.id"/></el-select></el-form-item>
        <el-form-item :label="t('knowledgeBases.embeddingModel')"><el-select v-model="form.embeddingModelId" filterable class="full"><el-option v-for="item in embeddingModels" :key="item.id" :label="`${item.name} · ${item.modelName}`" :value="item.id"/></el-select></el-form-item>
        <div class="form-grid"><el-form-item :label="t('knowledgeBases.resourceMode')"><el-select v-model="form.resourceMode"><el-option :label="t('knowledgeBases.managed')" value="MANAGED"/><el-option :label="t('knowledgeBases.existing')" value="EXISTING"/></el-select></el-form-item><el-form-item v-if="form.resourceMode==='EXISTING'" :label="t('knowledgeBases.resourceName')"><el-input v-model="form.resourceName"/></el-form-item></div>
        <div class="form-grid three"><el-form-item :label="t('knowledgeBases.distance')"><el-select v-model="form.distanceMetric"><el-option v-for="item in ['COSINE','L2','IP']" :key="item" :label="item" :value="item"/></el-select></el-form-item><el-form-item :label="t('knowledgeBases.chunkSize')"><el-input-number v-model="form.chunkSize" :min="50" :max="500"/></el-form-item><el-form-item :label="t('knowledgeBases.chunkOverlap')"><el-input-number v-model="form.chunkOverlap" :min="0" :max="499"/></el-form-item></div>
        <el-form-item :label="t('common.status')"><el-switch v-model="form.enabled"/></el-form-item></el-form>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button type="primary" @click="save">{{ t('common.save') }}</el-button></template>
    </el-dialog>
    <el-drawer v-model="documentsVisible" :title="`${activeBase?.name || ''} · ${t('knowledgeBases.documents')}`" size="min(760px,94vw)">
      <div class="upload-row" v-if="auth.hasPermission('knowledge:base:update')"><input ref="fileInput" type="file" hidden @change="upload"><el-button type="primary" :loading="uploading" @click="fileInput?.click()">{{ t('knowledgeBases.upload') }}</el-button><small>{{ t('knowledgeBases.uploadHint') }}</small></div>
      <el-table :data="documents"><el-table-column prop="fileName" :label="t('knowledgeBases.fileName')" min-width="220"/><el-table-column prop="status" :label="t('common.status')" width="110"/><el-table-column prop="chunkCount" :label="t('knowledgeBases.chunks')" width="90"/><el-table-column :label="t('common.operation')" width="90"><template #default="s"><el-button v-if="auth.hasPermission('knowledge:base:update')" link type="danger" @click="removeDocument(s.row)">{{ t('common.delete') }}</el-button></template></el-table-column></el-table>
    </el-drawer>
  </div>
</template>
<script setup>
import { computed,onMounted,reactive,ref } from 'vue'
import { ElMessage,ElMessageBox } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http,{ showHttpError } from '../api/http'
import { useAuthStore } from '../stores/auth'
const { t }=useI18n(),auth=useAuthStore();const rows=ref([]),connections=ref([]),models=ref([]),visible=ref(false),documentsVisible=ref(false),documents=ref([]),activeBase=ref(null),fileInput=ref(null),uploading=ref(false);const form=reactive(emptyForm())
const vectorConnections=computed(()=>connections.value.filter(item=>['POSTGRESQL','QDRANT','MILVUS','ELASTICSEARCH'].includes(item.connectionType)&&item.vectorStatus==='SUPPORTED'))
const embeddingModels=computed(()=>models.value.filter(item=>item.enabled&&item.supportedModelTypes?.includes('embedding_model')))
/** 加载知识库及可复用模型、连接资源。 */
async function load(){const [bases,connectionRows,modelRows]=await Promise.all([http.get('/knowledge-bases'),http.get('/workflow/connections'),http.get('/models')]);rows.value=bases.data||[];connections.value=connectionRows.data||[];models.value=modelRows.data||[]}
/** 打开新增或编辑表单。 */
function open(row){Object.assign(form,emptyForm(),row||{});visible.value=true}
/** 保存知识库并由服务端校验向量能力。 */
async function save(){if(!form.code.trim()||!form.name.trim()||!form.connectionId||!form.embeddingModelId)return ElMessage.warning(t('knowledgeBases.required'));try{form.id?await http.put(`/knowledge-bases/${form.id}`,form):await http.post('/knowledge-bases',form);visible.value=false;await load();ElMessage.success(t('common.successSaved'))}catch(error){showHttpError(error,'common.saveFailed')}}
/** 删除知识库及平台管理的外部资源。 */
async function remove(row){try{await ElMessageBox.confirm(t('common.confirmDelete',{name:row.name}),t('common.deleteConfirm'));await http.delete(`/knowledge-bases/${row.id}`);await load()}catch(error){if(error!=='cancel'&&error!=='close')showHttpError(error)}}
/** 打开知识库文档列表。 */
async function openDocuments(row){activeBase.value=row;documentsVisible.value=true;documents.value=(await http.get(`/knowledge-bases/${row.id}/documents`)).data||[]}
/** 上传文档并等待本次索引完成。 */
async function upload(event){const file=event.target.files?.[0];event.target.value='';if(!file)return;uploading.value=true;try{const data=new FormData();data.append('file',file);await http.post(`/knowledge-bases/${activeBase.value.id}/documents`,data);await openDocuments(activeBase.value);await load();ElMessage.success(t('knowledgeBases.indexed'))}catch(error){showHttpError(error)}finally{uploading.value=false}}
/** 删除单个文档及其外部向量。 */
async function removeDocument(row){try{await ElMessageBox.confirm(t('common.confirmDelete',{name:row.fileName}),t('common.deleteConfirm'));await http.delete(`/knowledge-bases/${activeBase.value.id}/documents/${row.id}`);await openDocuments(activeBase.value)}catch(error){if(error!=='cancel'&&error!=='close')showHttpError(error)}}
/** 创建稳定默认配置。 */
function emptyForm(){return{id:null,code:'',name:'',description:'',connectionId:null,resourceMode:'MANAGED',resourceName:'',embeddingModelId:null,distanceMetric:'COSINE',chunkSize:400,chunkOverlap:60,enabled:true}}
onMounted(load)
</script>
<style scoped>.knowledge-page{display:grid;gap:18px}.form-grid{display:grid;grid-template-columns:1fr 1fr;gap:14px}.form-grid.three{grid-template-columns:1fr 1fr 1fr}.upload-row{display:flex;align-items:center;gap:12px;margin-bottom:16px}.upload-row small{color:var(--app-muted)}@media(max-width:700px){.form-grid,.form-grid.three{grid-template-columns:1fr}}</style>
