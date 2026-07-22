<!-- API 触发器页面：维护外部 API 配置、执行记录和测试操作。 -->
<template>
  <div class="panel">
    <div class="section-head"><div><h2>{{ t('apiTrigger.title') }}</h2><p>{{ t('apiTrigger.description') }}</p></div><el-button v-if="auth.hasPermission('automation:api-trigger:create')" type="primary" @click="open()">{{ t('apiTrigger.add') }}</el-button></div>
    <div class="filter-row"><el-input v-model="query.keyword" clearable :placeholder="t('apiTrigger.nameOrDescription')"/><el-select v-model="query.enabled" clearable :placeholder="t('apiTrigger.enabledStatus')"><el-option :label="t('common.enabled')" :value="true"/><el-option :label="t('common.disabled')" :value="false"/></el-select><el-button type="primary" @click="load">{{ t('common.query') }}</el-button></div>
    <el-table :data="rows">
      <el-table-column prop="name" :label="t('common.name')" min-width="160"/><el-table-column prop="httpMethod" :label="t('common.method')" width="85"/><el-table-column prop="url" label="URL" min-width="260" show-overflow-tooltip/>
      <el-table-column prop="cronExpression" label="Cron" min-width="150"><template #default="s">{{s.row.cronExpression||t('apiTrigger.manualOnly')}}</template></el-table-column>
      <el-table-column :label="t('common.status')" width="100"><template #default="s"><el-tag :type="s.row.enabled?'success':'info'">{{s.row.enabled?t('common.enabled'):t('common.disabled')}}</el-tag></template></el-table-column>
      <el-table-column prop="lastStatus" :label="t('apiTrigger.latestResult')" width="110"/><el-table-column :label="t('common.operation')" width="300"><template #default="s">
        <el-button v-if="auth.hasPermission('automation:api-trigger:update')" link type="primary" @click="open(s.row)">{{ t('common.edit') }}</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:trigger')" link type="success" @click="trigger(s.row)">{{ t('apiTrigger.execute') }}</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:logs')" link @click="showLogs(s.row)">{{ t('apiTrigger.logs') }}</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:delete')" link type="warning" @click="disable(s.row)">{{ t('apiTrigger.disable') }}</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:delete')" link type="danger" @click="voidConfig(s.row)">{{ t('apiTrigger.void') }}</el-button>
      </template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id?t('apiTrigger.edit'):t('apiTrigger.addTrigger')" width="min(960px, 94vw)" top="4vh" class="api-trigger-dialog">
      <el-tabs v-model="activeTab" class="trigger-tabs">
        <el-tab-pane :label="t('apiTrigger.basic')" name="basic">
          <el-form label-width="120px">
            <div class="form-grid">
              <el-form-item :label="t('common.name')"><el-input v-model="form.name"/></el-form-item>
              <el-form-item :label="t('common.enabled')"><el-switch v-model="form.enabled" :active-text="t('common.enabled')" :inactive-text="t('common.disabled')"/></el-form-item>
            </div>
            <el-form-item :label="t('common.description')"><el-input v-model="form.description"/></el-form-item>
            <el-form-item label="Cron"><el-input v-model="form.cronExpression" :placeholder="t('apiTrigger.cronPlaceholder')"/></el-form-item>
            <el-form-item :label="t('apiTrigger.authEnabled')">
              <el-switch v-model="form.authEnabled" :active-text="t('common.enabled')" :inactive-text="t('common.close')"/>
              <span class="form-help">{{ t('apiTrigger.authHelp') }}</span>
            </el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane :label="t('apiTrigger.business')" name="business">
          <el-form label-width="120px">
            <div class="form-grid">
              <el-form-item :label="t('apiTrigger.httpMethod')">
                <el-select v-model="form.httpMethod"><el-option v-for="item in methods" :key="item" :label="item" :value="item"/></el-select>
              </el-form-item>
              <el-form-item :label="t('apiTrigger.timeoutSeconds')"><el-input-number v-model="form.timeoutSeconds" :min="1" :max="300"/></el-form-item>
            </div>
            <el-form-item :label="t('apiTrigger.targetUrl')"><el-input v-model="form.url" placeholder="API_TRIGGER_ALLOWED_HOSTS"/></el-form-item>
            <el-form-item :label="t('apiTrigger.headers')"><el-input v-model="form.headers" type="textarea" :rows="3"/></el-form-item>
            <el-form-item :label="t('apiTrigger.queryParams')"><el-input v-model="form.queryParams" type="textarea" :rows="3"/></el-form-item>
            <el-form-item label="Content-Type"><el-input v-model="form.contentType"/></el-form-item>
            <el-form-item :label="t('apiTrigger.requestBody')"><el-input v-model="form.requestBody" type="textarea" :rows="4"/></el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane v-if="form.authEnabled" :label="t('apiTrigger.auth')" name="auth">
          <el-form label-width="120px">
            <div class="form-grid">
              <el-form-item :label="t('apiTrigger.authMethod')">
                <el-select v-model="form.authMethod"><el-option v-for="item in methods" :key="item" :label="item" :value="item"/></el-select>
              </el-form-item>
              <el-form-item :label="t('apiTrigger.authType')"><el-input v-model="form.authContentType"/></el-form-item>
            </div>
            <el-form-item :label="t('apiTrigger.authUrl')"><el-input v-model="form.authUrl"/></el-form-item>
            <el-form-item :label="t('apiTrigger.authBody')"><el-input v-model="form.authBody" type="textarea" :rows="3"/></el-form-item>
            <div class="form-grid">
              <el-form-item :label="t('apiTrigger.tokenPath')"><el-input v-model="form.authTokenPath"/></el-form-item>
              <el-form-item :label="t('apiTrigger.tokenHeader')"><el-input v-model="form.authTokenHeader"/></el-form-item>
            </div>
            <el-form-item :label="t('apiTrigger.tokenPrefix')"><el-input v-model="form.authTokenPrefix"/></el-form-item>
          </el-form>
        </el-tab-pane>

        <el-tab-pane v-if="auth.hasPermission('system:task:view')" :label="t('apiTrigger.progress')" name="progress">
          <div v-loading="progressLoading" class="progress-panel">
            <div class="progress-toolbar">
              <div>
                <span>{{ t('apiTrigger.traceReturned') }}</span>
                <code v-if="progressTraceId" class="trace-id-chip">{{ progressTraceId }}</code>
                <small v-else>{{ t('apiTrigger.traceHelp') }}</small>
              </div>
              <el-button :disabled="!progressTraceId" @click="queryProgress">{{ t('apiTrigger.refreshProgress') }}</el-button>
            </div>

            <el-alert v-if="progressError" :title="progressError" type="error" :closable="false" show-icon/>
            <el-empty v-if="!progressTraceId" :description="t('apiTrigger.noTrace')"/>
            <template v-else-if="progressDetail">
              <el-descriptions :column="2" border class="progress-details">
                <el-descriptions-item :label="t('common.status')"><el-tag :type="statusType(progressDetail.status)">{{progressDetail.status||'-'}}</el-tag></el-descriptions-item>
                <el-descriptions-item :label="t('tasks.taskType')">{{progressDetail.task_type||'-'}}</el-descriptions-item>
                <el-descriptions-item :label="t('tasks.triggerEntry')">{{progressDetail.trigger_entry||'-'}}</el-descriptions-item>
                <el-descriptions-item :label="t('apiTrigger.pythonTask')">{{progressDetail.pythonTraces?.length||0}}</el-descriptions-item>
                <el-descriptions-item :label="t('tasks.startedAt')">{{progressDetail.started_at||'-'}}</el-descriptions-item>
                <el-descriptions-item :label="t('tasks.heartbeatAt')">{{progressDetail.heartbeat_at||'-'}}</el-descriptions-item>
                <el-descriptions-item :label="t('tasks.finishedAt')" :span="2">{{progressDetail.finished_at||'-'}}</el-descriptions-item>
                <el-descriptions-item v-if="progressDetail.error_message" :label="t('tasks.failureReason')" :span="2">{{progressDetail.error_message}}</el-descriptions-item>
              </el-descriptions>

              <div v-if="progressDetail.pythonTraces?.length" class="progress-section">
                <h4>{{ t('apiTrigger.pythonTask') }}</h4>
                <el-table :data="progressDetail.pythonTraces" size="small" max-height="220">
                  <el-table-column prop="python_trace_id" label="Python Trace ID" min-width="220"/>
                  <el-table-column prop="status" :label="t('common.status')" width="110"/>
                  <el-table-column prop="worker_endpoint" :label="t('apiTrigger.workerEndpoint')" min-width="180"/>
                  <el-table-column prop="heartbeat_at" :label="t('tasks.heartbeatAt')" min-width="180"/>
                </el-table>
              </div>

              <div class="progress-section">
                <h4>{{ t('apiTrigger.unifiedLogs') }} <span>{{ t('apiTrigger.entries',{count:progressLogs.length}) }}</span></h4>
                <el-table :data="progressLogs" size="small" max-height="320" :empty-text="t('apiTrigger.noLogs')">
                  <el-table-column prop="logged_at" :label="t('common.time')" min-width="180"/>
                  <el-table-column :label="t('apiTrigger.level')" width="90"><template #default="s"><el-tag :type="logLevelType(s.row.level)" size="small">{{s.row.level}}</el-tag></template></el-table-column>
                  <el-table-column prop="source" :label="t('apiTrigger.source')" width="90"/>
                  <el-table-column prop="logger_name" :label="t('apiTrigger.logger')" min-width="150" show-overflow-tooltip/>
                  <el-table-column :label="t('apiTrigger.content')" min-width="280">
                    <template #default="s">
                      <div class="task-log-message">{{s.row.message}}</div>
                      <small v-if="s.row.throwable" class="task-log-throwable">{{s.row.throwable}}</small>
                    </template>
                  </el-table-column>
                </el-table>
              </div>
            </template>
          </div>
        </el-tab-pane>
      </el-tabs>
      <template #footer><el-button @click="visible=false">{{ t('common.cancel') }}</el-button><el-button v-if="auth.hasPermission('automation:api-trigger:trigger')" :loading="testing" @click="test">{{ t('apiTrigger.temporaryTest') }}</el-button><el-button type="primary" @click="save">{{ t('apiTrigger.save') }}</el-button></template>
    </el-dialog>
    <el-dialog v-model="resultVisible" :title="t('apiTrigger.result')" width="720px"><el-descriptions :column="3" border><el-descriptions-item :label="t('apiTrigger.http')">{{result.httpStatus}}</el-descriptions-item><el-descriptions-item :label="t('apiTrigger.duration')">{{result.durationMs}} ms</el-descriptions-item></el-descriptions><pre class="response-body">{{result.responseBody}}</pre></el-dialog>
    <el-drawer v-model="logsVisible" :title="t('apiTrigger.executionLogs')" size="65%"><el-table :data="logs"><el-table-column prop="triggeredAt" :label="t('common.time')" min-width="180"/><el-table-column prop="triggerType" :label="t('apiTrigger.trigger')"/><el-table-column prop="status" :label="t('common.status')"/><el-table-column prop="httpStatus" label="HTTP"/><el-table-column prop="durationMs" :label="t('apiTrigger.duration')"/><el-table-column prop="responseSummary" :label="t('apiTrigger.summary')" min-width="260" show-overflow-tooltip/></el-table></el-drawer>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { useI18n } from 'vue-i18n'
import { extractTraceId, fetchTaskProgress, resolveActiveTab } from '../utils/apiTrigger'
const { t } = useI18n(); const auth=useAuthStore(),rows=ref([]),visible=ref(false),testing=ref(false),resultVisible=ref(false),logsVisible=ref(false),logs=ref([]),result=ref({})
const query=reactive({keyword:'',enabled:''}),methods=['GET','POST','PUT','PATCH','DELETE']
const defaults={id:null,name:'',description:'',httpMethod:'GET',url:'',headers:'{}',queryParams:'{}',requestBody:'',contentType:'application/json',cronExpression:'',timeoutSeconds:30,enabled:true,authEnabled:false,authUrl:'',authMethod:'POST',authBody:'',authContentType:'application/json',authTokenPath:'data.token',authTokenHeader:'Authorization',authTokenPrefix:'Bearer '}
const form=reactive({...defaults})
const activeTab=ref('basic'),progressTraceId=ref(''),progressLoading=ref(false),progressDetail=ref(null),progressLogs=ref([]),progressError=ref(''),progressLoadedTraceId=ref('')
/** 查询接口触发配置。 */
async function load(){const params={keyword:query.keyword||undefined,enabled:query.enabled===''?undefined:query.enabled};rows.value=(await http.get('/automation/api-triggers',{params})).data}
/** 打开新增或编辑窗口。 */
function open(row){
  Object.assign(form,row?{...defaults,...row}:{...defaults})
  activeTab.value='basic'
  resetProgress(row?.lastResult)
  visible.value=true
}
/** 组装保存和临时测试共用的配置参数。 */
function payload(){const data={...form};delete data.id;return data}
/** 保存配置并触发后端 Cron 重注册。 */
async function save(){try{form.id?await http.put(`/automation/api-triggers/${form.id}`,payload()):await http.post('/automation/api-triggers',payload());visible.value=false;await load();ElMessage.success(t('common.successSaved'))}catch(e){ElMessage.error(e.response?.data?.message||t('common.saveFailed'))}}
/** 使用当前表单执行不落配置的临时调用。 */
async function test(){
  testing.value=true
  try{
    setExecutionResult((await http.post('/automation/api-triggers/test',payload())).data)
    resultVisible.value=true
  }catch(e){ElMessage.error(e.response?.data?.message||t('apiTrigger.testFailed'))}
  finally{testing.value=false}
}
/** 立即正式执行并写入任务与执行日志。 */
async function trigger(row){
  try{
    setExecutionResult((await http.post('/automation/api-triggers/'+row.id+'/trigger')).data)
    resultVisible.value=true
    await load()
  }catch(e){ElMessage.error(e.response?.data?.message||t('apiTrigger.executeFailed'))}
}
/** 根据目标接口响应重置自动识别的 Trace 进度上下文。 */
function resetProgress(responseBody){
  progressTraceId.value=extractTraceId(responseBody)
  progressDetail.value=null
  progressLogs.value=[]
  progressError.value=''
  progressLoadedTraceId.value=''
}
/** 保存调用结果，并在进度页已打开时自动加载任务详情与日志。 */
function setExecutionResult(data){
  result.value=data||{}
  resetProgress(result.value.responseBody)
  if(activeTab.value==='progress'&&progressTraceId.value&&auth.hasPermission('system:task:view'))queryProgress()
}
/** 查询目标接口返回 Trace ID 对应的平台进度与统一日志。 */
async function queryProgress(){
  if(!progressTraceId.value)return
  progressLoading.value=true
  progressError.value=''
  try{
    const progress=await fetchTaskProgress(http,progressTraceId.value)
    progressDetail.value=progress.detail
    progressLogs.value=progress.logs
    progressLoadedTraceId.value=progressTraceId.value
  }catch(e){
    progressDetail.value=null
    progressLogs.value=[]
    progressLoadedTraceId.value=''
    progressError.value=e.response?.data?.message||e.message||t('apiTrigger.progressFailed')
    ElMessage.error(progressError.value)
  }finally{progressLoading.value=false}
}
/** 将任务状态映射为 Element Plus 标签样式。 */
function statusType(status){return {SUCCESS:'success',FAILED:'danger',CANCELLED:'info',CANCEL_REQUESTED:'warning',RUNNING:'primary'}[status]||'info'}
/** 将链路日志级别映射为标签样式。 */
function logLevelType(level){return {ERROR:'danger',WARN:'warning',INFO:'success',DEBUG:'info'}[level]||'info'}
/** 查询配置执行日志。 */
async function showLogs(row){logs.value=(await http.get(`/automation/api-triggers/${row.id}/logs`)).data;logsVisible.value=true}
async function disable(row){await ElMessageBox.confirm(t('apiTrigger.disableConfirm',{name:row.name}),t('common.confirm'),{type:'warning'});await http.delete(`/automation/api-triggers/${row.id}`);load()}
async function voidConfig(row){await ElMessageBox.confirm(t('apiTrigger.voidConfirm',{name:row.name}),t('apiTrigger.voidTitle'),{type:'warning'});await http.post(`/automation/api-triggers/${row.id}/void`);load()}
watch(()=>form.authEnabled,enabled=>{activeTab.value=resolveActiveTab(enabled,activeTab.value)})
watch(activeTab,tab=>{
  if(tab==='progress'&&progressTraceId.value&&progressLoadedTraceId.value!==progressTraceId.value&&auth.hasPermission('system:task:view'))queryProgress()
})
onMounted(load)
</script>
