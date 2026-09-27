<template>
  <section v-if="auth.isAdmin" class="panel model-usage-panel">
    <div class="section-head">
      <div>
        <span class="eyebrow">{{ t('modelUsage.eyebrow') }}</span>
        <h2>{{ t('modelUsage.title') }}</h2>
        <p>{{ t('modelUsage.description') }}</p>
      </div>
      <div class="model-usage-actions">
        <el-tag v-if="lastLoadedAt" type="info" effect="plain">{{ t('modelUsage.updatedAt', { time: lastLoadedAt }) }}</el-tag>
        <el-button :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
      </div>
    </div>

    <div class="model-usage-filters">
      <el-date-picker v-model="dateRange" type="datetimerange" value-format="YYYY-MM-DD HH:mm:ss"
        :start-placeholder="t('modelUsage.startTime')" :end-placeholder="t('modelUsage.endTime')" />
      <el-input v-model="filters.providerCode" clearable :placeholder="t('modelUsage.provider')" />
      <el-input v-model="filters.modelName" clearable :placeholder="t('modelUsage.model')" />
      <el-select v-model="filters.source" clearable :placeholder="t('modelUsage.source')">
        <el-option v-for="item in sources" :key="item" :value="item" :label="t(`modelUsage.sources.${item}`)" />
      </el-select>
      <el-select v-model="filters.status" clearable :placeholder="t('modelUsage.status')">
        <el-option v-for="item in statuses" :key="item" :value="item" :label="t(`modelUsage.statuses.${item}`)" />
      </el-select>
      <el-button type="primary" :loading="loading" @click="load">{{ t('common.query') }}</el-button>
      <el-button @click="reset">{{ t('common.reset') }}</el-button>
    </div>

    <el-alert v-if="error" :title="t('modelUsage.loadFailed')" type="error" show-icon :closable="false" />

    <div class="metric-grid model-usage-metrics" v-loading="loading">
      <div class="metric"><b>{{ number(overview.calls) }}</b><span>{{ t('modelUsage.calls') }}</span></div>
      <div class="metric"><b>{{ percent(successRate) }}</b><span>{{ t('modelUsage.successRate') }}</span></div>
      <div class="metric"><b>{{ number(overview.totalTokens) }}</b><span>{{ t('modelUsage.totalTokens') }}</span></div>
      <div class="metric"><b>{{ milliseconds(overview.averageFirstTokenLatencyMs) }}</b><span>{{ t('modelUsage.firstToken') }}</span></div>
      <div class="metric"><b>{{ milliseconds(overview.averageDurationMs) }}</b><span>{{ t('modelUsage.duration') }}</span></div>
      <div class="metric"><b>{{ number(overview.running) }}</b><span>{{ t('modelUsage.running') }}</span></div>
    </div>

    <div class="model-usage-columns" v-loading="loading">
      <div>
        <h3>{{ t('modelUsage.modelRanking') }}</h3>
        <el-table :data="models" size="small">
          <el-table-column prop="providerCode" :label="t('modelUsage.provider')" min-width="120" />
          <el-table-column prop="modelName" :label="t('modelUsage.model')" min-width="160" />
          <el-table-column prop="calls" :label="t('modelUsage.calls')" width="90" />
          <el-table-column prop="totalTokens" :label="t('modelUsage.totalTokens')" width="120" />
          <el-table-column :label="t('modelUsage.successRate')" width="110">
            <template #default="scope">{{ percent(rate(scope.row.success, scope.row.calls)) }}</template>
          </el-table-column>
        </el-table>
        <el-empty v-if="!models.length && !loading" :description="t('modelUsage.empty')" />
      </div>
      <div>
        <h3>{{ t('modelUsage.errors') }}</h3>
        <el-table :data="errors" size="small">
          <el-table-column prop="errorType" :label="t('modelUsage.errorType')" min-width="130" />
          <el-table-column prop="errorCode" :label="t('modelUsage.errorCode')" min-width="150" />
          <el-table-column prop="count" :label="t('modelUsage.count')" width="90" />
        </el-table>
        <el-empty v-if="!errors.length && !loading" :description="t('modelUsage.noErrors')" />
      </div>
    </div>

    <div class="model-usage-records">
      <h3>{{ t('modelUsage.records') }}</h3>
      <el-table :data="records" v-loading="loading" size="small" table-layout="auto">
        <el-table-column prop="startedAt" :label="t('modelUsage.time')" min-width="170" />
        <el-table-column prop="source" :label="t('modelUsage.source')" width="110" />
        <el-table-column prop="providerCode" :label="t('modelUsage.provider')" min-width="120" />
        <el-table-column prop="modelName" :label="t('modelUsage.model')" min-width="160" />
        <el-table-column :label="t('modelUsage.status')" width="110">
          <template #default="scope"><el-tag :type="statusType(scope.row.status)" size="small">{{ t(`modelUsage.statuses.${scope.row.status}`) }}</el-tag></template>
        </el-table-column>
        <el-table-column prop="inputTokens" :label="t('modelUsage.inputTokens')" width="110" />
        <el-table-column prop="outputTokens" :label="t('modelUsage.outputTokens')" width="110" />
        <el-table-column prop="durationMs" :label="t('modelUsage.duration')" width="110">
          <template #default="scope">{{ milliseconds(scope.row.durationMs) }}</template>
        </el-table-column>
        <el-table-column prop="traceId" :label="t('modelUsage.traceId')" min-width="220" show-overflow-tooltip />
      </el-table>
      <el-empty v-if="!records.length && !loading" :description="t('modelUsage.empty')" />
      <el-pagination v-model:current-page="pagination.page" v-model:page-size="pagination.pageSize"
        :page-sizes="[10, 20, 50, 100]" :total="pagination.total" layout="total, sizes, prev, pager, next"
        @size-change="loadRecords" @current-change="loadRecords" />
    </div>
  </section>
</template>

<script setup>
import { computed, onMounted, onUnmounted, reactive, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const auth = useAuthStore()
const loading = ref(false)
const error = ref(false)
const lastLoadedAt = ref('')
const dateRange = ref([])
const filters = reactive({ providerCode: '', modelName: '', source: '', status: '' })
const overview = reactive({ calls: 0, success: 0, failed: 0, interrupted: 0, cancelled: 0, running: 0, totalTokens: 0, averageDurationMs: null, averageFirstTokenLatencyMs: null })
const models = ref([])
const errors = ref([])
const records = ref([])
const pagination = reactive({ page: 1, pageSize: 20, total: 0 })
const sources = ['CHAT', 'STREAM', 'EMBEDDING', 'AGENT']
const statuses = ['SUCCESS', 'FAILED', 'INTERRUPTED', 'CANCELLED', 'RUNNING']
let timer

const successRate = computed(() => rate(overview.success, overview.calls))

function rangeParams() {
  const params = { ...filters }
  if (dateRange.value?.length === 2) {
    params.from = new Date(dateRange.value[0]).toISOString()
    params.to = new Date(dateRange.value[1]).toISOString()
  }
  Object.keys(params).forEach(key => { if (!params[key]) delete params[key] })
  return params
}

async function load() {
  loading.value = true
  error.value = false
  try {
    const response = await http.get('/operations/llm-usage/summary', { params: rangeParams() })
    Object.assign(overview, response.data.overview || {})
    models.value = response.data.models || []
    errors.value = response.data.errors || []
    lastLoadedAt.value = new Date().toLocaleTimeString()
    pagination.page = 1
    await loadRecords()
  } catch {
    error.value = true
  } finally { loading.value = false }
}

async function loadRecords() {
  try {
    const response = await http.get('/operations/llm-usage/records', { params: { ...rangeParams(), page: pagination.page, pageSize: pagination.pageSize } })
    records.value = response.data.records || []
    pagination.total = response.data.total || 0
  } catch { error.value = true }
}

function reset() {
  dateRange.value = []
  Object.assign(filters, { providerCode: '', modelName: '', source: '', status: '' })
  load()
}

function number(value) { return Number(value || 0).toLocaleString() }
function milliseconds(value) { return value == null ? '-' : `${Math.round(Number(value))} ms` }
function percent(value) { return `${(Number(value || 0) * 100).toFixed(1)}%` }
function rate(success, calls) { return Number(calls || 0) ? Number(success || 0) / Number(calls) : 0 }
function statusType(status) { return status === 'SUCCESS' ? 'success' : status === 'RUNNING' ? 'warning' : 'danger' }

onMounted(() => {
  if (!auth.isAdmin) return
  load()
  timer = window.setInterval(load, 30000)
})
onUnmounted(() => { if (timer) window.clearInterval(timer) })
</script>

<style scoped>
.model-usage-panel { margin-top: 20px; }
.model-usage-actions { display: flex; align-items: center; gap: 10px; }
.model-usage-filters { display: flex; flex-wrap: wrap; gap: 10px; margin: 18px 0; }
.model-usage-filters .el-input, .model-usage-filters .el-select { width: 160px; }
.model-usage-metrics { margin: 18px 0; }
.model-usage-columns { display: grid; grid-template-columns: 1fr 1fr; gap: 24px; margin-top: 24px; }
.model-usage-columns h3, .model-usage-records h3 { margin: 0 0 12px; font-size: 15px; }
.model-usage-records { margin-top: 24px; }
@media (max-width: 900px) { .model-usage-columns { grid-template-columns: 1fr; } .model-usage-filters .el-input, .model-usage-filters .el-select { width: 100%; } }
</style>
