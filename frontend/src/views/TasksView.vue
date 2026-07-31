<template>
  <div class="panel">
    <div class="section-head">
      <div>
        <h2>{{ t('tasks.title') }}</h2>
        <p>{{ t('tasks.description') }}</p>
      </div>
      <el-button @click="load">{{ t('common.refresh') }}</el-button>
    </div>

    <!-- 筛选器 -->
    <div class="filter-section">
      <!-- 第一行：任务状态、任务类型、触发入口、仅显示有日志开关、按钮 -->
      <div class="filter-row">
        <el-select v-model="query.status" clearable :placeholder="t('tasks.taskStatus')" class="filter-item-select">
          <el-option v-for="item in statuses" :key="item" :label="t(`tasks.statuses.${item}`)" :value="item"/>
        </el-select>
        <el-select v-model="query.taskType" clearable filterable :placeholder="t('tasks.taskType')" class="filter-item-select">
          <el-option v-for="item in taskTypes" :key="item" :label="localizeTaskType(item, t)" :value="item"/>
        </el-select>
        <el-select v-model="query.triggerEntry" clearable :placeholder="t('tasks.triggerEntry')" class="filter-item-select">
          <el-option v-for="item in triggerEntries" :key="item" :label="item" :value="item"/>
        </el-select>
        <el-switch
          v-model="query.onlyWithLogs"
          :active-text="t('tasks.onlyWithLogs')"
          class="filter-switch"
        />
        <div class="filter-actions">
          <el-button type="primary" @click="load" :loading="loading">{{ t('common.query') }}</el-button>
          <el-button @click="reset">{{ t('common.reset') }}</el-button>
        </div>
      </div>
      <!-- 第二行：关键字和时间 -->
      <div class="filter-row">
        <el-input
          v-model="query.logKeyword"
          clearable
          :placeholder="t('tasks.logKeyword')"
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
          range-separator="~"
          :start-placeholder="t('tasks.startTime')"
          :end-placeholder="t('tasks.endTime')"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="filter-item-date"
        />
      </div>
    </div>

    <!-- 数据表格 -->
    <el-table :data="rows" v-loading="loading" class="tasks-table" table-layout="auto">
      <el-table-column prop="trace_id" :label="t('tasks.traceId')" min-width="300">
        <template #default="scope">
          <el-text class="trace-id" truncated>{{ scope.row.trace_id }}</el-text>
        </template>
      </el-table-column>
      <el-table-column :label="t('tasks.taskType')" min-width="160">
        <template #default="scope">{{ localizeTaskType(scope.row.task_type, t) }}</template>
      </el-table-column>
      <el-table-column prop="trigger_entry" :label="t('tasks.entry')" width="120"/>
      <el-table-column :label="t('tasks.status')" width="145">
        <template #default="s">
          <el-tag :type="statusType(s.row.status)" effect="light">
            {{ t(`tasks.statuses.${s.row.status}`) }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="created_at" :label="t('tasks.createdAt')" min-width="180"/>
      <el-table-column prop="finished_at" :label="t('tasks.finishedAt')" min-width="180"/>
      <el-table-column :label="t('common.actions')" width="320" fixed="right">
        <template #default="s">
          <div class="table-actions">
            <el-button link type="primary" @click="showDetail(s.row)">{{ t('common.detail') }}</el-button>
            <el-button link type="primary" @click="showLogs(s.row.trace_id)">{{ t('tasks.logs') }}</el-button>
            <el-button v-if="manageable(s.row)" link type="warning" @click="cancelTrace(s.row.trace_id)">{{ t('tasks.cancel') }}</el-button>
            <el-button v-if="auth.isAdmin && manageable(s.row)" link type="danger" @click="forceTrace(s.row.trace_id)">{{ t('tasks.forceTerminate') }}</el-button>
          </div>
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
      :title="t('tasks.detail')"
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
      :title="t('tasks.log.title')"
      size="72%"
      class="log-drawer"
    >
      <!-- 日志过滤器 -->
      <div class="log-filters">
        <el-select v-model="logFilter.systemType" clearable :placeholder="t('tasks.log.systemType')" class="log-filter-item">
          <el-option label="Python" value="python"/>
          <el-option label="Java" value="java"/>
        </el-select>
        <el-date-picker
          v-model="logFilter.timeRange"
          type="datetimerange"
          range-separator="~"
          :start-placeholder="t('tasks.startTime')"
          :end-placeholder="t('tasks.endTime')"
          value-format="YYYY-MM-DD HH:mm:ss"
          class="log-filter-item log-filter-date"
        />
        <el-input
          v-model="logFilter.keyword"
          clearable
          :placeholder="t('tasks.log.keyword')"
          class="log-filter-item log-filter-input"
          @keyup.enter="filterLogs"
        >
          <template #prefix>
            <el-icon><Search /></el-icon>
          </template>
        </el-input>
        <el-button type="primary" @click="filterLogs" :loading="logLoading">{{ t('tasks.log.filter') }}</el-button>
        <el-button @click="resetLogFilter">{{ t('tasks.log.reset') }}</el-button>
      </div>

      <!-- 日志时间线 -->
      <div class="log-timeline" v-loading="logLoading">
        <div class="log-list-summary">
          <span class="log-list-count">{{ filteredLogs.length }}</span>
          <span>{{ t('tasks.log.title') }}</span>
        </div>
        <div
          v-for="item in displayedLogs"
          :key="item.id"
          class="log-entry"
          :class="`log-entry--${String(item.level || 'info').toLowerCase()}`"
        >
          <div class="log-entry-head">
            <div class="log-meta">
              <span class="log-time">{{ item.logged_at }}</span>
              <el-tag :type="levelType(item.level)" size="small" effect="dark" class="log-level">
                {{ item.level }}
              </el-tag>
              <el-tag type="info" size="small" effect="plain" class="log-source">{{ item.source }}</el-tag>
            </div>
            <div v-if="item.logger_name || item.thread_name" class="log-context">
              <span v-if="item.logger_name">{{ item.logger_name }}</span>
              <span v-if="item.thread_name">{{ item.thread_name }}</span>
            </div>
          </div>
          <div v-if="item.displayFields.length" class="log-fields">
            <div v-for="(field, fieldIndex) in item.displayFields" :key="`${field.name}-${fieldIndex}`" class="log-field">
              <div class="log-field-head">
                <span class="log-field-name">{{ field.name }}:</span>
                <el-button
                  link
                  type="primary"
                  :icon="CopyDocument"
                  class="log-field-copy"
                  @click="copyLogValue(logFieldCopyValue(field, logFieldKey(item, fieldIndex)))"
                >
                  {{ t('tasks.log.copy') }}
                </el-button>
              </div>
              <details
                v-if="field.isJson"
                class="log-field-json"
                @toggle="setJsonFieldExpanded(logFieldKey(item, fieldIndex), $event.target.open)"
              >
                <summary><code>{{ field.compactValue }}</code></summary>
                <pre>{{ field.displayValue }}</pre>
              </details>
              <code v-else class="log-field-value">{{ field.displayValue || '-' }}</code>
            </div>
          </div>
          <div v-else class="log-message">
            <div class="log-field-head">
              <span class="log-field-name">{{ t('tasks.log.rawMessage') }}:</span>
              <el-button
                link
                type="primary"
                :icon="CopyDocument"
                class="log-field-copy"
                @click="copyLogValue(item.message)"
              >
                {{ t('tasks.log.copy') }}
              </el-button>
            </div>
            <code>{{ item.message }}</code>
          </div>
          <details v-if="item.throwable" class="log-throwable">
            <summary>{{ firstThrowableLine(item.throwable) }}</summary>
            <pre>{{ item.throwable }}</pre>
          </details>
        </div>
        <el-empty v-if="!filteredLogs.length" :description="t('tasks.log.noLogs')"/>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { onMounted, reactive, ref, computed, onUnmounted, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { CopyDocument, Search } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { localizeTaskType } from '../utils/localization'
import { parseTaskLogFields } from '../utils/taskLogDisplay'

const { t } = useI18n()
const auth = useAuthStore()
const rows = ref([])
const logs = ref([])
const detail = ref({})
const logVisible = ref(false)
const detailVisible = ref(false)
const loading = ref(false)
const logLoading = ref(false)
const expandedJsonFields = reactive(new Set())

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
    ElMessage.error(t('tasks.loadFailed'))
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

/** 提取异常首行作为折叠摘要 */
function firstThrowableLine(throwable) {
  return String(throwable).split('\n')[0]
}

/** 展示任务详情 */
async function showDetail(row) {
  try {
    const response = await http.get(`/system/tasks/${row.trace_id}`)
    detail.value = response.data
    detailVisible.value = true
  } catch (error) {
    ElMessage.error(t('tasks.loadDetailFailed'))
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
    ElMessage.error(t('tasks.loadLogsFailed'))
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
    ElMessage.error(t('tasks.filterFailed'))
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
    ElMessage.error(t('tasks.resetFilterFailed'))
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

/** 为日志记录补充结构化字段，供展示层逐项渲染和复制。 */
const displayedLogs = computed(() => filteredLogs.value.map(log => ({
  ...log,
  displayFields: parseTaskLogFields(log.message)
})))

/** 生成日志字段在当前抽屉中的稳定状态键。 */
function logFieldKey(item, fieldIndex) {
  return `${item.id ?? item.logged_at ?? 'log'}:${fieldIndex}`
}

/** 记录 JSON 字段当前是否展开，以决定复制紧凑或格式化内容。 */
function setJsonFieldExpanded(fieldKey, expanded) {
  if (expanded) expandedJsonFields.add(fieldKey)
  else expandedJsonFields.delete(fieldKey)
}

/** 根据 JSON 字段当前展示状态返回对应的复制内容。 */
function logFieldCopyValue(field, fieldKey) {
  if (!field.isJson || expandedJsonFields.has(fieldKey)) return field.displayValue
  return field.compactValue
}

/** 复制日志字段内容，并在浏览器不支持 Clipboard API 时降级处理。 */
async function copyLogValue(value) {
  const text = String(value ?? '')
  try {
    if (navigator.clipboard?.writeText) {
      await navigator.clipboard.writeText(text)
    } else {
      const textarea = document.createElement('textarea')
      try {
        textarea.value = text
        textarea.style.position = 'fixed'
        textarea.style.opacity = '0'
        document.body.appendChild(textarea)
        textarea.select()
        if (!document.execCommand('copy')) throw new Error('COPY_FAILED')
      } finally {
        textarea.remove()
      }
    }
    ElMessage.success(t('tasks.log.copySuccess'))
  } catch {
    ElMessage.error(t('tasks.log.copyFailed'))
  }
}

/** 格式化字段标签 */
function formatLabel(key) {
  const labelMap = {
    trace_id: t('tasks.traceId'),
    task_type: t('tasks.taskType'),
    trigger_entry: t('tasks.triggerEntry'),
    status: t('tasks.status'),
    started_at: t('tasks.startedAt'),
    completed_at: t('tasks.completedAt'),
    created_at: t('tasks.createdAt'),
    updated_at: t('tasks.updatedAt'),
    error_message: t('tasks.errorMessage'),
    cancel_reason: t('tasks.cancelReason')
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
    const { value } = await ElMessageBox.prompt(t('tasks.cancelReason_input'), t('tasks.cancelTask'), {
      inputValue: t('tasks.cancelReason_default')
    })
    await http.post(`/system/tasks/${traceId}/cancel`, { reason: value })
    ElMessage.success(t('tasks.cancelRequested'))
    load()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(t('tasks.cancelFailed'))
    }
  }
}

/** 管理员强制中断任务 */
async function forceTrace(traceId) {
  try {
    await ElMessageBox.confirm(t('tasks.forceTerminateConfirm'), t('tasks.forceTerminateTitle'), {
      type: 'warning'
    })
    await http.post(`/system/tasks/${traceId}/force-terminate`, { reason: t('tasks.forceTerminateReason') })
    ElMessage.success(t('tasks.taskTerminated'))
    load()
  } catch (error) {
    if (error !== 'cancel') {
      ElMessage.error(t('tasks.forceTerminateFailed'))
    }
  }
}

// 监听抽屉关闭，停止自动刷新
const stopLogRefreshOnClose = () => {
  if (!logVisible.value) {
    stopLogRefresh()
    currentTraceId = null
    expandedJsonFields.clear()
    resetLogFilter()
  }
}

// 使用 watch 监听 logVisible 变化
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
  padding: 4px 8px 4px 4px;
}

.log-list-summary {
  position: sticky;
  top: 0;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 8px;
  margin-bottom: 12px;
  padding: 10px 12px;
  color: var(--app-muted);
  background: rgba(255, 255, 255, 0.94);
  border-bottom: 1px solid var(--app-border);
  backdrop-filter: blur(8px);
}

.log-list-count {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 28px;
  height: 28px;
  padding: 0 8px;
  color: var(--app-primary);
  font-weight: 700;
  background: #eef4ff;
  border-radius: 999px;
}

.log-entry {
  position: relative;
  padding: 0;
  margin-bottom: 12px;
  background: #ffffff;
  border: 1px solid var(--app-border);
  border-left: 4px solid #909399;
  border-radius: 8px;
  box-shadow: 0 2px 8px rgba(0, 0, 0, 0.04);
  overflow: hidden;
  transition: border-color 0.2s ease, box-shadow 0.2s ease;
}

.log-entry--error {
  border-left-color: #f56c6c;
}

.log-entry--warn {
  border-left-color: #e6a23c;
}

.log-entry--info {
  border-left-color: #409eff;
}

.log-entry--debug {
  border-left-color: #909399;
}

.log-entry:hover {
  border-top-color: #b9cdf8;
  border-right-color: #b9cdf8;
  border-bottom-color: #b9cdf8;
  box-shadow: 0 4px 12px rgba(53, 106, 230, 0.1);
}

.log-entry-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  padding: 12px 14px;
  background: #fbfcff;
  border-bottom: 1px solid #edf0f5;
}

.log-meta {
  display: flex;
  align-items: center;
  gap: 10px;
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

.log-context {
  display: flex;
  flex-direction: column;
  align-items: flex-end;
  gap: 3px;
  min-width: 0;
  color: var(--app-muted);
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.35;
}

.log-context span {
  max-width: 360px;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-message {
  padding: 14px;
  background: #ffffff;
}

.log-fields {
  display: grid;
  gap: 12px;
  padding: 14px;
  background: #ffffff;
}

.log-field {
  min-width: 0;
  padding: 12px;
  background: #f8faff;
  border: 1px solid #e4eaf4;
  border-radius: 7px;
}

.log-field-head {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 8px;
}

.log-field-name {
  min-width: 0;
  overflow-wrap: anywhere;
  color: #526079;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  font-weight: 700;
}

.log-field-copy {
  flex: 0 0 auto;
}

.log-field-value,
.log-field-json summary code {
  display: block;
  overflow-wrap: anywhere;
  color: #2c3e50;
  font-family: 'Courier New', monospace;
  font-size: 13px;
  line-height: 1.6;
  white-space: pre-wrap;
}

.log-field-json {
  overflow: hidden;
  background: #ffffff;
  border: 1px solid #dfe6f1;
  border-radius: 6px;
}

.log-field-json summary {
  position: relative;
  padding: 10px 36px 10px 12px;
  cursor: pointer;
  list-style: none;
}

.log-field-json summary::-webkit-details-marker {
  display: none;
}

.log-field-json summary::after {
  position: absolute;
  top: 50%;
  right: 13px;
  color: var(--app-muted);
  content: '›';
  font-size: 18px;
  transform: translateY(-50%) rotate(90deg);
  transition: transform 0.2s ease;
}

.log-field-json[open] summary::after {
  transform: translateY(-50%) rotate(-90deg);
}

.log-field-json summary code {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.log-field-json pre {
  max-height: 360px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  color: #dce7fa;
  background: #182338;
  border-top: 1px solid #dfe6f1;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre;
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

.log-throwable {
  margin: 0 14px 14px;
  color: #a63d3d;
  background: #fff6f6;
  border: 1px solid #f5c2c2;
  border-radius: 6px;
}

.log-throwable summary {
  padding: 10px 12px;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  font-weight: 600;
  cursor: pointer;
  word-break: break-word;
}

.log-throwable pre {
  max-height: 320px;
  margin: 0;
  padding: 12px;
  overflow: auto;
  color: #7c2d2d;
  font-family: 'Courier New', monospace;
  font-size: 12px;
  line-height: 1.55;
  white-space: pre-wrap;
  word-break: break-word;
  border-top: 1px solid #f5c2c2;
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

  .log-entry-head,
  .log-meta {
    flex-direction: column;
    align-items: flex-start;
  }

  .log-context {
    align-items: flex-start;
    width: 100%;
  }

  .log-context span {
    max-width: 100%;
  }

  .log-time {
    min-width: auto;
  }

  .log-field-head {
    align-items: flex-start;
  }
}
</style>
