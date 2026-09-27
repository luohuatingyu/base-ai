<!-- 仪表盘按统计口径呈现任务概览，并为管理员展示模型使用监控。 -->
<template>
  <div class="dashboard">
    <section class="panel dashboard-header">
      <div>
        <span class="eyebrow">{{ t('dashboard.eyebrow') }}</span>
        <h2>{{ t('dashboard.title') }}</h2>
        <p>{{ t('dashboard.description') }}</p>
      </div>
      <div v-if="canView" class="dashboard-actions">
        <span class="updated-time" aria-live="polite">{{ updatedAt ? t('modelUsage.updatedAt', { time: updatedAt }) : t('dashboard.notUpdated') }}</span>
        <el-button :icon="Refresh" :loading="loading" @click="load">{{ t('common.refresh') }}</el-button>
      </div>
    </section>

    <el-alert v-if="!canView" class="dashboard-notice" :title="t('dashboard.noPermission')" type="info" show-icon :closable="false" />
    <template v-else>
      <el-alert v-if="error" class="dashboard-notice" :title="t('dashboard.loadFailed')" type="error" show-icon :closable="false" />
      <div class="dashboard-section-heading">
        <h3>{{ t('dashboard.taskOverview') }}</h3>
        <span>{{ t('dashboard.last24Hours') }}</span>
      </div>
      <div class="dashboard-metrics" :aria-busy="loading">
        <article v-for="item in metrics" :key="item.key" class="dashboard-metric" :class="`metric-${item.tone}`">
          <div class="metric-label"><span>{{ t(`dashboard.${item.key}`) }}</span><el-icon aria-hidden="true"><component :is="item.icon" /></el-icon></div>
          <el-skeleton v-if="loading" animated :rows="0" />
          <strong v-else>{{ formatCount(stats[item.key], locale) }}</strong>
          <p>{{ t(item.key === 'running' ? 'dashboard.runningHint' : 'dashboard.createdIn24Hours') }}</p>
        </article>
      </div>
      <div class="dashboard-alert-strip" :class="{ 'has-alerts': stats.alerts > 0 }" role="status">
        <el-icon aria-hidden="true"><Warning /></el-icon>
        <span>{{ t('dashboard.alerts') }}</span>
        <b>{{ loading ? '—' : formatCount(stats.alerts, locale) }}</b>
        <span class="alert-hint">{{ t('dashboard.alertsHint') }}</span>
      </div>
    </template>
    <ModelUsagePanel />
  </div>
</template>

<script setup>
import { useI18n } from 'vue-i18n'
import { computed, onMounted, reactive, ref } from 'vue'
import { Checked, CircleClose, DataAnalysis, Loading, Refresh, Warning } from '@element-plus/icons-vue'
import http from '../api/http'
import { useAuthStore } from '../stores/auth'
import { formatCount } from '../utils/dashboardMetrics'
import ModelUsagePanel from '../components/ModelUsagePanel.vue'

const { t, locale } = useI18n()
const auth = useAuthStore()
const canView = computed(() => auth.hasPermission('operations:dashboard:view'))
const stats = reactive({ tasks24h: null, success24h: null, failed24h: null, running: null, alerts: null })
const loading = ref(false)
const error = ref(false)
const updatedAt = ref('')
const metrics = [
  { key: 'tasks24h', tone: 'primary', icon: DataAnalysis },
  { key: 'success24h', tone: 'success', icon: Checked },
  { key: 'failed24h', tone: 'danger', icon: CircleClose },
  { key: 'running', tone: 'warning', icon: Loading }
]

/** 按权限刷新概览；失败时清空数值，防止将未知状态解释成零或最新数据。 */
async function load() {
  if (!canView.value || loading.value) return
  loading.value = true
  error.value = false
  try {
    const { data } = await http.get('/operations/dashboard', { silentError: true })
    for (const key of Object.keys(stats)) stats[key] = data[key] ?? null
    updatedAt.value = new Date().toLocaleTimeString(locale.value)
  } catch {
    for (const key of Object.keys(stats)) stats[key] = null
    updatedAt.value = ''
    error.value = true
  } finally {
    loading.value = false
  }
}

onMounted(load)
</script>

<style scoped>
.dashboard { min-width: 0; }
.dashboard-header { display: flex; align-items: center; justify-content: space-between; gap: 24px; background: linear-gradient(110deg, #f0f5ff, #fff 75%); }
.dashboard-header .eyebrow { color: var(--app-primary); }
.dashboard-header h2 { margin: 8px 0; font-size: clamp(24px, 3vw, 30px); letter-spacing: -.5px; }
.dashboard-header p { margin: 0; color: #56637a; line-height: 1.7; }
.dashboard-actions { display: flex; align-items: center; flex-wrap: wrap; justify-content: flex-end; gap: 12px; flex-shrink: 0; }
.updated-time, .dashboard-section-heading > span { color: #56637a; font-size: 12px; }
.dashboard-notice { margin-top: 16px; }
.dashboard-section-heading { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 24px 0 12px; }
.dashboard-section-heading h3 { margin: 0; font-size: 16px; }
.dashboard-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 16px; }
.dashboard-metric { --metric-color: #356ae6; --metric-tint: #edf3ff; min-width: 0; padding: 20px; border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-surface); box-shadow: var(--app-shadow); }
.metric-success { --metric-color: #17835e; --metric-tint: #ecf8f1; }
.metric-danger { --metric-color: #c63d4b; --metric-tint: #fff0f1; }
.metric-warning { --metric-color: #a56508; --metric-tint: #fff7e7; }
.metric-label { display: flex; align-items: center; justify-content: space-between; gap: 8px; color: #56637a; font-size: 13px; }
.metric-label .el-icon { flex-shrink: 0; width: 32px; height: 32px; border-radius: 9px; color: var(--metric-color); background: var(--metric-tint); font-size: 18px; }
.dashboard-metric strong { display: block; margin: 14px 0 8px; font-size: clamp(24px, 2.6vw, 34px); font-variant-numeric: tabular-nums; overflow-wrap: anywhere; line-height: 1.2; }
.dashboard-metric p { margin: 0; font-size: 12px; line-height: 1.6; color: #56637a; }
.dashboard-metric .el-skeleton { margin: 22px 0; }
.dashboard-alert-strip { display: flex; align-items: center; flex-wrap: wrap; gap: 10px; padding: 14px 18px; margin-top: 12px; border: 1px solid var(--app-border); border-radius: 10px; background: var(--app-surface); font-size: 13px; }
.dashboard-alert-strip b { font-size: 18px; font-variant-numeric: tabular-nums; }
.dashboard-alert-strip.has-alerts { background: #fff8f6; border-color: #f4d5ce; }
.has-alerts .el-icon, .has-alerts b { color: #b43b35; }
.alert-hint { margin-left: auto; color: #56637a; font-size: 12px; }
@media (max-width: 1100px) {
  .dashboard-header { align-items: flex-start; flex-direction: column; gap: 16px; }
  .dashboard-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); }
}
@media (max-width: 600px) {
  .dashboard-actions { width: 100%; justify-content: space-between; }
  .dashboard-metrics { gap: 10px; }
  .dashboard-metric { padding: 14px; }
  .metric-label { align-items: flex-start; }
  .metric-label .el-icon { width: 24px; height: 24px; font-size: 16px; }
  .alert-hint { width: 100%; margin-left: 0; }
}
@media (max-width: 380px) { .dashboard-metrics { grid-template-columns: minmax(0, 1fr); } }
</style>
