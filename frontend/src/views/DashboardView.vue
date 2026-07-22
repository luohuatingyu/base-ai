<!-- 仪表盘页面：展示平台运行概览和常用入口。 -->
<template>
  <div>
    <section class="hero panel">
      <div><span class="eyebrow">{{ t('dashboard.eyebrow') }}</span><h2>{{ t('dashboard.title') }}</h2><p>{{ t('dashboard.description') }}</p></div>
      <div class="orbit">AI</div>
    </section>
    <div class="metric-grid">
      <div class="metric"><b>MySQL</b><span>{{ t('dashboard.systemPermissions') }}</span></div>
      <div class="metric"><b>PostgreSQL</b><span>{{ t('dashboard.businessDatabase') }}</span></div>
      <div class="metric"><b>Redis</b><span>{{ t('dashboard.cacheTokens') }}</span></div>
      <div class="metric"><b>Worker</b><span>{{ t('dashboard.worker') }}</span></div>
    </div>
  </div>
</template>
<script setup>
import { useI18n } from 'vue-i18n'
const { t } = useI18n()
</script>
