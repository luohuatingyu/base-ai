<template>
  <div class="panel">
    <div class="section-head"><div><h2>任务调度</h2><p>AOP 统一跟踪控制器、定时任务和跨服务日志。</p></div><el-button @click="load">刷新</el-button></div>
    <div class="filter-row">
      <el-select v-model="query.status" clearable placeholder="任务状态"><el-option v-for="item in statuses" :key="item" :label="item" :value="item"/></el-select>
      <el-select v-model="query.taskType" clearable filterable placeholder="任务类型"><el-option v-for="item in taskTypes" :key="item" :label="item" :value="item"/></el-select>
      <el-select v-model="query.triggerEntry" clearable placeholder="触发入口"><el-option v-for="item in triggerEntries" :key="item" :label="item" :value="item"/></el-select>
      <el-button type="primary" @click="load">查询</el-button><el-button @click="reset">重置</el-button>
    </div>
    <el-table :data="rows">
      <el-table-column prop="job_id" label="任务编号" min-width="280"/>
      <el-table-column prop="task_type" label="任务类型" min-width="150"/>
      <el-table-column prop="trigger_entry" label="入口" width="110"/>
      <el-table-column label="状态" width="145"><template #default="s"><el-tag :type="statusType(s.row.status)">{{ s.row.status }}</el-tag></template></el-table-column>
      <el-table-column prop="started_at" label="开始时间" min-width="180"/>
      <el-table-column label="操作" width="250"><template #default="s">
        <el-button link type="primary" @click="showDetail(s.row)">详情</el-button>
        <el-button link type="primary" @click="showLogs(s.row.job_id)">日志</el-button>
        <el-button v-if="manageable(s.row)" link type="warning" @click="cancelJob(s.row.job_id)">取消</el-button>
        <el-button v-if="auth.isAdmin && manageable(s.row)" link type="danger" @click="forceJob(s.row.job_id)">强制终止</el-button>
      </template></el-table-column>
    </el-table>
    <el-drawer v-model="logVisible" title="任务日志" size="68%"><div v-for="item in logs" :key="item.id" class="log-line"><span>{{item.logged_at}}</span><b :class="item.level">{{item.level}}</b><em>{{item.source}}</em><code>{{item.message}}</code></div><el-empty v-if="!logs.length" description="暂无日志"/></el-drawer>
    <el-drawer v-model="detailVisible" title="任务详情" size="520px"><el-descriptions :column="1" border><el-descriptions-item v-for="(value,key) in detail" :key="key" :label="key"><pre class="detail-value">{{ value }}</pre></el-descriptions-item></el-descriptions></el-drawer>
  </div>
</template>
<script setup>
import { onMounted, reactive, ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
const auth=useAuthStore(), rows=ref([]),logs=ref([]),detail=ref({}),logVisible=ref(false),detailVisible=ref(false)
const taskTypes=ref([]),triggerEntries=ref([]),query=reactive({status:'',taskType:'',triggerEntry:''})
const statuses=['RESERVED','RUNNING','CANCEL_REQUESTED','CANCELLED','SUCCESS','FAILED']
/** 加载任务列表和筛选选项。 */
async function load(){const params=Object.fromEntries(Object.entries(query).filter(([,v])=>v)); const [jobs,types,entries]=await Promise.all([http.get('/system/tasks',{params}),http.get('/system/tasks/task-types'),http.get('/system/tasks/trigger-entries')]);rows.value=jobs.data;taskTypes.value=types.data;triggerEntries.value=entries.data}
function reset(){Object.assign(query,{status:'',taskType:'',triggerEntry:''});load()}
function manageable(row){return ['RUNNING','CANCEL_REQUESTED'].includes(row.status) && auth.hasPermission('system:task:manage')}
function statusType(status){return {SUCCESS:'success',FAILED:'danger',CANCELLED:'info',CANCEL_REQUESTED:'warning',RUNNING:'primary'}[status]||'info'}
/** 展示完整任务快照。 */
async function showDetail(row){detail.value=(await http.get(`/system/tasks/${row.job_id}`)).data;detailVisible.value=true}
/** 查询跨服务任务日志。 */
async function showLogs(jobId){logs.value=(await http.get(`/system/tasks/${jobId}/logs`)).data;logVisible.value=true}
/** 请求协作取消任务。 */
async function cancelJob(jobId){const {value}=await ElMessageBox.prompt('请输入取消原因','取消任务',{inputValue:'用户请求取消'});await http.post(`/system/tasks/${jobId}/cancel`,{reason:value});ElMessage.success('已发送取消请求');load()}
/** 管理员强制中断任务。 */
async function forceJob(jobId){await ElMessageBox.confirm('强制终止可能导致外部请求仍在处理中，是否继续？','强制终止',{type:'warning'});await http.post(`/system/tasks/${jobId}/force-terminate`,{reason:'管理员强制终止'});ElMessage.success('任务已终止');load()}
onMounted(load)
</script>
