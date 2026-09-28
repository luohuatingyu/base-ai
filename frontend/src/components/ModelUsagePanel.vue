<template>
  <section v-if="auth.isAdmin" class="panel model-usage-panel">
    <div class="section-head">
      <div class="model-usage-heading">
        <span class="eyebrow">{{ t('modelUsage.eyebrow') }}</span>
        <h2>{{ t('modelUsage.title') }}</h2>
        <p>{{ t('modelUsage.description') }}</p>
      </div>
      <div class="model-usage-actions">
        <span v-if="lastLoadedAt" class="usage-updated" aria-live="polite">{{ t('modelUsage.updatedAt', { time: lastLoadedAt }) }}</span>
        <el-button :icon="Refresh" :loading="loading" :disabled="recordsLoading" @click="refresh">{{ t('common.refresh') }}</el-button>
      </div>
    </div>

    <form class="model-usage-filters" @submit.prevent="load">
      <label class="usage-date-field"><span>{{ t('modelUsage.timeRange') }}</span>
        <el-date-picker v-model="dateRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
          :start-placeholder="t('modelUsage.startTime')" :end-placeholder="t('modelUsage.endTime')" />
      </label>
      <label><span>{{ t('modelUsage.provider') }}</span><el-input v-model="filters.providerCode" clearable :placeholder="t('modelUsage.allProviders')" /></label>
      <label><span>{{ t('modelUsage.model') }}</span><el-input v-model="filters.modelName" clearable :placeholder="t('modelUsage.allModels')" /></label>
      <label><span>{{ t('modelUsage.source') }}</span>
        <el-select v-model="filters.source" clearable :placeholder="t('modelUsage.allSources')" :aria-label="t('modelUsage.source')">
          <el-option v-for="item in sources" :key="item" :value="item" :label="t(`modelUsage.sources.${item}`)" />
        </el-select>
      </label>
      <label><span>{{ t('modelUsage.status') }}</span>
        <el-select v-model="filters.status" clearable :placeholder="t('modelUsage.allStatuses')" :aria-label="t('modelUsage.status')">
          <el-option v-for="item in statuses" :key="item" :value="item" :label="t(`modelUsage.statuses.${item}`)" />
        </el-select>
      </label>
      <div class="filter-buttons">
        <el-button native-type="submit" type="primary" :icon="Search" :loading="loading" :disabled="recordsLoading">{{ t('common.query') }}</el-button>
        <el-button :disabled="loading || recordsLoading" @click="reset">{{ t('common.reset') }}</el-button>
      </div>
    </form>
    <el-alert v-if="rangeError" :title="t('modelUsage.invalidRange')" type="warning" show-icon :closable="false" />
    <div class="usage-scope"><span>{{ t('modelUsage.appliedRange') }} · {{ scopeLabel }}</span><span>{{ t('modelUsage.autoRefresh') }}</span></div>
    <el-alert v-if="error" :title="t('modelUsage.loadFailed')" type="error" show-icon :closable="false" />

    <div class="metric-grid model-usage-metrics" :aria-busy="loading">
      <article v-for="item in metrics" :key="item.key" class="metric" :class="`usage-metric-${item.key}`">
        <div class="usage-metric-label"><span>{{ t(`modelUsage.${item.key}`) }}</span><el-icon aria-hidden="true"><component :is="item.icon" /></el-icon></div>
        <el-skeleton v-if="loading" animated :rows="0" />
        <b v-else>{{ item.value }}</b>
        <span class="usage-metric-hint">{{ t(`modelUsage.${item.key}Hint`) }}</span>
      </article>
    </div>

    <div class="model-usage-columns" v-loading="loading">
      <div class="usage-data-card">
        <div class="usage-card-heading"><h3>{{ t('modelUsage.modelRanking') }}</h3><span>{{ t('modelUsage.rankingHint') }}</span></div>
        <el-table v-if="models.length" :data="models" size="small" stripe>
          <el-table-column prop="providerCode" :label="t('modelUsage.provider')" min-width="110" show-overflow-tooltip />
          <el-table-column prop="modelName" :label="t('modelUsage.model')" min-width="150" show-overflow-tooltip />
          <el-table-column :label="t('modelUsage.calls')" width="90" align="right"><template #default="scope">{{ number(scope.row.calls) }}</template></el-table-column>
          <el-table-column :label="t('modelUsage.totalTokens')" width="120" align="right"><template #default="scope">{{ number(scope.row.totalTokens) }}</template></el-table-column>
          <el-table-column :label="t('modelUsage.successRate')" width="110" align="right">
            <template #default="scope">{{ formatSuccessRate(scope.row.success, scope.row.calls, locale) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-else-if="!loading && !error" :image-size="64" :description="t('modelUsage.empty')" />
        <p v-else-if="error" class="usage-unavailable">{{ t('modelUsage.unavailable') }}</p>
      </div>
      <div class="usage-data-card">
        <div class="usage-card-heading"><h3>{{ t('modelUsage.errors') }}</h3><span>{{ t('modelUsage.errorsHint') }}</span></div>
        <el-table v-if="errors.length" :data="errors" size="small" stripe>
          <el-table-column prop="errorType" :label="t('modelUsage.errorType')" min-width="130" show-overflow-tooltip />
          <el-table-column prop="errorCode" :label="t('modelUsage.errorCode')" min-width="150" show-overflow-tooltip />
          <el-table-column :label="t('modelUsage.count')" width="90" align="right"><template #default="scope">{{ number(scope.row.count) }}</template></el-table-column>
        </el-table>
        <el-empty v-else-if="!loading && !error" :image-size="64" :description="t('modelUsage.noErrors')" />
        <p v-else-if="error" class="usage-unavailable">{{ t('modelUsage.unavailable') }}</p>
      </div>
    </div>

    <div class="model-usage-records" v-loading="recordsLoading">
      <div class="usage-card-heading"><h3>{{ t('modelUsage.records') }}</h3><span>{{ t('modelUsage.recordsHint') }}</span></div>
      <el-alert v-if="recordsError" :title="t('modelUsage.recordsFailed')" type="error" show-icon :closable="false">
        <el-button text type="primary" @click="loadRecords">{{ t('common.refresh') }}</el-button>
      </el-alert>
      <el-table v-if="records.length" :data="records" size="small" stripe>
        <el-table-column prop="startedAt" :label="t('modelUsage.time')" min-width="170" />
        <el-table-column :label="t('modelUsage.source')" width="120"><template #default="scope">{{ sourceLabel(scope.row.source) }}</template></el-table-column>
        <el-table-column prop="providerCode" :label="t('modelUsage.provider')" min-width="120" show-overflow-tooltip />
        <el-table-column prop="modelName" :label="t('modelUsage.model')" min-width="180" show-overflow-tooltip />
        <el-table-column :label="t('modelUsage.status')" width="110">
          <template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small" effect="light">{{ statusLabel(scope.row.status) }}</el-tag></template>
        </el-table-column>
        <el-table-column :label="t('modelUsage.inputTokens')" width="110" align="right"><template #default="scope">{{ number(scope.row.inputTokens) }}</template></el-table-column>
        <el-table-column :label="t('modelUsage.outputTokens')" width="110" align="right"><template #default="scope">{{ number(scope.row.outputTokens) }}</template></el-table-column>
        <el-table-column :label="t('modelUsage.duration')" width="140" align="right"><template #default="scope">{{ milliseconds(scope.row.durationMs) }}</template></el-table-column>
        <el-table-column prop="traceId" :label="t('modelUsage.traceId')" min-width="220" show-overflow-tooltip />
      </el-table>
      <el-empty v-else-if="!loading && !recordsLoading && !error && !recordsError" :image-size="64" :description="t('modelUsage.empty')" />
      <el-pagination v-if="pagination.total > 0 && !recordsError" v-model:current-page="pagination.page" v-model:page-size="pagination.pageSize"
        :disabled="loading || recordsLoading" :page-sizes="[10, 20, 50, 100]" :total="pagination.total" :pager-count="5"
        layout="total, sizes, prev, pager, next" @change="loadRecords" />
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { CircleCheck, Clock, Coin, DataAnalysis, Loading, Refresh, Search, Timer } from '@element-plus/icons-vue'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { formatCount, formatMilliseconds, formatSuccessRate } from '../utils/dashboardMetrics'

const { t, locale } = useI18n()
const auth = useAuthStore()
const loading = ref(false)
const recordsLoading = ref(false)
const error = ref(false)
const recordsError = ref(false)
const rangeError = ref(false)
const lastLoadedAt = ref('')
const dateRange = ref([])
const filters = reactive({ providerCode: '', modelName: '', source: '', status: '' })
const overview = ref({})
const models = ref([])
const errors = ref([])
const records = ref([])
const pagination = reactive({ page: 1, pageSize: 20, total: 0 })
const sources = ['CHAT', 'STREAM', 'CHAT_STREAM', 'EMBEDDING', 'AGENT']
const statuses = ['SUCCESS', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'RUNNING']
const appliedParams = ref({})
const recordParams = ref(null)
let timer
let disposed = false

const scopeLabel = computed(() => {
  const params = appliedParams.value
  return params.from ? `${new Date(params.from).toLocaleString(locale.value)} – ${new Date(params.to).toLocaleString(locale.value)}` : t('dashboard.last24Hours')
})
const metrics = computed(() => [
  { key: 'calls', value: number(overview.value.calls), icon: DataAnalysis },
  { key: 'successRate', value: formatSuccessRate(overview.value.success, overview.value.calls, locale.value), icon: CircleCheck },
  { key: 'totalTokens', value: number(overview.value.totalTokens), icon: Coin },
  { key: 'firstToken', value: milliseconds(overview.value.averageFirstTokenLatencyMs), icon: Timer },
  { key: 'duration', value: milliseconds(overview.value.averageDurationMs), icon: Clock },
  { key: 'running', value: number(overview.value.calls === 0 ? 0 : overview.value.running), icon: Loading }
])

/** 验证日期范围并生成查询快照，避免未提交的筛选条件混入自动刷新和分页。 */
function rangeParams() {
  const params = Object.fromEntries(Object.entries(filters).map(([key, value]) => [key, value.trim()]).filter(([, value]) => value))
  if (dateRange.value?.length) {
    const [start, end] = dateRange.value.map(value => new Date(value).getTime())
    if (!Number.isFinite(start) || !Number.isFinite(end) || start >= end || end - start > 90 * 86400000) throw new Error('Invalid range')
    params.from = new Date(start).toISOString()
    params.to = new Date(end).toISOString()
  }
  return params
}

/** 应用用户提交的筛选条件，并从第一页查询。 */
async function load() {
  if (!auth.isAdmin || loading.value || recordsLoading.value) return
  rangeError.value = false
  try {
    appliedParams.value = rangeParams()
  } catch {
    rangeError.value = true
    return
  }
  pagination.page = 1
  await refresh()
}

/** 刷新已应用条件，保留当前页；失败时清空旧结果，避免误标为当前数据。 */
async function refresh() {
  if (!auth.isAdmin || loading.value || recordsLoading.value || disposed) return
  loading.value = true
  error.value = false
  recordsError.value = false
  recordParams.value = null
  try {
    const { data } = await http.get('/operations/llm-usage/summary', { params: { ...appliedParams.value }, silentError: true })
    if (disposed) return
    overview.value = data.overview || {}
    models.value = data.models || []
    errors.value = data.errors || []
    // 汇总和明细共用服务端返回的实际时间边界，避免两次请求的默认窗口偏移。
    recordParams.value = { ...appliedParams.value, ...(data.from && data.to ? { from: data.from, to: data.to } : {}) }
    lastLoadedAt.value = new Date().toLocaleTimeString(locale.value)
    await loadRecords()
  } catch {
    if (disposed) return
    overview.value = {}
    models.value = []
    errors.value = []
    records.value = []
    pagination.total = 0
    lastLoadedAt.value = ''
    error.value = true
  } finally {
    loading.value = false
  }
}

/** 分页只使用已成功加载的汇总条件，并独立提示明细请求失败。 */
async function loadRecords() {
  if (!auth.isAdmin || !recordParams.value || recordsLoading.value || disposed) return
  recordsLoading.value = true
  recordsError.value = false
  try {
    const params = { ...recordParams.value, page: pagination.page, pageSize: pagination.pageSize }
    const { data } = await http.get('/operations/llm-usage/records', { params, silentError: true })
    if (disposed) return
    records.value = data.records || []
    pagination.total = data.total || 0
  } catch {
    if (disposed) return
    records.value = []
    recordsError.value = true
  } finally {
    recordsLoading.value = false
  }
}

/** 清除筛选并恢复默认近 24 小时窗口。 */
async function reset() {
  if (loading.value || recordsLoading.value) return
  dateRange.value = []
  Object.assign(filters, { providerCode: '', modelName: '', source: '', status: '' })
  await load()
}

/** 使用当前语言格式化计数。 */
function number(value) { return formatCount(value, locale.value) }
/** 缺失或非法延迟使用占位符，合法延迟保留毫秒单位。 */
function milliseconds(value) { return formatMilliseconds(value, locale.value) }
/** 每个调用状态同时用颜色与文本表达，未知值保留中性样式。 */
function statusType(status) { return { SUCCESS: 'success', FAILED: 'danger', INTERRUPTED: 'warning', CANCELLED: 'info', RUNNING: 'primary' }[status] || 'info' }
/** 已知来源翻译为当前语言，兼容后端扩展的来源名称。 */
function sourceLabel(source) { return sources.includes(source) ? t(`modelUsage.sources.${source}`) : source || '—' }
/** 已知状态翻译为当前语言，未知状态不显示翻译键。 */
function statusLabel(status) { return statuses.includes(status) ? t(`modelUsage.statuses.${status}`) : status || '—' }

onMounted(() => {
  if (!auth.isAdmin) return
  load()
  timer = window.setInterval(refresh, 30000)
})
onUnmounted(() => {
  disposed = true
  if (timer) window.clearInterval(timer)
})
</script>

<style scoped>
.model-usage-panel { margin-top: 24px; }
.model-usage-panel .section-head { align-items: flex-start; justify-content: space-between; gap: 16px; }
.model-usage-panel .section-head > .model-usage-heading { display: block; }
.model-usage-heading .eyebrow { color: var(--app-primary); }
.model-usage-heading h2 { margin: 8px 0 6px; font-size: 20px; }
.model-usage-heading p { max-width: 720px; margin: 0; color: #56637a; line-height: 1.6; font-size: 13px; }
.model-usage-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 10px; }
.usage-updated { color: #56637a; font-size: 12px; }
.model-usage-filters { display: flex; align-items: flex-end; flex-wrap: wrap; gap: 14px 12px; margin: 20px 0 12px; padding: 16px; background: var(--app-canvas); border: 1px solid var(--app-border); border-radius: 10px; }
.model-usage-filters label { display: flex; flex-direction: column; gap: 7px; flex: 1 1 140px; min-width: 0; color: #56637a; font-size: 12px; }
.model-usage-filters .usage-date-field { flex: 2 1 360px; }
.model-usage-filters :deep(.el-date-editor) { width: 100%; min-width: 0; max-width: 100%; }
.model-usage-filters .el-input, .model-usage-filters .el-select { width: 100%; min-width: 0; }
.filter-buttons { display: flex; gap: 8px; }
.filter-buttons .el-button + .el-button { margin-left: 0; }
.usage-scope { display: flex; justify-content: space-between; flex-wrap: wrap; gap: 8px; margin: 14px 0; color: #56637a; font-size: 12px; }
.model-usage-metrics { grid-template-columns: repeat(3, minmax(0, 1fr)); margin: 16px 0 24px; gap: 12px; }
.model-usage-metrics .metric { min-width: 0; padding: 18px; background: #fbfcfe; box-shadow: none; }
.usage-metric-label { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.usage-metric-label span { color: #56637a; }
.usage-metric-label .el-icon { color: var(--app-primary); font-size: 18px; }
.model-usage-metrics .metric b { margin: 12px 0 6px; font-size: clamp(22px, 2vw, 28px); font-variant-numeric: tabular-nums; overflow-wrap: anywhere; }
.model-usage-metrics .usage-metric-successRate b, .usage-metric-successRate .el-icon { color: #17835e; }
.model-usage-metrics .usage-metric-hint { font-size: 12px; color: #56637a; }
.model-usage-metrics .el-skeleton { margin: 18px 0; }
.model-usage-columns { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 20px; margin-top: 24px; }
.model-usage-columns > div { min-width: 0; }
.usage-data-card { border: 1px solid var(--app-border); border-radius: 10px; overflow: hidden; }
.usage-card-heading { display: flex; flex-wrap: wrap; align-items: center; justify-content: space-between; gap: 8px; padding: 16px; border-bottom: 1px solid var(--app-border); }
.usage-card-heading h3 { margin: 0; font-size: 15px; }
.usage-card-heading > span { color: #56637a; font-size: 12px; }
.usage-unavailable { padding: 24px 16px; text-align: center; color: #56637a; font-size: 13px; }
.model-usage-records { min-width: 0; margin-top: 20px; border: 1px solid var(--app-border); border-radius: 10px; overflow: hidden; }
.model-usage-records .el-pagination { display: flex; flex-wrap: wrap; gap: 8px; padding: 16px; }
.model-usage-panel :deep(.el-table th.el-table__cell) { background: #f7f9fc; color: #56637a; font-weight: 500; }
.model-usage-panel :deep(.el-table .cell) { font-variant-numeric: tabular-nums; }
@media (max-width: 1100px) { .model-usage-panel .section-head { flex-wrap: wrap; } }
@media (max-width: 900px) {
  .model-usage-columns { grid-template-columns: minmax(0, 1fr); }
  .model-usage-filters label { flex-basis: 180px; }
}
@media (max-width: 600px) {
  .model-usage-panel .section-head { gap: 12px; }
  .model-usage-actions { width: 100%; justify-content: flex-start; }
  .model-usage-actions .el-button { margin-left: auto; }
  .model-usage-filters label, .model-usage-filters .usage-date-field { flex-basis: 100%; min-width: 0; }
  .model-usage-filters .el-date-editor, .model-usage-filters .el-input, .model-usage-filters .el-select { flex-basis: 100%; min-width: 100%; }
  .filter-buttons { width: 100%; }
  .filter-buttons .el-button { flex: 1; }
  .model-usage-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 10px; }
  .model-usage-metrics .metric { padding: 14px; }
  .usage-card-heading { padding: 14px; }
  .model-usage-records .el-pagination { padding: 10px; }
  .model-usage-filters :deep(.el-range-input) { min-width: 0; }
}
@media (max-width: 380px) { .model-usage-metrics { grid-template-columns: minmax(0, 1fr); } }
</style>
