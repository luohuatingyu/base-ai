<template>
  <div class="panel">
    <div class="section-head">
      <div>
        <h2>任务调度</h2>
        <p>AOP 统一跟踪控制器、定时任务和跨服务日志。</p>
      </div>
      <el-button @click="load">刷新</el-button>
    </div>

    <!-- 筛选器 -->
    <div class="filter-section">
      <!-- 第一行：任务状态、任务类型、触发入口、仅显示有日志开关、按钮 -->
      <div class="filter-row">
        <el-select v-model="query.status" clearable placeholder="任务状态" class="filter-item-select">
          <el-option v-for="item in statuses" :key="item" :label="item" :value="item"/>
        </el-select>
        <el-select v-model="query.taskType" clearable filterable placeholder="任务类型" class="filter-item-select">
          <el-option v-for="item in taskTypes" :key="item" :label="item" :value="item"/>
        </el-select>
        <el-select v-model="query.triggerEntry" clearable placeholder="触发入口" class="filter-item-select">
          <el-option v-for="item in triggerEntries" :key="item" :label="item" :value="item"/>
        </el-select>
        <el-switch
          v-model="query.onlyWithLogs"
          active-text="仅显示有日志"
          class="filter-switch"
        />
        <div class="filter-actions">
          <el-button type="primary" @click="load" :loading="loading">查询</el-button>
          <el-button @click="reset">重置</el-button>
        </div>
      </div>
      <!-- 第二行：关键字和时间 -->
      <div class="filter-row">
        <el-input
          v-model="query.logKeyword"
          clearable
          placeholder="日志关键字"
          :disabled="!query.taskType"
          class="filter-item-keyword"
          @keyup.enter="load"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-date-picker
          v-model="dateRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="filter-item-date"
        />
      </div>
    </div>

    <!-- 数据表格 -->
    <el-table :data="rows" v-loading="loading" class="tasks-table">
      <el-table-column prop="trace_id" label="Trace ID" min-width="280">
        <template #default="scope">
          <el-text class="trace-id" truncated>{{ scope.row.trace_id }}</el-text>
        </template>
      </el-table-column>
      <el-table-column prop="task_type" label="任务类型" min-width="150"/>
      <el-table-column prop="trigger_entry" label="入口" width="110"/>
      <el-table-column label="状态" width="145">
        <template #default="s">
          <el-tag :type="statusType(s.row.status)" effect="light">
            {{ s.row.status }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" label="创建时间" min-width="180"/>
      <el-table-column prop="started_at" label="开始时间" min-width="180"/>
      <el-table-column label="操作" width="250" fixed="right">
        <template #default="s">
          <el-button link type="primary" @click="showDetail(s.row)">详情</el-button>
          <el-button link type="primary" @click="showLogs(s.row.trace_id)">日志</el-button>
          <el-button v-if="manageable(s.row)" link type="warning" @click="cancelTrace(s.row.trace_id)">取消</el-button>
          <el-button v-if="auth.isAdmin && manageable(s.row)" link type="danger" @click="forceTrace(s.row.trace_id)">强制终止</el-button>
        </template>
      </el-table-column>
    </el-table>

    <!-- 分页 -->
    <el-pagination
      v-model:current-page="pagination.page"
      v-model:page-size="pagination.pageSize"
      :page-sizes="[10, 20, 50, 100]"
      :total="pagination.total"
      layout="total, sizes, prev, pager, next, jumper"
      @size-change="load"
      @current-change="load"
    />

    <!-- 任务详情对话框 -->
    <el-dialog
      v-model="detailVisible"
      title="任务详情"
      width="680px"
      class="detail-dialog"
    >
      <el-descriptions :column="1" border size="default">
        <el-descriptions-item
          v-for="(value, key) in detail"
          :key="key"
          :label="formatLabel(key)"
          label-align="right"
          label-class-name="detail-label"
        >
          <pre class="detail-value">{{ formatValue(value) }}</pre>
        </el-descriptions-item>
      </el-descriptions>
    </el-dialog>

    <!-- 日志抽屉 -->
    <el-drawer
      v-model="logVisible"
      title="链路日志"
      size="72%"
      class="log-drawer"
    >
      <!-- 日志过滤器 -->
      <div class="log-filters">
        <el-select v-model="logFilter.systemType" clearable placeholder="系统类型" class="log-filter-item">
          <el-option label="Python" value="python"/>
          <el-option label="Java" value="java"/>
        </el-select>
        <el-date-picker
          v-model="logFilter.timeRange"
          type="datetimerange"
          range-separator="至"
          start-placeholder="开始时间"
          end-placeholder="结束时间"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="log-filter-item log-filter-date"
        />
        <el-input
          v-model="logFilter.keyword"
          clearable
          placeholder="关键字搜索"
          class="log-filter-item log-filter-input"
          @keyup.enter="filterLogs"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" @click="filterLogs" :loading="logLoading">筛选</el-button>
        <el-button @click="resetLogFilter">重置</el-button>
      </div>

      <!-- 日志时间线 -->
      <div class="log-timeline" v-loading="logLoading">
        <div v-for="item in filteredLogs" :key="item.id" class="log-entry">
          <div class="log-meta">
            <span class="log-time">{{ item.logged_at }}</span>
            <el-tag :type="levelType(item.level)" size="small" class="log-level">
              {{ item.level }}
            </el-tag>
            <el-tag type="info" size="small" class="log-source">{{ item.source }}</el-tag>
          </div>
          <div class="log-message">
            <code>{{ item.message }}</code>
          </div>
        </div>
        <el-empty v-if="!filteredLogs.length" description="暂无日志"/>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed, onUnmounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Search } from '@element-plus/icons-vue'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const auth = useAuthStore()
const rows = ref([])
const logs = ref([])
const detail = ref({})
const logVisible = ref(false)
const detailVisible = ref(false)
const loading = ref(false)
const logLoading = ref(false)

const taskTypes = ref([])
const triggerEntries = ref([])
const dateRange = ref([])

const query = reactive({
  status: '',
  taskType: '',
  triggerEntry: '',
  logKeyword: '',
  onlyWithLogs: true
})

const pagination = reactive({
  page: 1,
  pageSize: 20,
  total: 0
})

const logFilter = reactive({
  systemType: '',
  timeRange: [],
  keyword: ''
})

const statuses = ['RESERVED', 'RUNNING', 'CANCEL_REQUESTED', 'CANCELLED', 'SUCCESS', 'FAILED']

let refreshTimer = null
let currentTraceId = null

/** 加载任务列表和筛选选项 */
async function load() {
  loading.value = true
  try {
    const params = {
      page: pagination.page,
      pageSize: pagination.pageSize
    }

    // 添加筛选参数
    if (query.status) params.status = query.status
    if (query.taskType) params.taskType = query.taskType
    if (query.triggerEntry) params.triggerEntry = query.triggerEntry
    if (query.logKeyword) params.logKeyword = query.logKeyword

    // onlyWithLogs 需要显式传递，包括 false 值
    params.onlyWithLogs = query.onlyWithLogs

    // 添加时间范围参数
    if (dateRange.value && dateRange.value.length === 2) {
      params.startTime = dateRange.value[0]
      params.endTime = dateRange.value[1]
    }

    const [traces, types, entries] = await Promise.all([
      http.get('/system/tasks', { params }),
      http.get('/system/tasks/task-types'),
      http.get('/system/tasks/trigger-entries')
    ])

    rows.value = traces.data.records || traces.data
    pagination.total = traces.data.total || traces.data.length
    taskTypes.value = types.data
    triggerEntries.value = entries.data
  } catch (error) {
    ElMessage.error('加载任务列表失败')
    console.error('加载任务列表错误:', error)
  } finally {
    loading.value = false
  }
}

function reset() {
  Object.assign(query, {
    status: '',
    taskType: '',
    triggerEntry: '',
    logKeyword: '',
    onlyWithLogs: true
  })
  dateRange.value = []
  pagination.page = 1
  load()
}

function manageable(row) {
  return ['RUNNING', 'CANCEL_REQUESTED'].includes(row.status) && auth.hasPermission('system:task:manage')
}

function statusType(status) {
  return {
    SUCCESS: 'success',
    FAILED: 'danger',
    CANCELLED: 'info',
    CANCEL_REQUESTED: 'warning',
    RUNNING: 'primary'
  }[status] || 'info'
}

function levelType(level) {
  return {
    ERROR: 'danger',
    WARN: 'warning',
    INFO: 'info',
    DEBUG: 'info'
  }[level] || 'info'
}

/** 展示任务详情 */
async function showDetail(row) {
  try {
    const response = await http.get(`/system/tasks/${row.trace_id}`)
    detail.value = response.data
    detailVisible.value = true
  } catch (error) {
    ElMessage.error('加载任务详情失败')
  }
}

/** 查询链路日志 */
async function showLogs(traceId) {
  currentTraceId = traceId
  logLoading.value = true
  logVisible.value = true

  try {
    await loadLogs()
    // 启动自动刷新
    startLogRefresh()
  } catch (error) {
    ElMessage.error('加载日志失败')
  } finally {
    logLoading.value = false
  }
}

/** 加载日志数据 */
async function loadLogs() {
  if (!currentTraceId) return

  const params = {}
  if (logFilter.systemType) params.systemType = logFilter.systemType
  if (logFilter.timeRange && logFilter.timeRange.length === 2) {
    params.startTime = logFilter.timeRange[0]
    params.endTime = logFilter.timeRange[1]
  }
  if (logFilter.keyword) params.keyword = logFilter.keyword

  const response = await http.get(`/system/tasks/${currentTraceId}/logs`, { params })
  // 逆序排列（最新的在前）
  logs.value = (response.data || []).reverse()
}

/** 启动日志自动刷新 */
function startLogRefresh() {
  stopLogRefresh()
  refreshTimer = setInterval(async () => {
    if (logVisible.value && currentTraceId) {
      try {
        await loadLogs()
      } catch (error) {
        console.error('自动刷新日志失败', error)
      }
    }
  }, 5000) // 每5秒刷新一次
}

/** 停止日志自动刷新 */
function stopLogRefresh() {
  if (refreshTimer) {
    clearInterval(refreshTimer)
    refreshTimer = null
  }
}

/** 过滤日志 */
async function filterLogs() {
  logLoading.value = true
  try {
    await loadLogs()
  } catch (error) {
    ElMessage.error('筛选日志失败')
  } finally {
    logLoading.value = false
  }
}

/** 重置日志过滤器 */
async function resetLogFilter() {
  Object.assign(logFilter, {
    systemType: '',
    timeRange: [],
    keyword: ''
  })
  logLoading.value = true
  try {
    await loadLogs()
  } catch (error) {
    ElMessage.error('重置日志筛选失败')
  } finally {
    logLoading.value = false
  }
}

/** 计算过滤后的日志 - 前端本地过滤作为备选 */
const filteredLogs = computed(() => {
  let result = logs.value

  // 如果后端不支持过滤，在前端进行本地过滤
  if (logFilter.keyword && result.length > 0) {
    const keyword = logFilter.keyword.toLowerCase()
    result = result.filter(log =>
      log.message?.toLowerCase().includes(keyword) ||
      log.source?.toLowerCase().includes(keyword)
    )
  }

  return result
})

/** 格式化字段标签 */
function formatLabel(key) {
  const labelMap = {
    trace_id: 'Trace ID',
    task_type: '任务类型',
    trigger_entry: '触发入口',
    status: '状态',
    started_at: '开始时间',
    completed_at: '完成时间',
    created_at: '创建时间',
    updated_at: '更新时间',
    error_message: '错误信息',
    cancel_reason: '取消原因'
  }
  return labelMap[key] || key
}

/** 格式化字段值 */
function formatValue(value) {
  if (value === null || value === undefined) return '-'
  if (typeof value === 'object') return JSON.stringify(value, null, 2)
  return value
}

/** 请求协作取消任务 */
async function cancelTrace(traceId) {
  try {
    const { value } = await ElMessageBox.prompt('请输入取消原因', '取消任务', {
      inputValue: '用户请求取消'
    })
    await http.post(`/system/tasks/${traceId}/cancel`, { reason: value })
    ElMessage.success('已发送取消请求')
    load()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('取消任务失败')
    }
  }
}

/** 管理员强制中断任务 */
async function forceTrace(traceId) {
  try {
    await ElMessageBox.confirm('强制终止可能导致外部请求仍在处理中，是否继续？', '强制终止', {
      type: 'warning'
    })
    await http.post(`/system/tasks/${traceId}/force-terminate`, { reason: '管理员强制终止' })
    ElMessage.success('任务已终止')
    load()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error('强制终止失败')
    }
  }
}

// 监听抽屉关闭，停止自动刷新
const stopLogRefreshOnClose = () => {
  if (!logVisible.value) {
    stopLogRefresh()
    currentTraceId = null
    resetLogFilter()
  }
}

// 使用 watch 监听 logVisible 变化
import { watch } from 'vue'
watch(logVisible, stopLogRefreshOnClose)

onMounted(load)
onUnmounted(stopLogRefresh)
</script>

<style scoped>
.filter-section {
  margin-bottom: 20px;
  padding: 18px;
  background: #f8faff;
  border: 1px solid var(--app-border);
  border-radius: 10px;
}

.filter-row {
  display: flex;
  align-items: center;
  gap: 16px;
  flex-wrap: wrap;
}

.filter-row + .filter-row {
  margin-top: 14px;
}

.filter-item-select {
  width: 200px;
  flex-shrink: 0;
}

.filter-item-keyword {
  width: 260px;
  flex-shrink: 0;
}

.filter-item-date {
  flex: 1;
  min-width: 380px;
}

.filter-switch {
  flex-shrink: 0;
}

.filter-actions {
  display: flex;
  gap: 8px;
  margin-left: auto;
  flex-shrink: 0;
}

.tasks-table {
  margin-bottom: 16px;
}

.trace-id {
  font-family: 'Courier New', monospace;
  font-size: 12px;
}

.detail-dialog :deep(.el-descriptions__label) {
  width: 140px;
  font-weight: 600;
}

.detail-value {
  margin: 0;
  padding: 8px;
  background: #f5f7fa;
  border-radius: 4px;
  font-family: 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-all;
  max-height: 300px;
  overflow-y: auto;
}

.log-drawer :deep(.el-drawer__body) {
  padding: 20px;
  display: flex;
  flex-direction: column;
  gap: 16px;
}

.log-filters {
  display: flex;
  align-items: center;
  gap: 12px;
  flex-wrap: wrap;
  padding: 16px;
  background: #f8faff;
  border: 1px solid var(--app-border);
  border-radius: 10px;
}

.log-filter-item {
  width: 140px;
  flex-shrink: 0;
}

.log-filter-date {
  width: 360px;
  flex-shrink: 0;
}

.log-filter-input {
  width: 200px;
  flex-shrink: 0;
}

.log-timeline {
  flex: 1;
  overflow-y: auto;
  padding: 4px;
}

.log-entry {
  padding: 16px;
  margin-bottom: 12px;
  background: #ffffff;
  border: 1px solid var(--app-border);
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  transition: all 0.2s ease;
}

.log-entry:hover {
  border-color: var(--app-primary);
  box-shadow: 0 4px 12px rgba(53, 106, 230, 0.1);
}

.log-meta {
  display: flex;
  align-items: center;
  gap: 10px;
  margin-bottom: 10px;
  flex-wrap: wrap;
}

.log-time {
  font-size: 13px;
  color: var(--app-muted);
  font-family: 'Courier New', monospace;
  min-width: 160px;
}

.log-level,
.log-source {
  font-size: 12px;
}

.log-message {
  padding: 10px;
  background: #f5f7fa;
  border-radius: 6px;
  border-left: 3px solid var(--app-primary);
}

.log-message code {
  display: block;
  font-family: 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  color: #2c3e50;
  white-space: pre-wrap;
  word-break: break-word;
}

/* 响应式优化 */
@media (max-width: 900px) {
  .filter-section {
    padding: 12px;
  }

  .filter-row {
    gap: 12px;
  }

  .filter-item-select,
  .filter-item-keyword,
  .filter-item-date {
    width: 100%;
    min-width: 100%;
  }

  .filter-switch {
    width: 100%;
  }

  .filter-actions {
    width: 100%;
    margin-left: 0;
  }

  .filter-actions .el-button {
    flex: 1;
  }

  .log-filters {
    padding: 12px;
  }

  .log-filter-item,
  .log-filter-date,
  .log-filter-input {
    width: 100%;
  }

  .log-meta {
    flex-direction: column;
    align-items: flex-start;
  }

  .log-time {
    min-width: auto;
  }
}
</style>
