<template>
  <div class="ai-video-page">
    <section class="ai-video-hero panel" :title="t('aiVideo.currentModuleHint')">
      <div>
        <span class="eyebrow">{{ t('aiVideo.eyebrow') }}</span>
        <h2>{{ section.title }}</h2>
        <p>{{ section.description }}</p>
      </div>
      <div class="ai-video-hero-actions">
        <el-button v-if="isDashboard" type="primary" plain @click="goTo('projects')">{{ t('aiVideo.startProject') }}</el-button>
        <div class="ai-video-mark">AI<br>VIDEO</div>
      </div>
    </section>

    <template v-if="isDashboard || isAnalytics">
      <section class="ai-video-summary">
        <div v-for="item in summary" :key="item.key" class="ai-video-summary-card panel">
          <span>{{ item.label }}</span>
          <strong>{{ overview[item.key] ?? 0 }}</strong>
          <small>{{ item.hint }}</small>
        </div>
      </section>
      <section v-if="isDashboard" class="panel ai-video-guide">
        <div class="section-head">
          <div><h3>{{ t('aiVideo.workflowTitle') }}</h3><p>{{ t('aiVideo.workflowHint') }}</p></div>
          <el-tag type="success" effect="plain">{{ t('aiVideo.liveData') }}</el-tag>
        </div>
        <div class="ai-video-steps">
          <button v-for="(step, index) in workflowSteps" :key="step.title" type="button" class="ai-video-step" @click="goTo(step.path)">
            <b>{{ index + 1 }}</b><div><strong>{{ step.title }}</strong><p>{{ step.description }}</p></div>
          </button>
        </div>
        <div class="production-paths">
          <div class="production-paths-head"><div><h4>{{ t('aiVideo.pathsTitle') }}</h4><p>{{ t('aiVideo.pathsHint') }}</p></div><el-tag effect="plain">{{ productionPaths.length }} {{ t('aiVideo.pathsUnit') }}</el-tag></div>
          <div class="production-path-grid">
            <button v-for="path in productionPaths" :key="path.key" type="button" class="production-path" @click="goTo(path.route, path.mode)">
              <span class="production-path-icon">{{ path.icon }}</span><span><strong>{{ path.title }}</strong><small>{{ path.description }}</small></span><el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </div>
      </section>
      <section v-else class="panel ai-video-analytics">
        <div class="section-head"><div><h3>{{ t('aiVideo.analyticsTitle') }}</h3><p>{{ t('aiVideo.analyticsHint') }}</p></div></div>
        <el-descriptions :column="2" border>
          <el-descriptions-item :label="t('aiVideo.runningTasks')">{{ overview.runningTasks ?? 0 }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiVideo.failedTasks')">{{ overview.failedTasks ?? 0 }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiVideo.assetCoverage')">{{ overview.assets ?? 0 }} {{ t('aiVideo.assetsUnit') }}</el-descriptions-item>
          <el-descriptions-item :label="t('aiVideo.videoCoverage')">{{ overview.videos ?? 0 }} {{ t('aiVideo.videosUnit') }}</el-descriptions-item>
        </el-descriptions>
      </section>
    </template>

    <section v-else class="panel ai-video-content">
      <div class="section-head">
        <div><h3>{{ section.title }}</h3><p>{{ section.description }}</p></div>
        <div class="toolbar-actions">
          <el-button @click="load">{{ t('common.refresh') }}</el-button>
          <el-button v-if="can('create')" type="primary" @click="openCreate">{{ t('common.add') }}</el-button>
        </div>
      </div>
      <div v-if="assetType" class="asset-context"><el-tag>{{ assetTypeLabel }}</el-tag><span>{{ t('aiVideo.assetContextHint') }}</span></div>
      <el-alert v-if="loadError" class="ai-video-error" :title="t('aiVideo.loadFailed')" type="error" show-icon :closable="false">
        <template #default><el-button size="small" type="danger" plain @click="load">{{ t('common.retry') }}</el-button></template>
      </el-alert>
      <div class="ai-video-filters">
        <el-input v-model="keyword" clearable :placeholder="t('aiVideo.searchPlaceholder')" @clear="resetFilters" />
        <el-select v-model="statusFilter" clearable :placeholder="t('aiVideo.allStatuses')">
          <el-option v-for="status in availableStatuses" :key="status" :label="statusLabel(status)" :value="status" />
        </el-select>
        <span class="ai-video-result-count">{{ t('aiVideo.resultCount', { count: filteredRows.length }) }}</span>
      </div>
      <el-table v-loading="loading" :data="filteredRows" stripe table-layout="auto" class="ai-video-table" empty-text=" ">
        <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.width || 140" show-overflow-tooltip>
          <template #default="scope">
            <el-tag v-if="column.key === 'status'" size="small" effect="plain">{{ statusLabel(scope.row.status) }}</el-tag>
            <span v-else>{{ displayValue(scope.row[column.key]) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.operation')" min-width="220" fixed="right">
          <template #default="scope">
            <div class="table-actions">
              <el-button v-if="can('update')" link type="primary" @click="openEdit(scope.row)">{{ t('common.edit') }}</el-button>
              <el-button v-if="current.resource === 'tasks' && ['QUEUED', 'RUNNING'].includes(scope.row.status)" link type="warning" @click="cancelTask(scope.row)">{{ t('aiVideo.cancelTask') }}</el-button>
              <el-button v-if="current.resource === 'tasks' && ['FAILED', 'CANCELLED'].includes(scope.row.status)" link type="primary" @click="retryTask(scope.row)">{{ t('aiVideo.retryTask') }}</el-button>
              <el-button v-if="current.resource === 'publishes' && scope.row.status !== 'PUBLISHED'" link type="success" @click="completePublish(scope.row)">{{ t('aiVideo.completePublish') }}</el-button>
              <el-button v-if="can('delete')" link type="danger" @click="remove(scope.row)">{{ t('common.delete') }}</el-button>
            </div>
          </template>
        </el-table-column>
        <template #empty>
          <el-empty :description="rows.length ? t('aiVideo.noResults') : t('aiVideo.noRows')" :image-size="72" />
        </template>
      </el-table>
    </section>

    <el-dialog v-model="dialogVisible" :title="editingId ? t('common.edit') : t('common.add')" width="680px" destroy-on-close>
      <el-form ref="formRef" :model="form" label-position="top" status-icon class="ai-video-form">
        <el-form-item v-for="field in current.fields" :key="field.key" :label="field.label" :prop="field.key" :rules="fieldRules(field)">
          <el-select v-if="field.type === 'project'" v-model="form[field.key]" filterable clearable :placeholder="field.placeholder">
            <el-option v-for="project in projects" :key="project.id" :label="`${project.code} · ${project.name}`" :value="project.id" />
          </el-select>
          <el-select v-else-if="field.type === 'select'" v-model="form[field.key]" clearable :placeholder="field.placeholder">
            <el-option v-for="option in field.options" :key="option.value" :label="option.label" :value="option.value" />
          </el-select>
          <el-input-number v-else-if="field.type === 'number'" v-model="form[field.key]" :min="field.min" :max="field.max" />
          <el-input v-else-if="field.type === 'textarea'" v-model="form[field.key]" type="textarea" :rows="field.rows || 4" :placeholder="field.placeholder" />
          <el-input v-else v-model="form[field.key]" :placeholder="field.placeholder" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="dialogVisible = false">{{ t('common.cancel') }}</el-button>
        <el-button type="primary" :loading="saving" @click="save">{{ t('common.save') }}</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import {
  cancelAiVideoTask, completeAiVideoPublish, createAiVideoResource, deleteAiVideoResource,
  getAiVideoOverview, listAiVideoResource, retryAiVideoTask, updateAiVideoResource
} from '../api/aiVideo'

const { t } = useI18n()
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const rows = ref([])
const projects = ref([])
const overview = reactive({ projects: 0, assets: 0, tasks: 0, videos: 0, runningTasks: 0, failedTasks: 0 })
const loading = ref(false)
const loadError = ref(false)
const saving = ref(false)
const dialogVisible = ref(false)
const editingId = ref(null)
const formRef = ref(null)
const keyword = ref('')
const statusFilter = ref('')
let loadRequestId = 0
const form = reactive({})

// 路由兼容测试保留 items: 'items' 这一业务路径标识；接口分类仍使用 ITEM 枚举。
const assetTypes = { characters: 'CHARACTER', scenes: 'SCENE', items: 'ITEM', costumes: 'COSTUME', motions: 'MOTION', styles: 'STYLE', voices: 'VOICE', sounds: 'SOUND' }
const assetLabels = { CHARACTER: '角色', SCENE: '场景', ITEM: '物品', COSTUME: '服装', MOTION: '动作', STYLE: '画风', VOICE: '声音', SOUND: '音效' }
const statusOptions = values => values.map(value => ({ value, label: t(`aiVideo.statuses.${value}`) }))
const field = (key, label, type = 'input', extra = {}) => ({ key, label, type, ...extra })
const baseProject = () => field('projectId', t('aiVideo.fields.project'), 'project', { placeholder: t('aiVideo.selectProject') })

const modules = {
  projects: { resource: 'projects', permission: 'project', fields: [field('code', t('aiVideo.fields.code')), field('name', t('aiVideo.fields.name')), field('description', t('aiVideo.fields.description'), 'textarea'), field('coverUrl', t('aiVideo.fields.coverUrl')), field('aspectRatio', t('aiVideo.fields.aspectRatio')), field('resolution', t('aiVideo.fields.resolution')), field('fps', t('aiVideo.fields.fps'), 'number', { min: 1, max: 120 }), field('style', t('aiVideo.fields.style')), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'ACTIVE', 'ARCHIVED']) })], columns: [['code', '编码'], ['name', '名称'], ['status', '状态'], ['aspectRatio', '画幅'], ['resolution', '分辨率'], ['updatedAt', '更新时间']] },
  scripts: { resource: 'scripts', permission: 'script', fields: [baseProject(), field('title', t('aiVideo.fields.title')), field('content', t('aiVideo.fields.content'), 'textarea', { rows: 8 }), field('version', t('aiVideo.fields.version'), 'number', { min: 1, max: 10000 }), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'READY', 'ARCHIVED']) })], columns: [['title', '标题'], ['projectId', '项目'], ['version', '版本'], ['status', '状态'], ['updatedAt', '更新时间']] },
  storyboards: { resource: 'storyboards', permission: 'storyboard', fields: [baseProject(), field('shotNo', t('aiVideo.fields.shotNo'), 'number', { min: 1, max: 100000 }), field('title', t('aiVideo.fields.title')), field('sceneDescription', t('aiVideo.fields.sceneDescription'), 'textarea'), field('dialogue', t('aiVideo.fields.dialogue'), 'textarea'), field('narration', t('aiVideo.fields.narration'), 'textarea'), field('camera', t('aiVideo.fields.camera')), field('durationMs', t('aiVideo.fields.durationMs'), 'number', { min: 100, max: 3600000 }), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'READY', 'LOCKED']) })], columns: [['shotNo', '镜头'], ['title', '标题'], ['camera', '机位'], ['durationMs', '时长(ms)'], ['status', '状态']] },
  characters: { resource: 'assets', permission: 'character', fields: [baseProject(), field('name', t('aiVideo.fields.name')), field('description', t('aiVideo.fields.description'), 'textarea'), field('previewUrl', t('aiVideo.fields.previewUrl')), field('resourceUrl', t('aiVideo.fields.resourceUrl')), field('tags', t('aiVideo.fields.tags')), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'READY', 'ARCHIVED']) })], columns: [['name', '名称'], ['projectId', '项目'], ['tags', '标签'], ['status', '状态'], ['updatedAt', '更新时间']] },
  scenes: { resource: 'assets', permission: 'scene', fields: [], columns: [] }, items: { resource: 'assets', permission: 'item', fields: [], columns: [] }, costumes: { resource: 'assets', permission: 'costume', fields: [], columns: [] }, motions: { resource: 'assets', permission: 'motion', fields: [], columns: [] }, styles: { resource: 'assets', permission: 'style', fields: [], columns: [] }, voices: { resource: 'assets', permission: 'voice', fields: [], columns: [] }, sounds: { resource: 'assets', permission: 'sound', fields: [], columns: [] },
  tasks: { resource: 'tasks', permission: 'task', fields: [baseProject(), field('taskType', t('aiVideo.fields.taskType'), 'select', { options: statusOptions(['SCRIPT', 'IMAGE', 'VIDEO', 'TTS', 'MUSIC', 'UPSCALE']) }), field('name', t('aiVideo.fields.name')), field('prompt', t('aiVideo.fields.prompt'), 'textarea', { rows: 6 }), field('providerRouteId', t('aiVideo.fields.providerRouteId'), 'number', { min: 1, max: 100000000 }), field('inputJson', t('aiVideo.fields.inputJson'), 'textarea')], columns: [['name', '名称'], ['taskType', '类型'], ['status', '状态'], ['progress', '进度'], ['errorMessage', '错误'], ['createdAt', '创建时间']] },
  renders: { resource: 'renders', permission: 'render', fields: [baseProject(), field('name', t('aiVideo.fields.name')), field('configJson', t('aiVideo.fields.configJson'), 'textarea', { rows: 6 })], columns: [['name', '名称'], ['status', '状态'], ['progress', '进度'], ['outputUrl', '输出地址'], ['createdAt', '创建时间']] },
  videos: { resource: 'videos', permission: 'video', fields: [baseProject(), field('renderId', t('aiVideo.fields.renderId'), 'number', { min: 1, max: 100000000 }), field('title', t('aiVideo.fields.title')), field('version', t('aiVideo.fields.version'), 'number', { min: 1, max: 10000 }), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'PREVIEW', 'REVIEW', 'PUBLISHED']) }), field('mediaUrl', t('aiVideo.fields.mediaUrl')), field('coverUrl', t('aiVideo.fields.coverUrl')), field('durationMs', t('aiVideo.fields.durationMs'), 'number', { min: 0, max: 36000000 }), field('description', t('aiVideo.fields.description'), 'textarea')], columns: [['title', '标题'], ['version', '版本'], ['status', '状态'], ['durationMs', '时长(ms)'], ['mediaUrl', '视频地址']] },
  reviews: { resource: 'reviews', permission: 'review', fields: [field('videoId', t('aiVideo.fields.videoId'), 'number', { min: 1, max: 100000000 }), field('status', t('common.status'), 'select', { options: statusOptions(['PENDING', 'APPROVED', 'REJECTED']) }), field('comment', t('aiVideo.fields.comment'), 'textarea')], columns: [['videoId', '成片'], ['status', '审核状态'], ['comment', '意见'], ['reviewerUserId', '审核人'], ['reviewedAt', '审核时间']] },
  publishes: { resource: 'publishes', permission: 'publish', fields: [field('videoId', t('aiVideo.fields.videoId'), 'number', { min: 1, max: 100000000 }), field('channel', t('aiVideo.fields.channel')), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'PUBLISHING', 'PUBLISHED', 'FAILED']) }), field('externalId', t('aiVideo.fields.externalId')), field('externalUrl', t('aiVideo.fields.externalUrl')), field('errorMessage', t('aiVideo.fields.errorMessage'), 'textarea')], columns: [['videoId', '成片'], ['channel', '渠道'], ['status', '状态'], ['externalUrl', '外部地址'], ['publishedAt', '发布时间']] }
}

/** 为资产分类补齐相同的表单和表格定义。 */
Object.keys(assetTypes).forEach(key => {
  if (!modules[key].fields.length) modules[key].fields = modules.characters.fields
  if (!modules[key].columns.length) modules[key].columns = modules.characters.columns
})

const sectionKey = computed(() => route.path.split('/').filter(Boolean).pop() || 'dashboard')
const isDashboard = computed(() => sectionKey.value === 'dashboard')
const isAnalytics = computed(() => sectionKey.value === 'analytics')
const current = computed(() => modules[sectionKey.value] || modules.projects)
const assetType = computed(() => assetTypes[sectionKey.value] || '')
const assetTypeLabel = computed(() => assetLabels[assetType.value] || '')
const section = computed(() => ({ title: t(`aiVideo.sections.${sectionKey.value}.title`), description: t(`aiVideo.sections.${sectionKey.value}.description`) }))
const columns = computed(() => current.value.columns.map(([key, label]) => ({ key, label: key.endsWith('At') ? t('common.time') : label })))
const availableStatuses = computed(() => [...new Set(rows.value.map(row => row.status).filter(Boolean))])
const filteredRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (!search) return true
    return Object.values(row).some(value => String(value ?? '').toLowerCase().includes(search))
  })
})
const summary = computed(() => [
  { key: 'projects', label: t('aiVideo.summary.projects'), hint: t('aiVideo.summary.projectsHint') },
  { key: 'assets', label: t('aiVideo.summary.assets'), hint: t('aiVideo.summary.assetsHint') },
  { key: 'tasks', label: t('aiVideo.summary.tasks'), hint: t('aiVideo.summary.tasksHint') },
  { key: 'videos', label: t('aiVideo.summary.videos'), hint: t('aiVideo.summary.videosHint') }
])
const workflowSteps = computed(() => [
  { title: t('aiVideo.steps.project'), description: t('aiVideo.steps.projectHint'), path: 'projects' },
  { title: t('aiVideo.steps.content'), description: t('aiVideo.steps.contentHint'), path: 'scripts' },
  { title: t('aiVideo.steps.asset'), description: t('aiVideo.steps.assetHint'), path: 'assets/items' },
  { title: t('aiVideo.steps.generate'), description: t('aiVideo.steps.generateHint'), path: 'tasks' },
  { title: t('aiVideo.steps.publish'), description: t('aiVideo.steps.publishHint'), path: 'reviews' }
])
const productionPaths = computed(() => [
  { key: 'text', icon: '文', title: t('aiVideo.paths.textTitle'), description: t('aiVideo.paths.textHint'), route: 'tasks', mode: 'VIDEO' },
  { key: 'storyboard', icon: '镜', title: t('aiVideo.paths.storyboardTitle'), description: t('aiVideo.paths.storyboardHint'), route: 'renders', mode: 'STORYBOARD' },
  { key: 'asset', icon: '物', title: t('aiVideo.paths.assetTitle'), description: t('aiVideo.paths.assetHint'), route: 'tasks', mode: 'IMAGE' },
  { key: 'audio', icon: '声', title: t('aiVideo.paths.audioTitle'), description: t('aiVideo.paths.audioHint'), route: 'tasks', mode: 'TTS' },
  { key: 'template', icon: '模', title: t('aiVideo.paths.templateTitle'), description: t('aiVideo.paths.templateHint'), route: 'renders', mode: 'TEMPLATE' }
])

/** 判断当前用户是否拥有指定资源操作权限。 */
function can(action) {
  if (auth.isAdmin) return true
  return auth.hasPermission(`ai:video:${current.value.permission}:${action}`)
}

/** 跳转到制作流程中的指定模块。 */
function goTo(path, mode) {
  router.push({ path: `/ai-video/${path}`, query: mode ? { mode } : {} })
}

/** 判断当前页面是否允许读取项目选择项。 */
function canListProjects() {
  return auth.isAdmin || auth.hasPermission('ai:video:project:list')
}

/** 加载工作台统计、项目选择项和当前业务列表，避免无关权限导致整页失败。 */
async function load() {
  const requestId = ++loadRequestId
  loading.value = true
  loadError.value = false
  try {
    const dashboardPermission = auth.isAdmin || auth.hasPermission('ai:video:dashboard:list')
    if ((isDashboard.value || isAnalytics.value) && dashboardPermission) Object.assign(overview, await getAiVideoOverview())
    if (canListProjects()) projects.value = await listAiVideoResource('projects')
    if (!isDashboard.value && !isAnalytics.value && can('list')) {
      rows.value = await listAiVideoResource(current.value.resource, assetType.value ? { assetType: assetType.value } : {})
    } else if (!isDashboard.value && !isAnalytics.value) {
      rows.value = []
    }
    if (route.query.mode && !isDashboard.value && !isAnalytics.value && can('create')) {
      const mode = String(route.query.mode)
      openCreate(mode)
      router.replace({ query: {} })
    }
  } catch (error) {
    if (requestId === loadRequestId) loadError.value = true
  } finally {
    if (requestId === loadRequestId) loading.value = false
  }
}

/** 初始化新增表单并打开弹窗。 */
function openCreate(mode = '') {
  editingId.value = null
  Object.keys(form).forEach(key => delete form[key])
  current.value.fields.forEach(item => { form[item.key] = item.type === 'number' ? (item.min || 0) : item.type === 'select' ? item.options[0]?.value : item.type === 'project' ? null : '' })
  if (current.value.resource === 'tasks' && ['SCRIPT', 'IMAGE', 'VIDEO', 'TTS', 'MUSIC', 'UPSCALE'].includes(mode)) form.taskType = mode
  if (current.value.resource === 'renders' && ['STORYBOARD', 'TEMPLATE'].includes(mode)) form.configJson = JSON.stringify({ mode }, null, 2)
  dialogVisible.value = true
}

/** 将列表行复制到编辑表单。 */
function openEdit(row) {
  editingId.value = row.id
  Object.keys(form).forEach(key => delete form[key])
  current.value.fields.forEach(item => { form[item.key] = row[item.key] ?? (item.type === 'number' ? (item.min || 0) : item.type === 'project' ? null : '') })
  dialogVisible.value = true
}

/** 保存新增或编辑的业务资源。 */
async function save() {
  try { await formRef.value?.validate() } catch { return }
  saving.value = true
  try {
    if (editingId.value) await updateAiVideoResource(current.value.resource, editingId.value, { ...form })
    else await createAiVideoResource(current.value.resource, { ...form }, assetType.value)
    dialogVisible.value = false
    await load()
    ElMessage.success(t('common.successSaved'))
  } finally { saving.value = false }
}

/** 删除业务资源并刷新当前列表。 */
async function remove(row) {
  await ElMessageBox.confirm(t('common.confirmDelete', { name: row.name || row.title || row.id }), t('common.deleteConfirm'), { type: 'warning' })
  await deleteAiVideoResource(current.value.resource, row.id)
  await load()
  ElMessage.success(t('common.successDeleted'))
}

/** 取消排队或运行中的任务。 */
async function cancelTask(row) { await cancelAiVideoTask(row.id); await load(); ElMessage.success(t('aiVideo.taskCancelled')) }

/** 重试失败或取消的任务。 */
async function retryTask(row) { await retryAiVideoTask(row.id); await load(); ElMessage.success(t('aiVideo.taskRetried')) }

/** 完成发布记录并刷新状态。 */
async function completePublish(row) { await completeAiVideoPublish(row.id); await load(); ElMessage.success(t('aiVideo.publishCompleted')) }

/** 返回当前模块的必填字段校验规则。 */
function fieldRules(item) {
  const required = {
    projects: ['code', 'name'], scripts: ['projectId', 'title'], storyboards: ['projectId', 'title'],
    assets: ['projectId', 'name'], tasks: ['projectId', 'taskType', 'name'], renders: ['projectId', 'name'],
    videos: ['projectId', 'title'], reviews: ['videoId'], publishes: ['videoId', 'channel']
  }
  if (!required[current.value.resource]?.includes(item.key)) return undefined
  return [{ required: true, message: t('aiVideo.fieldRequired', { field: item.label }), trigger: ['blur', 'change'] }]
}

/** 清空列表筛选条件。 */
function resetFilters() {
  keyword.value = ''
  statusFilter.value = ''
}

/** 将空值和 JSON 字段转换成适合表格阅读的文本。 */
function displayValue(value) { if (value === null || value === undefined || value === '') return '—'; if (typeof value === 'object') return JSON.stringify(value); return value }

/** 将后端状态编码转换为可读文本。 */
function statusLabel(value) { return value ? t(`aiVideo.statuses.${value}`) : '—' }

watch(sectionKey, () => { resetFilters(); load() })
onMounted(load)
</script>

<style scoped>
.ai-video-page { display: grid; gap: 18px; }
.ai-video-hero { display: flex; align-items: center; justify-content: space-between; min-height: 160px; overflow: hidden; background: linear-gradient(125deg, #101b3a, #263d7a 58%, #4c78bc); color: #fff; }
.ai-video-hero h2 { margin: 10px 0 8px; font-size: clamp(26px, 4vw, 42px); letter-spacing: -.03em; }
.ai-video-hero p { max-width: 720px; margin: 0; color: rgb(239 245 255 / 82%); }
.ai-video-hero-actions { display: flex; align-items: center; gap: 18px; }
.ai-video-hero-actions :deep(.el-button) { border-color: rgb(255 255 255 / 55%); color: #fff; }
.ai-video-mark { width: 132px; height: 132px; display: grid; place-content: center; border: 1px solid rgb(255 255 255 / 35%); border-radius: 50%; color: rgb(255 255 255 / 88%); font-size: 16px; font-weight: 800; line-height: 1.4; letter-spacing: .18em; text-align: center; transform: rotate(-8deg); }
.ai-video-summary { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 14px; }
.ai-video-summary-card { display: grid; gap: 6px; }
.ai-video-summary-card span, .ai-video-summary-card small { color: var(--app-muted); }
.ai-video-summary-card strong { font-size: 28px; color: var(--app-text); }
.ai-video-content { min-height: 360px; }
.toolbar-actions, .table-actions { display: flex; align-items: center; gap: 8px; flex-wrap: wrap; }
.asset-context { display: flex; align-items: center; gap: 10px; margin-bottom: 14px; color: var(--app-muted); }
.ai-video-error { margin-bottom: 14px; }
.ai-video-filters { display: flex; align-items: center; gap: 10px; margin-bottom: 12px; }
.ai-video-filters :deep(.el-input) { width: min(320px, 100%); }
.ai-video-filters :deep(.el-select) { width: 180px; }
.ai-video-result-count { margin-left: auto; color: var(--app-muted); font-size: 12px; white-space: nowrap; }
.ai-video-table { width: 100%; }
.ai-video-form :deep(.el-select), .ai-video-form :deep(.el-input), .ai-video-form :deep(.el-input-number) { width: 100%; }
.ai-video-guide, .ai-video-analytics { min-height: 220px; }
.ai-video-steps { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 12px; }
.ai-video-step { display: flex; width: 100%; gap: 10px; padding: 16px; border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-surface-soft); color: inherit; text-align: left; cursor: pointer; transition: border-color .16s ease, background-color .16s ease, transform .16s ease; }
.ai-video-step:hover { border-color: #a9bfe9; background: #f4f7ff; transform: translateY(-2px); }
.ai-video-step:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.ai-video-step b { display: grid; width: 28px; height: 28px; flex: 0 0 auto; place-items: center; border-radius: 50%; background: var(--app-primary); color: #fff; }
.ai-video-step strong { color: var(--app-text); }
.ai-video-step p { margin: 5px 0 0; color: var(--app-muted); font-size: 13px; line-height: 1.5; }
.production-paths { margin-top: 22px; padding-top: 20px; border-top: 1px solid var(--app-border); }
.production-paths-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; margin-bottom: 12px; }
.production-paths-head h4, .production-paths-head p { margin: 0; }
.production-paths-head h4 { font-size: 15px; }
.production-paths-head p { margin-top: 4px; color: var(--app-muted); font-size: 12px; }
.production-path-grid { display: grid; grid-template-columns: repeat(5, minmax(0, 1fr)); gap: 10px; }
.production-path { display: flex; min-width: 0; align-items: center; gap: 9px; padding: 13px 12px; border: 1px solid var(--app-border); border-radius: 10px; background: #fff; color: var(--app-text); text-align: left; cursor: pointer; transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease; }
.production-path:hover { border-color: #aac0e8; box-shadow: 0 7px 18px rgb(32 69 135 / 10%); transform: translateY(-2px); }
.production-path:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.production-path-icon { display: grid; width: 30px; height: 30px; flex: 0 0 auto; place-items: center; border-radius: 9px; background: #edf3ff; color: var(--app-primary); font-size: 13px; font-weight: 700; }
.production-path>span:nth-child(2) { display: grid; min-width: 0; gap: 3px; }
.production-path strong, .production-path small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.production-path strong { font-size: 13px; }
.production-path small { color: var(--app-muted); font-size: 11px; }
.production-path :deep(.el-icon) { margin-left: auto; color: var(--app-muted); }
@media (max-width: 1000px) { .ai-video-steps { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 1100px) { .production-path-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 800px) { .ai-video-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } .ai-video-mark { width: 96px; height: 96px; font-size: 12px; } .production-path-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) { .ai-video-hero { align-items: flex-start; } .ai-video-mark { display: none; } .ai-video-hero-actions { align-items: flex-start; } .ai-video-steps { grid-template-columns: 1fr; } .ai-video-filters { align-items: stretch; flex-direction: column; } .ai-video-filters :deep(.el-input), .ai-video-filters :deep(.el-select) { width: 100%; } .ai-video-result-count { margin-left: 0; } }
@media (max-width: 520px) { .production-path-grid { grid-template-columns: 1fr; } }
</style>
