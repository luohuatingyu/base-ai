<template>
  <div class="ai-video-page">
    <section class="ai-video-hero panel">
      <div>
        <span class="eyebrow">{{ t('aiVideo.eyebrow') }}</span>
        <h2>{{ section.title }}</h2>
        <p>{{ section.description }}</p>
      </div>
      <div class="ai-video-mark">AI<br>VIDEO</div>
    </section>

    <section class="ai-video-summary">
      <div v-for="item in summary" :key="item.key" class="ai-video-summary-card panel">
        <span>{{ t(`aiVideo.summary.${item.key}`) }}</span>
        <strong>{{ item.value }}</strong>
        <small>{{ t(`aiVideo.summary.${item.key}Hint`) }}</small>
      </div>
    </section>

    <section class="panel ai-video-content">
      <div class="section-head">
        <div>
          <h3>{{ t('aiVideo.currentModule') }}</h3>
          <p>{{ t('aiVideo.currentModuleHint') }}</p>
        </div>
        <el-tag type="info" effect="plain">{{ t('aiVideo.permissionReady') }}</el-tag>
      </div>
      <el-empty :description="t('aiVideo.empty')" :image-size="96" />
    </section>
  </div>
</template>

<script setup>
import { computed } from 'vue'
import { useI18n } from 'vue-i18n'
import { useRoute } from 'vue-router'

const { t } = useI18n()
const route = useRoute()

const sections = {
  dashboard: 'dashboard',
  projects: 'projects',
  scripts: 'scripts',
  storyboards: 'storyboards',
  characters: 'characters',
  scenes: 'scenes',
  items: 'items',
  costumes: 'costumes',
  motions: 'motions',
  styles: 'styles',
  voices: 'voices',
  sounds: 'sounds',
  tasks: 'tasks',
  renders: 'renders',
  videos: 'videos',
  reviews: 'reviews',
  publishes: 'publishes',
  analytics: 'analytics'
}

/** 根据路由路径解析当前 AI 视频业务模块，未知路径回退到工作台首页。 */
const sectionKey = computed(() => {
  const pathPart = route.path.split('/').filter(Boolean).pop()
  return sections[pathPart] || sections.dashboard
})

/** 返回当前页面的本地化标题和说明。 */
const section = computed(() => ({
  title: t(`aiVideo.sections.${sectionKey.value}.title`),
  description: t(`aiVideo.sections.${sectionKey.value}.description`)
}))

const summary = [
  { key: 'projects', value: '—' },
  { key: 'assets', value: '—' },
  { key: 'tasks', value: '—' },
  { key: 'videos', value: '—' }
]
</script>

<style scoped>
.ai-video-page { display: grid; gap: 18px; }
.ai-video-hero { display: flex; align-items: center; justify-content: space-between; min-height: 180px; overflow: hidden; background: linear-gradient(125deg, #101b3a, #263d7a 58%, #4c78bc); color: #fff; }
.ai-video-hero h2 { margin: 10px 0 8px; font-size: clamp(26px, 4vw, 42px); letter-spacing: -.03em; }
.ai-video-hero p { max-width: 720px; margin: 0; color: rgb(239 245 255 / 82%); }
.ai-video-mark { width: 132px; height: 132px; display: grid; place-content: center; border: 1px solid rgb(255 255 255 / 35%); border-radius: 50%; color: rgb(255 255 255 / 88%); font-size: 16px; font-weight: 800; line-height: 1.4; letter-spacing: .18em; text-align: center; transform: rotate(-8deg); }
.ai-video-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.ai-video-summary-card { display: grid; gap: 6px; }
.ai-video-summary-card span, .ai-video-summary-card small { color: var(--app-muted); }
.ai-video-summary-card strong { font-size: 28px; color: var(--app-text); }
.ai-video-content { min-height: 320px; }
@media (max-width: 800px) { .ai-video-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } .ai-video-mark { width: 96px; height: 96px; font-size: 12px; } }
@media (max-width: 520px) { .ai-video-hero { align-items: flex-start; } .ai-video-mark { display: none; } }
</style>
