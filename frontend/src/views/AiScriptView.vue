<template>
  <div class="ai-script-page">
    <section class="ai-script-hero panel">
      <div>
        <span class="ai-script-eyebrow">{{ t('aiScript.eyebrow') }}</span>
        <h2>{{ t('aiScript.title') }}</h2>
        <p>{{ t('aiScript.subtitle') }}</p>
      </div>
      <div class="ai-script-flow" aria-label="script workflow">
        <span class="is-active">01 {{ t('aiScript.generate') }}</span><i></i><span>02 {{ t('aiScript.save') }}</span><i></i><span>03 {{ t('aiScript.useForVideo') }}</span>
      </div>
    </section>

    <div class="ai-script-layout">
      <section class="panel ai-script-brief">
        <div class="section-head">
          <div><h3>{{ t('aiScript.brief') }}</h3><p>{{ t('aiScript.modelHint') }}</p></div>
          <el-tag effect="plain" type="success">AI</el-tag>
        </div>
        <el-form label-position="top" :model="form" @submit.prevent>
          <el-form-item :label="t('aiScript.project')" required>
            <el-select v-model="projectId" filterable clearable :placeholder="t('aiScript.selectProject')" :disabled="busy || saving" class="full-field">
              <el-option v-for="project in projects" :key="project.id" :label="`${project.code} · ${project.name}`" :value="project.id" />
            </el-select>
            <small v-if="!projects.length && !projectsLoading" class="field-hint">{{ t('aiScript.noProjects') }} <el-button link type="primary" @click="router.push('/ai-video/projects')">{{ t('aiScript.openProjects') }}</el-button></small>
          </el-form-item>
          <el-form-item :label="t('aiScript.brief')" required>
            <el-input v-model="form.brief" type="textarea" :rows="6" maxlength="10000" show-word-limit :placeholder="t('aiScript.briefPlaceholder')" />
          </el-form-item>
          <div class="ai-script-form-grid">
            <el-form-item :label="t('aiScript.genre')"><el-input v-model="form.genre" :placeholder="t('aiScript.genrePlaceholder')" maxlength="80" /></el-form-item>
            <el-form-item :label="t('aiScript.tone')"><el-input v-model="form.tone" :placeholder="t('aiScript.tonePlaceholder')" maxlength="120" /></el-form-item>
            <el-form-item :label="t('aiScript.duration')"><el-input-number v-model="form.durationSeconds" :min="1" :max="3600" controls-position="right" class="full-field" /></el-form-item>
            <el-form-item :label="t('aiScript.sceneCount')"><el-input-number v-model="form.sceneCount" :min="1" :max="24" controls-position="right" class="full-field" /></el-form-item>
          </div>
          <div class="ai-script-actions">
            <span class="field-hint">{{ t('aiScript.editHint') }}</span>
            <el-button type="primary" :loading="busy" :disabled="!canGenerate" @click="generate">{{ result ? t('aiScript.regenerate') : t('aiScript.generate') }}</el-button>
          </div>
        </el-form>
      </section>

      <section class="panel ai-script-result">
        <div class="section-head">
          <div><h3>{{ t('aiScript.resultTitle') }}</h3><p>{{ t('aiScript.videoHint') }}</p></div>
          <el-tag v-if="savedScriptId" type="success" effect="plain">{{ t('aiScript.saved') }}</el-tag>
        </div>
        <el-empty v-if="!result" :description="t('aiScript.emptyResult')" :image-size="86" />
        <template v-else>
          <div class="ai-script-result-fields">
            <el-form-item :label="t('aiScript.sceneTitle')"><el-input v-model="result.title" maxlength="200" /></el-form-item>
            <el-form-item :label="t('aiScript.logline')"><el-input v-model="result.logline" maxlength="1000" /></el-form-item>
            <el-form-item :label="t('aiScript.synopsis')"><el-input v-model="result.synopsis" type="textarea" :rows="4" maxlength="10000" show-word-limit /></el-form-item>
          </div>
          <div class="ai-script-scenes-heading"><h4>{{ t('aiScript.scenes') }}</h4><span>{{ result.scenes.length }}</span></div>
          <div class="ai-script-scenes">
            <article v-for="(scene, index) in result.scenes" :key="`${index}-${scene.title}`" class="ai-script-scene">
              <div class="ai-script-scene-head"><span>{{ String(index + 1).padStart(2, '0') }}</span><el-input v-model="scene.title" :placeholder="t('aiScript.sceneTitle')" maxlength="200" /></div>
              <el-input v-model="scene.sceneDescription" type="textarea" :rows="3" :placeholder="t('aiScript.sceneDescription')" maxlength="2000" />
              <div class="ai-script-scene-grid">
                <el-input v-model="scene.dialogue" type="textarea" :rows="2" :placeholder="t('aiScript.dialogue')" maxlength="5000" />
                <el-input v-model="scene.narration" type="textarea" :rows="2" :placeholder="t('aiScript.narration')" maxlength="5000" />
                <el-input v-model="scene.camera" :placeholder="t('aiScript.camera')" maxlength="120" />
                <el-input-number v-model="scene.durationMs" :min="100" :max="3600000" controls-position="right" class="full-field" />
              </div>
            </article>
          </div>
          <div class="ai-script-result-actions">
            <el-button :loading="saving" @click="saveScript">{{ t('aiScript.save') }}</el-button>
            <el-button type="primary" :loading="saving" @click="useForVideo">{{ t('aiScript.useForVideo') }}</el-button>
          </div>
        </template>
      </section>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import http, { showHttpError } from '../api/http'
import { createAiVideoResource, listAiVideoResource, updateAiVideoResource } from '../api/aiVideo'
import { buildScriptRequest, parseScriptResponse, scriptContent, scriptVideoPrompt, storyboardPayloads } from '../utils/ai-script.js'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const projects = ref([])
const projectsLoading = ref(false)
const projectId = ref(null)
const result = ref(null)
const savedScriptId = ref(null)
const busy = ref(false)
const saving = ref(false)
const form = reactive({ brief: '', genre: '', tone: '', language: '简体中文', durationSeconds: 60, sceneCount: 6 })
const canGenerate = computed(() => Boolean(projectId.value && form.brief.trim()) && !busy.value && !saving.value)

/** 查询当前用户可访问的项目，并优先应用视频工作台传入的项目上下文。 */
async function loadProjects() {
  projectsLoading.value = true
  try {
    projects.value = (await listAiVideoResource('projects')).data || []
    const wanted = Number(route.query.projectId)
    projectId.value = projects.value.find(project => project.id === wanted)?.id || projects.value[0]?.id || null
  } catch (cause) {
    showHttpError(cause, 'common.loadingFailed')
  } finally { projectsLoading.value = false }
}

/** 调用独立 AI 剧本接口，把模型正文解析为可编辑的结构化剧本。 */
async function generate() {
  if (!canGenerate.value) return
  busy.value = true
  savedScriptId.value = null
  try {
    const response = await http.post('/ai/script/generate', buildScriptRequest(form))
    result.value = parseScriptResponse(response.data?.content)
    ElMessage.success(t('aiScript.generate'))
  } catch (cause) {
    showHttpError(cause, 'aiScript.generateFailed')
  } finally { busy.value = false }
}

/** 保存剧本正文并按镜头顺序同步分镜记录，确保 AI 视频可引用同一项目上下文。 */
async function saveScript() {
  if (!result.value || !projectId.value || saving.value) return null
  saving.value = true
  try {
    const payload = { projectId: projectId.value, title: result.value.title, content: scriptContent(result.value), version: 1, status: 'READY' }
    const creating = !savedScriptId.value
    const response = savedScriptId.value
      ? await updateAiVideoResource('scripts', savedScriptId.value, payload)
      : await createAiVideoResource('scripts', payload)
    savedScriptId.value = response.data?.id || savedScriptId.value
    if (!savedScriptId.value) throw new Error('script id missing')
    if (creating) {
      const shots = storyboardPayloads(result.value, projectId.value, savedScriptId.value)
      for (const shot of shots) await createAiVideoResource('storyboards', shot)
    }
    ElMessage.success(t('aiScript.saved'))
    return savedScriptId.value
  } catch (cause) {
    showHttpError(cause, 'aiScript.saveFailed')
    return null
  } finally { saving.value = false }
}

/** 确保剧本已经持久化后进入视频生成页，携带剧本和项目上下文。 */
async function useForVideo() {
  const id = savedScriptId.value || await saveScript()
  if (!id || !result.value) {
    if (!id) ElMessage.warning(t('aiScript.saveFirst'))
    return
  }
  await router.push({ path: '/ai-generation', query: { type: 'video_model', mode: 'STORYBOARD_TO_VIDEO', projectId: String(projectId.value), scriptId: String(id), source: 'script', prompt: scriptVideoPrompt(result.value) } })
}

onMounted(loadProjects)
</script>

<style scoped>
.ai-script-page { display: grid; gap: 18px; }
.ai-script-hero { display: flex; align-items: flex-end; justify-content: space-between; gap: 24px; padding: 26px 28px; overflow: hidden; background: linear-gradient(125deg, #243b68, #445fa1 55%, #6c6add); color: #fff; }
.ai-script-eyebrow { font-size: 11px; font-weight: 800; letter-spacing: .14em; opacity: .75; }
.ai-script-hero h2 { margin: 8px 0; font-size: clamp(26px, 3vw, 38px); letter-spacing: -.04em; }
.ai-script-hero p { max-width: 720px; margin: 0; color: rgb(255 255 255 / 82%); line-height: 1.6; }
.ai-script-flow { display: flex; align-items: center; gap: 9px; flex-wrap: wrap; color: rgb(255 255 255 / 66%); font-size: 11px; font-weight: 700; white-space: nowrap; }
.ai-script-flow span.is-active { color: #fff; }
.ai-script-flow i { width: 20px; height: 1px; background: rgb(255 255 255 / 35%); }
.ai-script-layout { display: grid; grid-template-columns: minmax(260px, .72fr) minmax(0, 1.5fr); gap: 18px; align-items: start; }
.ai-script-brief, .ai-script-result { padding: 20px; }
.ai-script-brief .el-form-item { margin-bottom: 14px; }
.ai-script-form-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 12px; }
.full-field { width: 100%; }
.field-hint { color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.5; }
.ai-script-actions, .ai-script-result-actions { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 8px; }
.ai-script-result { min-height: 520px; }
.ai-script-result-fields { display: grid; gap: 4px; }
.ai-script-result-fields .el-form-item { margin-bottom: 9px; }
.ai-script-scenes-heading { display: flex; align-items: center; justify-content: space-between; margin: 14px 0 9px; }
.ai-script-scenes-heading h4 { margin: 0; }
.ai-script-scenes-heading span { display: grid; place-items: center; min-width: 26px; height: 26px; border-radius: 50%; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 12px; font-weight: 700; }
.ai-script-scenes { display: grid; gap: 11px; }
.ai-script-scene { display: grid; gap: 9px; padding: 13px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: var(--el-fill-color-light); }
.ai-script-scene-head { display: grid; grid-template-columns: 32px minmax(0, 1fr); align-items: center; gap: 8px; }
.ai-script-scene-head > span { color: var(--el-color-primary); font-size: 12px; font-weight: 800; }
.ai-script-scene-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 8px; }
.ai-script-result-actions { justify-content: flex-end; margin-top: 16px; }
@media (max-width: 900px) { .ai-script-layout { grid-template-columns: 1fr; } }
@media (max-width: 600px) { .ai-script-hero { align-items: flex-start; flex-direction: column; padding: 22px; } .ai-script-form-grid, .ai-script-scene-grid { grid-template-columns: 1fr; } .ai-script-actions { align-items: flex-start; flex-direction: column; } .ai-script-actions .el-button, .ai-script-result-actions .el-button { flex: 1; } }
</style>
