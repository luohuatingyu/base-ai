<template>
  <div class="panel">
    <div class="section-head"><div><h2>接口触发</h2><p>配置保存于 PostgreSQL，正式执行同步进入 MySQL 任务调度。</p></div><el-button v-if="auth.hasPermission('automation:api-trigger:create')" type="primary" @click="open()">新增配置</el-button></div>
    <div class="filter-row"><el-input v-model="query.keyword" clearable placeholder="名称或描述"/><el-select v-model="query.enabled" clearable placeholder="启用状态"><el-option label="启用" :value="true"/><el-option label="停用" :value="false"/></el-select><el-button type="primary" @click="load">查询</el-button></div>
    <el-table :data="rows">
      <el-table-column prop="name" label="名称" min-width="160"/><el-table-column prop="httpMethod" label="方法" width="85"/><el-table-column prop="url" label="URL" min-width="260" show-overflow-tooltip/>
      <el-table-column prop="cronExpression" label="Cron" min-width="150"><template #default="s">{{s.row.cronExpression||'仅手动'}}</template></el-table-column>
      <el-table-column label="状态" width="100"><template #default="s"><el-tag :type="s.row.enabled?'success':'info'">{{s.row.enabled?'启用':'停用'}}</el-tag></template></el-table-column>
      <el-table-column prop="lastStatus" label="最近结果" width="110"/><el-table-column label="操作" width="300"><template #default="s">
        <el-button v-if="auth.hasPermission('automation:api-trigger:update')" link type="primary" @click="open(s.row)">编辑</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:trigger')" link type="success" @click="trigger(s.row)">执行</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:logs')" link @click="showLogs(s.row)">日志</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:delete')" link type="warning" @click="disable(s.row)">停用</el-button>
        <el-button v-if="auth.hasPermission('automation:api-trigger:delete')" link type="danger" @click="voidConfig(s.row)">作废</el-button>
      </template></el-table-column>
    </el-table>

    <el-dialog v-model="visible" :title="form.id?'编辑接口触发':'新增接口触发'" width="820px" top="4vh">
      <el-form label-width="120px">
        <div class="form-grid"><el-form-item label="名称"><el-input v-model="form.name"/></el-form-item><el-form-item label="启用"><el-switch v-model="form.enabled"/></el-form-item></div>
        <el-form-item label="描述"><el-input v-model="form.description"/></el-form-item>
        <div class="form-grid"><el-form-item label="HTTP 方法"><el-select v-model="form.httpMethod"><el-option v-for="item in methods" :key="item" :label="item" :value="item"/></el-select></el-form-item><el-form-item label="超时秒数"><el-input-number v-model="form.timeoutSeconds" :min="1" :max="300"/></el-form-item></div>
        <el-form-item label="目标 URL"><el-input v-model="form.url" placeholder="必须属于 API_TRIGGER_ALLOWED_HOSTS"/></el-form-item>
        <el-form-item label="请求头 JSON"><el-input v-model="form.headers" type="textarea" :rows="3"/></el-form-item>
        <el-form-item label="查询参数 JSON"><el-input v-model="form.queryParams" type="textarea" :rows="3"/></el-form-item>
        <el-form-item label="请求体"><el-input v-model="form.requestBody" type="textarea" :rows="4"/></el-form-item>
        <div class="form-grid"><el-form-item label="Content-Type"><el-input v-model="form.contentType"/></el-form-item><el-form-item label="Cron"><el-input v-model="form.cronExpression" placeholder="留空仅手动执行"/></el-form-item></div>
        <el-divider content-position="left">前置认证</el-divider>
        <el-form-item label="启用认证"><el-switch v-model="form.authEnabled"/></el-form-item>
        <template v-if="form.authEnabled">
          <div class="form-grid"><el-form-item label="认证方法"><el-select v-model="form.authMethod"><el-option v-for="item in methods" :key="item" :label="item" :value="item"/></el-select></el-form-item><el-form-item label="认证类型"><el-input v-model="form.authContentType"/></el-form-item></div>
          <el-form-item label="认证 URL"><el-input v-model="form.authUrl"/></el-form-item><el-form-item label="认证请求体"><el-input v-model="form.authBody" type="textarea" :rows="3"/></el-form-item>
          <div class="form-grid"><el-form-item label="Token 路径"><el-input v-model="form.authTokenPath"/></el-form-item><el-form-item label="Token 请求头"><el-input v-model="form.authTokenHeader"/></el-form-item></div>
          <el-form-item label="Token 前缀"><el-input v-model="form.authTokenPrefix"/></el-form-item>
        </template>
      </el-form>
      <template #footer><el-button @click="visible=false">取消</el-button><el-button v-if="auth.hasPermission('automation:api-trigger:trigger')" :loading="testing" @click="test">临时测试</el-button><el-button type="primary" @click="save">保存</el-button></template>
    </el-dialog>
    <el-dialog v-model="resultVisible" title="调用结果" width="720px"><el-descriptions :column="3" border><el-descriptions-item label="HTTP">{{result.httpStatus}}</el-descriptions-item><el-descriptions-item label="耗时">{{result.durationMs}} ms</el-descriptions-item></el-descriptions><pre class="response-body">{{result.responseBody}}</pre></el-dialog>
    <el-drawer v-model="logsVisible" title="执行日志" size="65%"><el-table :data="logs"><el-table-column prop="triggeredAt" label="时间" min-width="180"/><el-table-column prop="triggerType" label="触发"/><el-table-column prop="status" label="状态"/><el-table-column prop="httpStatus" label="HTTP"/><el-table-column prop="durationMs" label="耗时"/><el-table-column prop="responseSummary" label="结果摘要" min-width="260" show-overflow-tooltip/></el-table></el-drawer>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
const auth=useAuthStore(),rows=ref([]),visible=ref(false),testing=ref(false),resultVisible=ref(false),logsVisible=ref(false),logs=ref([]),result=ref({})
const query=reactive({keyword:'',enabled:''}),methods=['GET','POST','PUT','PATCH','DELETE']
const defaults={id:null,name:'',description:'',httpMethod:'GET',url:'',headers:'{}',queryParams:'{}',requestBody:'',contentType:'application/json',cronExpression:'',timeoutSeconds:30,enabled:true,authEnabled:false,authUrl:'',authMethod:'POST',authBody:'',authContentType:'application/json',authTokenPath:'data.token',authTokenHeader:'Authorization',authTokenPrefix:'Bearer '}
const form=reactive({...defaults})
/** 查询接口触发配置。 */
async function load(){const params={keyword:query.keyword||undefined,enabled:query.enabled===''?undefined:query.enabled};rows.value=(await http.get('/automation/api-triggers',{params})).data}
/** 打开新增或编辑窗口。 */
function open(row){Object.assign(form,row?{...defaults,...row}:{...defaults});visible.value=true}
function payload(){const data={...form};delete data.id;return data}
/** 保存配置并触发后端 Cron 重注册。 */
async function save(){try{form.id?await http.put(`/automation/api-triggers/${form.id}`,payload()):await http.post('/automation/api-triggers',payload());visible.value=false;await load();ElMessage.success('保存成功')}catch(e){ElMessage.error(e.response?.data?.message||'保存失败')}}
/** 使用当前表单执行不落配置的临时调用。 */
async function test(){testing.value=true;try{result.value=(await http.post('/automation/api-triggers/test',payload())).data;resultVisible.value=true}catch(e){ElMessage.error(e.response?.data?.message||'测试失败')}finally{testing.value=false}}
/** 立即正式执行并写入任务与执行日志。 */
async function trigger(row){try{result.value=(await http.post(`/automation/api-triggers/${row.id}/trigger`)).data;resultVisible.value=true;load()}catch(e){ElMessage.error(e.response?.data?.message||'执行失败')}}
/** 查询配置执行日志。 */
async function showLogs(row){logs.value=(await http.get(`/automation/api-triggers/${row.id}/logs`)).data;logsVisible.value=true}
async function disable(row){await ElMessageBox.confirm(`确认停用 ${row.name}？`);await http.delete(`/automation/api-triggers/${row.id}`);load()}
async function voidConfig(row){await ElMessageBox.confirm(`作废后不再显示 ${row.name}，是否继续？`,'作废配置',{type:'warning'});await http.post(`/automation/api-triggers/${row.id}/void`);load()}
onMounted(load)
</script>
