<template>
  <div class="panel"><div class="section-head"><div><h2>任务日志</h2><p>Java 与 Python 日志统一归档到 MySQL。</p></div><el-button @click="load">刷新</el-button></div>
    <el-table :data="rows"><el-table-column prop="job_id" label="任务编号" min-width="280"/><el-table-column prop="task_type" label="类型"/><el-table-column prop="status" label="状态"/><el-table-column prop="started_at" label="开始时间" min-width="180"/><el-table-column label="操作" width="100"><template #default="s"><el-button link type="primary" @click="showLogs(s.row.job_id)">日志</el-button></template></el-table-column></el-table>
    <el-drawer v-model="visible" title="任务日志" size="65%"><div v-for="item in logs" :key="item.id" class="log-line"><span>{{item.logged_at}}</span><b :class="item.level">{{item.level}}</b><em>{{item.source}}</em><code>{{item.message}}</code></div><el-empty v-if="!logs.length" description="暂无日志"/></el-drawer>
  </div>
</template>
<script setup>
import { onMounted, ref } from 'vue'; import http from '../api/http'
const rows=ref([]),logs=ref([]),visible=ref(false)
/** 刷新任务列表。 */
async function load(){rows.value=(await http.get('/system/tasks')).data}
/** 查询并展示统一任务日志。 */
async function showLogs(jobId){logs.value=(await http.get(`/system/tasks/${jobId}/logs`)).data;visible.value=true}
onMounted(load)
</script>
