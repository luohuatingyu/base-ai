<!-- 仪表盘页面：展示平台运行概览和常用入口。 -->
<template>
  <div>
    <section class="hero panel">
      <div><span class="eyebrow">{{ t('dashboard.eyebrow') }}</span><h2>{{ t('dashboard.title') }}</h2><p>{{ t('dashboard.description') }}</p></div>
      <div class="orbit">AI</div>
    </section>
    <div class="metric-grid">
      <div class="metric"><b>{{ stats.tasks24h }}</b><span>{{ t('dashboard.tasks24h') }}</span></div>
      <div class="metric"><b>{{ stats.success24h }}</b><span>{{ t('dashboard.success24h') }}</span></div>
      <div class="metric"><b>{{ stats.failed24h }}</b><span>{{ t('dashboard.failed24h') }}</span></div>
      <div class="metric"><b>{{ stats.running }}</b><span>{{ t('dashboard.running') }}</span></div>
    </div>
  </div>
</template>
<script setup>
import { useI18n } from 'vue-i18n'
import { reactive, onMounted } from 'vue'
import http from '../api/http'
const { t } = useI18n()
const stats = reactive({ tasks24h: 0, success24h: 0, failed24h: 0, running: 0 })
onMounted(async () => { try { Object.assign(stats, (await http.get('/operations/dashboard')).data) } catch { /* 首页允许在服务暂不可用时展示零值 */ } })
</script>
