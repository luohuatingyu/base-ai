<template>
  <div class="ai-video-page">
    <section class="ai-video-hero panel" :title="t('aiVideo.currentModuleHint')">
      <div>
        <span class="eyebrow">{{ t('aiVideo.eyebrow') }}</span>
        <h2>{{ section.title }}</h2>
        <p>{{ section.description }}</p>
      </div>
      <div class="ai-video-hero-actions">
        <el-button v-if="isDashboard" class="ai-video-start-button" type="primary" plain @click="goTo('projects')">{{ t('aiVideo.startProject') }}</el-button>
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
        <div class="guide-heading">
          <div class="section-head guide-heading-main">
            <div><h3>{{ t('aiVideo.workflowTitle') }}</h3><p>{{ t('aiVideo.workflowHint') }}</p></div>
            <el-tag type="success" effect="plain">{{ t('aiVideo.liveData') }}</el-tag>
          </div>
          <div class="guide-progress-card">
            <div class="guide-progress-copy"><strong>{{ guideCompletedCount }}/{{ workflowSteps.length }}</strong><span>{{ t('aiVideo.guide.progressLabel') }}</span></div>
            <el-progress :percentage="guideProgress" :show-text="false" :stroke-width="8" />
            <small>{{ guideNextStep ? t('aiVideo.guide.nextStep', { step: guideNextStep.title }) : t('aiVideo.guide.allDone') }}</small>
          </div>
        </div>
        <div class="guide-preparation">
          <div class="guide-subsection-head"><div><h4>{{ t('aiVideo.guide.preparationTitle') }}</h4><p>{{ t('aiVideo.guide.preparationHint') }}</p></div></div>
          <div class="guide-preparation-grid">
            <article v-for="item in guidePreparation" :key="item.key" class="guide-preparation-item">
              <span class="guide-preparation-icon">{{ item.icon }}</span>
              <div><strong>{{ item.title }}</strong><p>{{ item.description }}</p></div>
              <el-button link type="primary" @click="goTo(item.path)">{{ item.action }}</el-button>
            </article>
          </div>
        </div>
        <div class="guide-flow-head"><div><h4>{{ t('aiVideo.guide.flowTitle') }}</h4><p>{{ t('aiVideo.guide.flowHint') }}</p></div><span>{{ t('aiVideo.guide.flowCount', { count: workflowSteps.length }) }}</span></div>
        <div class="ai-video-steps">
          <article v-for="(step, index) in workflowSteps" :key="step.key" class="ai-video-step" :class="`is-${step.status}`">
            <div class="ai-video-step-head"><div class="ai-video-step-index">{{ String(index + 1).padStart(2, '0') }}</div><el-tag size="small" effect="plain" :type="step.status === 'done' ? 'success' : step.status === 'locked' ? 'info' : 'warning'">{{ step.statusLabel }}</el-tag></div>
            <h4>{{ step.title }}</h4><p class="ai-video-step-goal">{{ step.goal }}</p>
            <dl class="ai-video-step-details"><div><dt>{{ t('aiVideo.guide.prepareLabel') }}</dt><dd>{{ step.prepare }}</dd></div><div><dt>{{ t('aiVideo.guide.actionLabel') }}</dt><dd>{{ step.action }}</dd></div><div><dt>{{ t('aiVideo.guide.outputLabel') }}</dt><dd>{{ step.output }}</dd></div></dl>
            <div class="ai-video-step-checks"><span>{{ t('aiVideo.guide.checklistLabel') }}</span><ul><li v-for="check in step.checks" :key="check">{{ check }}</li></ul></div>
            <el-button class="ai-video-step-action" type="primary" plain @click="goTo(step.path)">{{ step.cta }}<el-icon><ArrowRight /></el-icon></el-button>
          </article>
        </div>
        <div class="production-paths">
          <div class="production-paths-head"><div><h4>{{ t('aiVideo.pathsTitle') }}</h4><p>{{ t('aiVideo.pathsHint') }}</p></div><el-tag effect="plain">{{ productionPaths.length }} {{ t('aiVideo.pathsUnit') }}</el-tag></div>
          <div class="production-path-grid">
            <button v-for="path in productionPaths" :key="path.key" type="button" class="production-path" @click="goTo(path.route, path.mode, path.type, path.generationMode, path.source)">
              <span class="production-path-icon">{{ path.icon }}</span><span><strong>{{ path.title }}</strong><small>{{ path.description }}</small><em>{{ path.next }}</em></span><el-icon><ArrowRight /></el-icon>
            </button>
          </div>
        </div>
        <div class="guide-delivery">
          <div class="guide-subsection-head"><div><h4>{{ t('aiVideo.guide.deliveryTitle') }}</h4><p>{{ t('aiVideo.guide.deliveryHint') }}</p></div></div>
          <div class="guide-delivery-grid">
            <article v-for="item in guideDelivery" :key="item.key" class="guide-delivery-item"><div class="guide-delivery-index">{{ item.index }}</div><div><strong>{{ item.title }}</strong><p>{{ item.description }}</p><small>{{ item.check }}</small></div><el-button link type="primary" @click="goTo(item.path)">{{ item.action }}</el-button></article>
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
      <section v-if="assetType" class="asset-guide" aria-labelledby="asset-guide-title">
        <h3 id="asset-guide-title">{{ t('aiVideo.creation.labels.assetTitle') }}</h3>
        <p>{{ creationGuide.purpose }}</p>
        <div class="creation-guide-grid">
          <article v-for="step in creationGuide.steps" :key="step.key">
            <strong>{{ step.title }}</strong><p>{{ step.description }}</p>
          </article>
        </div>
        <small>{{ t('aiVideo.creation.labels.example') }}：{{ creationGuide.example }}</small>
      </section>
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
      <div v-if="isTaskModule" v-loading="loading" class="task-workspace">
        <div class="task-metrics">
          <div v-for="metric in taskMetrics" :key="metric.key" class="task-metric" :class="`is-${metric.tone}`">
            <span>{{ metric.label }}</span><strong>{{ metric.value }}</strong>
          </div>
        </div>
        <div v-if="filteredRows.length" class="task-grid">
          <article v-for="row in filteredRows" :key="row.id" class="task-card">
            <div class="task-card-head">
              <span class="task-type" :class="`task-type-${String(row.taskType || 'default').toLowerCase()}`"><b>{{ taskTypeIcon(row.taskType) }}</b>{{ taskTypeLabel(row.taskType) }}</span>
              <el-tag size="small" effect="plain" :type="taskStatusType(row.status)">{{ statusLabel(row.status) }}</el-tag>
            </div>
            <div class="task-card-body">
              <h4 :title="row.name">{{ row.name || t('aiVideo.taskBoard.unnamed') }}</h4>
              <p :title="taskDescription(row)">{{ taskDescription(row) }}</p>
              <div class="task-progress-head"><span>{{ t('aiVideo.taskBoard.progress') }}</span><strong>{{ taskProgress(row) }}%</strong></div>
              <el-progress :percentage="taskProgress(row)" :status="taskProgressStatus(row.status)" :stroke-width="8" :show-text="false" />
            </div>
            <div class="task-card-meta">
              <span><i class="meta-dot" />{{ row.projectId ? `${t('aiVideo.fields.project')} #${row.projectId}` : t('aiVideo.taskBoard.noProject') }}</span>
              <span>{{ displayValue(row.createdAt) }}</span>
            </div>
            <div v-if="row.errorMessage" class="task-error" :title="row.errorMessage">{{ row.errorMessage }}</div>
            <div class="task-card-actions">
              <el-button v-if="can('update')" link type="primary" @click="openEdit(row)">{{ t('common.edit') }}</el-button>
              <el-button v-if="['QUEUED', 'RUNNING'].includes(row.status)" link type="warning" @click="cancelTask(row)">{{ t('aiVideo.cancelTask') }}</el-button>
              <el-button v-if="['FAILED', 'CANCELLED'].includes(row.status)" link type="primary" @click="retryTask(row)">{{ t('aiVideo.retryTask') }}</el-button>
              <el-button v-if="can('delete')" link type="danger" @click="remove(row)">{{ t('common.delete') }}</el-button>
            </div>
          </article>
        </div>
        <el-empty v-else :description="rows.length ? t('aiVideo.noResults') : t('aiVideo.noRows')" :image-size="72" />
      </div>
      <el-table v-else v-loading="loading" :data="filteredRows" stripe table-layout="auto" class="ai-video-table" empty-text=" ">
        <el-table-column v-for="column in columns" :key="column.key" :prop="column.key" :label="column.label" :min-width="column.width || 140" show-overflow-tooltip>
          <template #default="scope">
            <el-tag v-if="column.key === 'status'" size="small" effect="plain">{{ statusLabel(scope.row.status) }}</el-tag>
            <el-button v-else-if="current.resource === 'videos' && column.key === 'mediaUrl' && safeMediaUrl(scope.row.mediaUrl)" link type="primary" @click="previewVideo(scope.row)">{{ t('aiVideo.previewVideo') }}</el-button>
            <span v-else>{{ displayValue(scope.row[column.key]) }}</span>
          </template>
        </el-table-column>
        <el-table-column :label="t('common.operation')" min-width="220" fixed="right">
          <template #default="scope">
            <div class="table-actions">
              <el-button v-if="can('update')" link type="primary" @click="openEdit(scope.row)">{{ t('common.edit') }}</el-button>
              <el-button v-if="current.resource === 'tasks' && ['QUEUED', 'RUNNING'].includes(scope.row.status)" link type="warning" @click="cancelTask(scope.row)">{{ t('aiVideo.cancelTask') }}</el-button>
              <el-button v-if="current.resource === 'tasks' && ['FAILED', 'CANCELLED'].includes(scope.row.status)" link type="primary" @click="retryTask(scope.row)">{{ t('aiVideo.retryTask') }}</el-button>
              <el-button v-if="current.resource === 'storyboards' && can('update')" link type="primary" @click="generateStoryboard(scope.row)">{{ t('aiVideo.generateStoryboard') }}</el-button>
              <el-button v-if="current.resource === 'videos' && safeMediaUrl(scope.row.mediaUrl)" link type="primary" @click="previewVideo(scope.row)">{{ t('aiVideo.previewVideo') }}</el-button>
              <el-button v-if="current.resource === 'videos' && canReview && scope.row.status !== 'PUBLISHED'" link type="success" @click="submitQuickReview(scope.row, 'APPROVED')">{{ t('aiVideo.approveVideo') }}</el-button>
              <el-button v-if="current.resource === 'videos' && canReview && scope.row.status !== 'PUBLISHED'" link type="warning" @click="submitQuickReview(scope.row, 'REJECTED')">{{ t('aiVideo.rejectVideo') }}</el-button>
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

    <el-dialog v-model="dialogVisible" :title="dialogTitle" width="min(920px, 94vw)" top="5vh" class="video-editor" destroy-on-close :close-on-click-modal="false" :before-close="closeEditor">
      <div class="editor-intro"><span class="editor-badge">{{ section.title }}</span><p>{{ section.description }}</p></div>
      <section v-if="showCreationGuide" class="creation-guide" aria-labelledby="creation-guide-title">
        <h3 id="creation-guide-title">{{ t('aiVideo.creation.labels.createTitle') }}</h3>
        <p>{{ creationGuide.purpose }}</p>
        <div class="creation-guide-grid">
          <article v-for="(step, index) in creationGuide.steps" :key="step.key">
            <strong><span>{{ index + 1 }}.</span> {{ step.title }}</strong><p>{{ step.description }}</p>
          </article>
        </div>
      </section>
      <section v-if="isProjectCreate" class="project-create-guide" aria-labelledby="project-create-guide-title">
        <div class="project-create-guide-head">
          <div>
            <span class="project-create-guide-kicker">{{ t('aiVideo.editor.projectGuide.kicker') }}</span>
            <h3 id="project-create-guide-title">{{ t('aiVideo.editor.projectGuide.title') }}</h3>
            <p>{{ t('aiVideo.editor.projectGuide.description') }}</p>
          </div>
          <el-tag type="primary" effect="plain">{{ t('aiVideo.editor.projectGuide.requiredTag') }}</el-tag>
        </div>
        <div class="project-create-guide-steps">
          <article v-for="step in projectCreateGuide" :key="step.key" class="project-create-guide-step">
            <span class="project-create-guide-index">{{ step.index }}</span>
            <div>
              <strong>{{ step.title }}</strong>
              <p>{{ step.description }}</p>
              <small>{{ step.fields }}</small>
            </div>
          </article>
        </div>
        <div class="project-create-guide-next">
          <strong>{{ t('aiVideo.editor.projectGuide.nextTitle') }}</strong>
          <span>{{ t('aiVideo.editor.projectGuide.nextDescription') }}</span>
        </div>
      </section>
      <el-alert v-if="saveError" :title="saveError" type="error" show-icon :closable="false" class="editor-alert" />
      <el-form ref="formRef" :model="form" label-position="top" status-icon class="ai-video-form" :disabled="saving">
        <section v-for="group in formGroups" :key="group.key" class="editor-section">
          <button v-if="group.key === 'advanced'" type="button" class="editor-section-toggle" :aria-expanded="advancedOpen" @click="advancedOpen = !advancedOpen">
            <span>{{ group.title }}</span><span>{{ advancedOpen ? '−' : '+' }}</span>
          </button>
          <div v-else class="editor-section-heading"><span>{{ group.number }}</span><h3>{{ group.title }}</h3></div>
          <p class="editor-section-hint">{{ group.hint }}</p>
          <div v-show="group.key !== 'advanced' || advancedOpen" class="editor-field-grid">
            <el-form-item v-for="field in group.fields" :key="field.key" :label="field.label" :prop="field.config ? undefined : field.key" :rules="field.config ? undefined : fieldRules(field)" :class="{ 'field-wide': field.wide }">
              <el-select v-if="field.type === 'project'" v-model="form[field.key]" filterable clearable :disabled="!!editingId" :placeholder="t('aiVideo.selectProject')">
                <el-option v-for="project in projects" :key="project.id" :label="project.code + ' · ' + project.name" :value="project.id" />
              </el-select>
              <el-select v-else-if="field.type === 'select'" :model-value="fieldValue(field)" filterable clearable :allow-create="field.custom" :disabled="field.config ? !configurationValid : !!editingId && field.key === 'taskType'" @update:model-value="setFieldValue(field, $event)">
                <el-option v-for="option in field.options" :key="option.value" :label="option.label" :value="option.value" />
              </el-select>
              <el-input-number v-else-if="field.type === 'number'" v-model="form[field.key]" :min="field.min" :max="field.max" :precision="0" controls-position="right" :disabled="!!editingId && (['renderId', 'videoId'].includes(field.key) || field.key === 'version' && current.resource === 'videos')" />
              <el-input v-else :model-value="fieldValue(field)" :type="field.type === 'textarea' ? 'textarea' : 'text'" :rows="field.rows || 4" :maxlength="field.maxlength" :show-word-limit="field.type === 'textarea'" :placeholder="field.placeholder" :disabled="field.config && !configurationValid" @update:model-value="setFieldValue(field, $event)" />
              <small v-if="field.hint" class="editor-field-hint">{{ field.hint }}</small>
              <template v-if="field.media && safeMediaUrl(form[field.key])">
                <el-button link type="primary" @click="previewField = previewField === field.key ? '' : field.key">{{ t('aiVideo.editor.preview') }}</el-button>
                <div v-if="previewField === field.key" class="editor-preview">
                  <img v-if="field.media === 'image'" :src="safeMediaUrl(form[field.key])" :alt="field.label" referrerpolicy="no-referrer" @error="previewField = ''; ElMessage.warning(t('aiVideo.editor.previewFailed'))">
                  <audio v-else-if="field.media === 'audio'" :src="safeMediaUrl(form[field.key])" controls preload="metadata" @error="previewField = ''; ElMessage.warning(t('aiVideo.editor.previewFailed'))" />
                  <video v-else :src="safeMediaUrl(form[field.key])" controls preload="metadata" @error="previewField = ''; ElMessage.warning(t('aiVideo.editor.previewFailed'))" />
                </div>
              </template>
            </el-form-item>
          </div>
        </section>
      </el-form>
      <template #footer>
        <div class="editor-footer"><span>{{ t('aiVideo.editor.requiredHint') }}</span><div><el-button :disabled="saving" @click="closeEditor()">{{ t('common.cancel') }}</el-button><el-button type="primary" :loading="saving" @click="save">{{ editingId ? t('common.save') : t('aiVideo.editor.create') }}</el-button></div></div>
      </template>
    </el-dialog>

    <el-dialog v-model="videoPreviewVisible" :title="videoPreviewTitle" width="min(960px, 94vw)" destroy-on-close @closed="closeVideoPreview">
      <video v-if="videoPreviewUrl" class="ai-video-preview-player" controls autoplay playsinline preload="metadata" :src="videoPreviewUrl" @error="closeVideoPreview" />
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onBeforeUnmount, onMounted, reactive, ref, watch } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowRight } from '@element-plus/icons-vue'
import { useI18n } from 'vue-i18n'
import { useRoute, useRouter } from 'vue-router'
import { assetAttributes, configurationKey, parseConfiguration, updateConfiguration, safeMediaUrl, prepareVideoPayload } from '../utils/ai-video-form'
import { useAuthStore } from '../stores/auth'
import {
  cancelAiVideoTask, completeAiVideoPublish, createAiVideoResource, deleteAiVideoResource,
  getAiVideoOverview, listAiVideoResource, retryAiVideoTask, reviewAiVideo, updateAiVideoResource
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
const loadTimeoutMs = 12000
const form = reactive({})
const advancedOpen = ref(false)
const previewField = ref('')
const saveError = ref('')
const videoPreviewVisible = ref(false)
const videoPreviewUrl = ref('')
const videoPreviewTitle = ref('')
let initialForm = ''

// 路由兼容测试保留 items: 'items' 这一业务路径标识；接口分类仍使用 ITEM 枚举。
const assetTypes = { characters: 'CHARACTER', scenes: 'SCENE', items: 'ITEM', costumes: 'COSTUME', motions: 'MOTION', styles: 'STYLE', voices: 'VOICE', sounds: 'SOUND' }
const statusOptions = values => values.map(value => ({ value, label: t(`aiVideo.statuses.${value}`) }))
const taskTypeOptions = values => values.map(value => ({ value, label: t(`aiVideo.taskTypes.${value}`) }))
const field = (key, label, type = 'input', extra = {}) => ({ key, label, type, ...extra })
const baseProject = () => field('projectId', t('aiVideo.fields.project'), 'project', { placeholder: t('aiVideo.selectProject') })

const modules = {
  projects: { resource: 'projects', permission: 'project', fields: [field('code', t('aiVideo.fields.code')), field('name', t('aiVideo.fields.name')), field('description', t('aiVideo.fields.description'), 'textarea'), field('coverUrl', t('aiVideo.fields.coverUrl')), field('aspectRatio', t('aiVideo.fields.aspectRatio')), field('resolution', t('aiVideo.fields.resolution')), field('fps', t('aiVideo.fields.fps'), 'number', { min: 1, max: 120 }), field('style', t('aiVideo.fields.style')), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'ACTIVE', 'ARCHIVED']) })], columns: [['code', '编码'], ['name', '名称'], ['status', '状态'], ['aspectRatio', '画幅'], ['resolution', '分辨率'], ['updatedAt', '更新时间']] },
  scripts: { resource: 'scripts', permission: 'script', fields: [baseProject(), field('title', t('aiVideo.fields.title')), field('content', t('aiVideo.fields.content'), 'textarea', { rows: 8 }), field('version', t('aiVideo.fields.version'), 'number', { min: 1, max: 10000 }), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'READY', 'ARCHIVED']) })], columns: [['title', '标题'], ['projectId', '项目'], ['version', '版本'], ['status', '状态'], ['updatedAt', '更新时间']] },
  storyboards: { resource: 'storyboards', permission: 'storyboard', fields: [baseProject(), field('shotNo', t('aiVideo.fields.shotNo'), 'number', { min: 1, max: 100000 }), field('title', t('aiVideo.fields.title')), field('sceneDescription', t('aiVideo.fields.sceneDescription'), 'textarea'), field('dialogue', t('aiVideo.fields.dialogue'), 'textarea'), field('narration', t('aiVideo.fields.narration'), 'textarea'), field('camera', t('aiVideo.fields.camera')), field('durationMs', t('aiVideo.fields.durationMs'), 'number', { min: 100, max: 3600000 }), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'READY', 'LOCKED']) })], columns: [['shotNo', '镜头'], ['title', '标题'], ['camera', '机位'], ['durationMs', '时长(ms)'], ['status', '状态']] },
  characters: { resource: 'assets', permission: 'character', fields: [baseProject(), field('name', t('aiVideo.fields.name')), field('description', t('aiVideo.fields.description'), 'textarea'), field('previewUrl', t('aiVideo.fields.previewUrl')), field('resourceUrl', t('aiVideo.fields.resourceUrl')), field('tags', t('aiVideo.fields.tags')), field('status', t('common.status'), 'select', { options: statusOptions(['DRAFT', 'READY', 'ARCHIVED']) })], columns: [['name', '名称'], ['projectId', '项目'], ['tags', '标签'], ['status', '状态'], ['updatedAt', '更新时间']] },
  scenes: { resource: 'assets', permission: 'scene', fields: [], columns: [] }, items: { resource: 'assets', permission: 'item', fields: [], columns: [] }, costumes: { resource: 'assets', permission: 'costume', fields: [], columns: [] }, motions: { resource: 'assets', permission: 'motion', fields: [], columns: [] }, styles: { resource: 'assets', permission: 'style', fields: [], columns: [] }, voices: { resource: 'assets', permission: 'voice', fields: [], columns: [] }, sounds: { resource: 'assets', permission: 'sound', fields: [], columns: [] },
  tasks: { resource: 'tasks', permission: 'task', fields: [baseProject(), field('taskType', t('aiVideo.fields.taskType'), 'select', { options: taskTypeOptions(['SCRIPT', 'IMAGE', 'VIDEO', 'TTS', 'MUSIC', 'UPSCALE']) }), field('name', t('aiVideo.fields.name')), field('prompt', t('aiVideo.fields.prompt'), 'textarea', { rows: 6 }), field('providerRouteId', t('aiVideo.fields.providerRouteId'), 'number', { min: 1, max: 100000000 }), field('inputJson', t('aiVideo.fields.inputJson'), 'textarea')], columns: [['name', '名称'], ['taskType', '类型'], ['status', '状态'], ['progress', '进度'], ['errorMessage', '错误'], ['createdAt', '创建时间']] },
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
const isTaskModule = computed(() => current.value.resource === 'tasks')
const canReview = computed(() => auth.isAdmin || auth.hasPermission('ai:video:review:create'))
const assetType = computed(() => assetTypes[sectionKey.value] || '')
const assetTypeLabel = computed(() => assetType.value ? t(`aiVideo.sections.${sectionKey.value}.title`) : '')
const section = computed(() => ({ title: t(`aiVideo.sections.${sectionKey.value}.title`), description: t(`aiVideo.sections.${sectionKey.value}.description`) }))
const columns = computed(() => current.value.columns.map(([key, label]) => ({ key, label: key.endsWith('At') ? t('common.time') : label })))
const configKey = computed(() => configurationKey(sectionKey.value))
const configurationValid = computed(() => { try { parseConfiguration(form[configKey.value]); return true } catch { return false } })
const dialogTitle = computed(() => t(editingId.value ? 'aiVideo.editor.editTitle' : 'aiVideo.editor.createTitle', { module: section.value.title }))
const isProjectCreate = computed(() => current.value.resource === 'projects' && !editingId.value)
const showCreationGuide = computed(() => !editingId.value && !!modules[sectionKey.value] && sectionKey.value !== 'projects')
/** 按实际业务模块提供准备、填写和后续使用说明，分类页与新建弹窗共用。 */
const creationGuide = computed(() => {
  if (!modules[sectionKey.value] || sectionKey.value === 'projects') return { purpose: '', example: '', steps: [] }
  const prefix = `aiVideo.creation.modules.${sectionKey.value}`
  return {
    purpose: t(`${prefix}.purpose`), example: t(`${prefix}.example`),
    steps: ['prepare', 'fill', 'next'].map(key => ({ key, title: t(`aiVideo.creation.labels.${key}`), description: t(`${prefix}.${key}`) }))
  }
})
/** 为首次创建项目提供按填写顺序组织的说明，避免用户只看到一组空字段。 */
const projectCreateGuide = computed(() => [
  { key: 'identity', index: '01', title: t('aiVideo.editor.projectGuide.identityTitle'), description: t('aiVideo.editor.projectGuide.identityDescription'), fields: t('aiVideo.editor.projectGuide.identityFields') },
  { key: 'output', index: '02', title: t('aiVideo.editor.projectGuide.outputTitle'), description: t('aiVideo.editor.projectGuide.outputDescription'), fields: t('aiVideo.editor.projectGuide.outputFields') },
  { key: 'direction', index: '03', title: t('aiVideo.editor.projectGuide.directionTitle'), description: t('aiVideo.editor.projectGuide.directionDescription'), fields: t('aiVideo.editor.projectGuide.directionFields') }
])
// 在原接口字段之上增强布局与输入方式，分类属性使用现有 JSON 扩展字段。
const formGroups = computed(() => {
  const advanced = ['version', 'status', 'providerRouteId', 'renderId', 'externalId', 'errorMessage', 'inputJson', 'configJson', 'metadataJson']
  const basic = ['projectId', 'code', 'name', 'title', 'taskType', 'videoId', 'channel', 'shotNo']
  const presets = { aspectRatio: ['16:9', '9:16', '1:1', '4:3'], resolution: ['1920x1080', '1080x1920', '1280x720', '1080x1080'], fps: [24, 25, 30, 60], camera: ['close', 'medium', 'wide'] }
  const fields = current.value.fields.map(item => {
    const result = { ...item, group: advanced.includes(item.key) ? 'advanced' : basic.includes(item.key) ? 'basic' : 'content', wide: item.type === 'textarea', maxlength: fieldMaxLength(item.key) }
    if (presets[item.key]) Object.assign(result, { type: 'select', options: presets[item.key].map(value => ({ value, label: item.key === 'camera' ? t('aiVideo.editor.camera.' + value) : String(value) })), custom: item.key !== 'fps' })
    if (item.key.endsWith('Url')) Object.assign(result, { maxlength: 500, hint: t('aiVideo.editor.urlHint'), placeholder: 'https://…', wide: true })
    if (['coverUrl', 'previewUrl'].includes(item.key)) result.media = 'image'
    if (item.key === 'mediaUrl' || item.key === 'resourceUrl') result.media = ['voices', 'sounds'].includes(sectionKey.value) ? 'audio' : sectionKey.value === 'motions' || item.key === 'mediaUrl' ? 'video' : 'image'
    if (isProjectCreate.value && current.value.resource === 'projects') {
      const guidance = {
        code: { hint: t('aiVideo.editor.projectGuide.codeHint'), placeholder: t('aiVideo.editor.projectGuide.codePlaceholder') },
        name: { hint: t('aiVideo.editor.projectGuide.nameHint'), placeholder: t('aiVideo.editor.projectGuide.namePlaceholder') },
        description: { hint: t('aiVideo.editor.projectGuide.descriptionHint'), placeholder: t('aiVideo.editor.projectGuide.descriptionPlaceholder') },
        aspectRatio: { hint: t('aiVideo.editor.projectGuide.aspectRatioHint') },
        resolution: { hint: t('aiVideo.editor.projectGuide.resolutionHint') },
        fps: { hint: t('aiVideo.editor.projectGuide.fpsHint') },
        style: { hint: t('aiVideo.editor.projectGuide.styleHint'), placeholder: t('aiVideo.editor.projectGuide.stylePlaceholder') },
        status: { hint: t('aiVideo.editor.projectGuide.statusHint') }
      }[item.key]
      if (guidance) Object.assign(result, guidance)
    }
    return result
  })
  const attributes = assetAttributes[sectionKey.value] || []
  for (const key of attributes) fields.push({ key, label: t('aiVideo.editor.attributes.' + key), type: 'input', config: true, group: 'content', maxlength: 500 })
  if (configKey.value && !fields.some(item => item.key === configKey.value)) fields.push({ key: configKey.value, label: t('aiVideo.editor.extended'), type: 'textarea', group: 'advanced', wide: true, maxlength: 20000 })
  if (['tasks', 'renders'].includes(sectionKey.value)) {
    const options = sectionKey.value === 'renders' ? ['STORYBOARD', 'TEMPLATE'] : []
    if (options.length) fields.push({ key: 'mode', label: t('aiVideo.editor.renderMode'), type: 'select', options: options.map(value => ({ value, label: t('aiVideo.editor.' + value) })), config: true, group: 'content' })
    for (const key of (sectionKey.value === 'renders' || ['VIDEO', 'IMAGE', 'UPSCALE'].includes(form.taskType) ? ['aspectRatio', 'resolution'] : [])) fields.push({ key, label: t('aiVideo.fields.' + key), type: 'select', options: presets[key].map(value => ({ value, label: value })), custom: true, config: true, group: 'content' })
  }
  // 新建时显示与真实字段匹配的示例，示例只作提示，不写入表单或覆盖已有数据。
  if (showCreationGuide.value) {
    for (const item of fields) {
      const category = item.config && attributes.includes(item.key) ? 'attributes' : 'hints'
      item.hint = t(`aiVideo.creation.${category}.${item.key}`)
      if (['name', 'title'].includes(item.key)) item.placeholder = creationGuide.value.example
    }
  }
  return ['basic', 'content', 'advanced'].map((key, index) => ({ key, number: index + 1, title: t('aiVideo.editor.' + key), hint: t('aiVideo.editor.' + key + 'Hint'), fields: fields.filter(item => item.group === key) })).filter(group => group.fields.length)
})

/** 读取普通字段或扩展对象字段；损坏 JSON 留在高级区供用户修正。 */
function fieldValue(item) {
  if (!item.config) return form[item.key]
  try { return parseConfiguration(form[configKey.value])[item.key] ?? '' } catch { return '' }
}

/** 双向同步可视化配置和高级 JSON，保证只有一份配置数据。 */
function setFieldValue(item, value) {
  if (item.config) form[configKey.value] = updateConfiguration(form[configKey.value], item.key, value)
  else form[item.key] = value
}

/** 按后端约束限制输入长度，减少提交后才发现错误的情况。 */
function fieldMaxLength(key) {
  return ({ code: 80, name: current.value.resource === 'projects' ? 160 : 200, title: 200, description: 1000, tags: 500, prompt: 10000, inputJson: 20000, configJson: 20000, sceneDescription: 2000, dialogue: 5000, narration: 5000, camera: 120, style: 120, channel: 64, comment: 1000, errorMessage: 1000, externalId: 200 })[key]
}

/** 关闭前保护未保存内容，保存过程中禁止关闭编辑器。 */
async function closeEditor(done) {
  if (saving.value) return
  if (JSON.stringify(form) !== initialForm) {
    try { await ElMessageBox.confirm(t('aiVideo.editor.discardHint'), t('aiVideo.editor.discardTitle'), { type: 'warning' }) } catch { return }
  }
  if (typeof done === 'function') done()
  else dialogVisible.value = false
}

/** 每次打开表单清理上次错误、预览和高级区展开状态。 */
function resetEditor() {
  saveError.value = ''
  previewField.value = ''
  advancedOpen.value = !configurationValid.value
  initialForm = JSON.stringify(form)
  dialogVisible.value = true
}

const availableStatuses = computed(() => [...new Set(rows.value.map(row => row.status).filter(Boolean))])
const filteredRows = computed(() => {
  const search = keyword.value.trim().toLowerCase()
  return rows.value.filter(row => {
    if (statusFilter.value && row.status !== statusFilter.value) return false
    if (!search) return true
    return Object.values(row).some(value => String(value ?? '').toLowerCase().includes(search))
  })
})
const taskMetrics = computed(() => [
  { key: 'total', label: t('aiVideo.taskBoard.total'), value: rows.value.length, tone: 'neutral' },
  { key: 'running', label: t('aiVideo.taskBoard.running'), value: rows.value.filter(row => row.status === 'RUNNING').length, tone: 'active' },
  { key: 'queued', label: t('aiVideo.taskBoard.queued'), value: rows.value.filter(row => row.status === 'QUEUED').length, tone: 'queued' },
  { key: 'failed', label: t('aiVideo.taskBoard.failed'), value: rows.value.filter(row => ['FAILED', 'CANCELLED'].includes(row.status)).length, tone: 'danger' }
])
const summary = computed(() => [
  { key: 'projects', label: t('aiVideo.summary.projects'), hint: t('aiVideo.summary.projectsHint') },
  { key: 'assets', label: t('aiVideo.summary.assets'), hint: t('aiVideo.summary.assetsHint') },
  { key: 'tasks', label: t('aiVideo.summary.tasks'), hint: t('aiVideo.summary.tasksHint') },
  { key: 'videos', label: t('aiVideo.summary.videos'), hint: t('aiVideo.summary.videosHint') }
])

/** 根据当前统计判断引导环节是否已具备、正在处理或等待前置步骤。 */
function guideStepStatus(key) {
  const hasProject = Number(overview.projects) > 0
  const hasAsset = Number(overview.assets) > 0
  const hasTask = Number(overview.tasks) > 0
  const hasVideo = Number(overview.videos) > 0
  const ready = { project: hasProject, content: hasProject && hasTask, asset: hasAsset, generate: hasTask, publish: hasVideo }
  const available = { project: true, content: hasProject, asset: hasProject, generate: hasProject && (hasAsset || hasTask), publish: hasVideo || hasTask }
  if (ready[key]) return 'done'
  return available[key] ? 'current' : 'locked'
}

/** 构造首页的详细制作步骤，把每个跳转入口解释为可执行的工作成果。 */
const workflowSteps = computed(() => [
  { key: 'project', title: t('aiVideo.steps.project'), description: t('aiVideo.steps.projectHint'), goal: t('aiVideo.steps.projectGoal'), prepare: t('aiVideo.steps.projectPrepare'), action: t('aiVideo.steps.projectAction'), output: t('aiVideo.steps.projectOutput'), checks: [t('aiVideo.steps.projectCheckOne'), t('aiVideo.steps.projectCheckTwo'), t('aiVideo.steps.projectCheckThree')], cta: t('aiVideo.steps.projectCta'), path: 'projects' },
  { key: 'content', title: t('aiVideo.steps.content'), description: t('aiVideo.steps.contentHint'), goal: t('aiVideo.steps.contentGoal'), prepare: t('aiVideo.steps.contentPrepare'), action: t('aiVideo.steps.contentAction'), output: t('aiVideo.steps.contentOutput'), checks: [t('aiVideo.steps.contentCheckOne'), t('aiVideo.steps.contentCheckTwo'), t('aiVideo.steps.contentCheckThree')], cta: t('aiVideo.steps.contentCta'), path: 'scripts' },
  { key: 'asset', title: t('aiVideo.steps.asset'), description: t('aiVideo.steps.assetHint'), goal: t('aiVideo.steps.assetGoal'), prepare: t('aiVideo.steps.assetPrepare'), action: t('aiVideo.steps.assetAction'), output: t('aiVideo.steps.assetOutput'), checks: [t('aiVideo.steps.assetCheckOne'), t('aiVideo.steps.assetCheckTwo'), t('aiVideo.steps.assetCheckThree')], cta: t('aiVideo.steps.assetCta'), path: 'assets/items' },
  { key: 'generate', title: t('aiVideo.steps.generate'), description: t('aiVideo.steps.generateHint'), goal: t('aiVideo.steps.generateGoal'), prepare: t('aiVideo.steps.generatePrepare'), action: t('aiVideo.steps.generateAction'), output: t('aiVideo.steps.generateOutput'), checks: [t('aiVideo.steps.generateCheckOne'), t('aiVideo.steps.generateCheckTwo'), t('aiVideo.steps.generateCheckThree')], cta: t('aiVideo.steps.generateCta'), path: 'tasks' },
  { key: 'publish', title: t('aiVideo.steps.publish'), description: t('aiVideo.steps.publishHint'), goal: t('aiVideo.steps.publishGoal'), prepare: t('aiVideo.steps.publishPrepare'), action: t('aiVideo.steps.publishAction'), output: t('aiVideo.steps.publishOutput'), checks: [t('aiVideo.steps.publishCheckOne'), t('aiVideo.steps.publishCheckTwo'), t('aiVideo.steps.publishCheckThree')], cta: t('aiVideo.steps.publishCta'), path: 'reviews' }
].map(step => ({ ...step, status: guideStepStatus(step.key), statusLabel: t(`aiVideo.guide.status.${guideStepStatus(step.key)}`) })))
const guideCompletedCount = computed(() => workflowSteps.value.filter(step => step.status === 'done').length)
const guideProgress = computed(() => Math.round((guideCompletedCount.value / workflowSteps.value.length) * 100))
const guideNextStep = computed(() => workflowSteps.value.find(step => step.status !== 'done'))

/** 展示开始制作前需要确认的输入，减少用户在空页面中反复试错。 */
const guidePreparation = computed(() => [
  { key: 'brief', icon: '01', title: t('aiVideo.guide.preparation.briefTitle'), description: t('aiVideo.guide.preparation.briefDescription'), action: t('aiVideo.guide.preparation.briefAction'), path: 'projects' },
  { key: 'model', icon: '02', title: t('aiVideo.guide.preparation.modelTitle'), description: t('aiVideo.guide.preparation.modelDescription'), action: t('aiVideo.guide.preparation.modelAction'), path: 'tasks' },
  { key: 'materials', icon: '03', title: t('aiVideo.guide.preparation.materialsTitle'), description: t('aiVideo.guide.preparation.materialsDescription'), action: t('aiVideo.guide.preparation.materialsAction'), path: 'assets/items' },
  { key: 'delivery', icon: '04', title: t('aiVideo.guide.preparation.deliveryTitle'), description: t('aiVideo.guide.preparation.deliveryDescription'), action: t('aiVideo.guide.preparation.deliveryAction'), path: 'publishes' }
])

/** 展示生成之后的质量与发布检查，帮助用户明确成片何时可以交付。 */
const guideDelivery = computed(() => [
  { key: 'task', index: '01', title: t('aiVideo.guide.delivery.taskTitle'), description: t('aiVideo.guide.delivery.taskDescription'), check: t('aiVideo.guide.delivery.taskCheck'), action: t('aiVideo.guide.delivery.taskAction'), path: 'tasks' },
  { key: 'video', index: '02', title: t('aiVideo.guide.delivery.videoTitle'), description: t('aiVideo.guide.delivery.videoDescription'), check: t('aiVideo.guide.delivery.videoCheck'), action: t('aiVideo.guide.delivery.videoAction'), path: 'videos' },
  { key: 'review', index: '03', title: t('aiVideo.guide.delivery.reviewTitle'), description: t('aiVideo.guide.delivery.reviewDescription'), check: t('aiVideo.guide.delivery.reviewCheck'), action: t('aiVideo.guide.delivery.reviewAction'), path: 'reviews' },
  { key: 'publish', index: '04', title: t('aiVideo.guide.delivery.publishTitle'), description: t('aiVideo.guide.delivery.publishDescription'), check: t('aiVideo.guide.delivery.publishCheck'), action: t('aiVideo.guide.delivery.publishAction'), path: 'publishes' }
])
const productionPaths = computed(() => [
  { key: 'script', icon: '剧', title: t('aiVideo.paths.scriptTitle'), description: t('aiVideo.paths.scriptHint'), next: t('aiVideo.paths.scriptNext'), route: 'ai-script', source: 'script' },
  { key: 'text', icon: '文', title: t('aiVideo.paths.textTitle'), description: t('aiVideo.paths.textHint'), next: t('aiVideo.paths.textNext'), route: 'ai-generation', type: 'video_model', generationMode: 'TEXT_TO_VIDEO', source: 'text' },
  { key: 'storyboard', icon: '镜', title: t('aiVideo.paths.storyboardTitle'), description: t('aiVideo.paths.storyboardHint'), next: t('aiVideo.paths.storyboardNext'), route: 'ai-generation', type: 'video_model', generationMode: 'STORYBOARD_TO_VIDEO', source: 'storyboard' },
  { key: 'asset', icon: '物', title: t('aiVideo.paths.assetTitle'), description: t('aiVideo.paths.assetHint'), next: t('aiVideo.paths.assetNext'), route: 'ai-generation', type: 'video_model', generationMode: 'REFERENCE_IMAGE_TO_VIDEO', source: 'asset' },
  { key: 'audio', icon: '声', title: t('aiVideo.paths.audioTitle'), description: t('aiVideo.paths.audioHint'), next: t('aiVideo.paths.audioNext'), route: 'tasks', mode: 'TTS' },
  { key: 'template', icon: '模', title: t('aiVideo.paths.templateTitle'), description: t('aiVideo.paths.templateHint'), next: t('aiVideo.paths.templateNext'), route: 'renders', mode: 'TEMPLATE' }
])

/** 判断当前用户是否拥有指定资源操作权限。 */
function can(action) {
  if (auth.isAdmin) return true
  return auth.hasPermission(`ai:video:${current.value.permission}:${action}`)
}

/** 跳转到制作流程中的指定模块。 */
function goTo(path, mode, type, generationMode, source) {
  if (path === 'ai-script') {
    const query = route.query.projectId ? { projectId: route.query.projectId } : {}
    router.push({ path: '/ai-script', query })
    return
  }
  if (path === 'ai-generation') {
    const query = { type, mode: generationMode, source }
    if (route.query.projectId) query.projectId = route.query.projectId
    if (route.query.storyboardId) query.storyboardId = route.query.storyboardId
    router.push({ path: '/ai-generation', query })
    return
  }
  router.push({ path: `/ai-video/${path}`, query: mode ? { mode } : {} })
}

/** 将分镜字段整理成生成页可直接使用的提示词，并保留项目与分镜上下文。 */
function generateStoryboard(row) {
  if (!row?.id) return
  const prompt = [
    row.sceneDescription,
    row.dialogue ? `对白：${row.dialogue}` : '',
    row.narration ? `旁白：${row.narration}` : '',
    row.camera ? `机位：${row.camera}` : '',
    row.durationMs ? `镜头时长：${row.durationMs}ms` : ''
  ].filter(Boolean).join('\n')
  const query = {
    type: 'video_model',
    mode: 'STORYBOARD_TO_VIDEO',
    source: 'storyboard',
    projectId: row.projectId,
    storyboardId: row.id
  }
  if (prompt) query.prompt = prompt
  router.push({ path: '/ai-generation', query })
}

/** 打开安全的视频预览地址，拒绝脚本协议和协议相对地址。 */
function previewVideo(row) {
  const url = safeMediaUrl(row?.mediaUrl)
  if (!url) {
    ElMessage.warning(t('aiVideo.editor.previewFailed'))
    return
  }
  videoPreviewUrl.value = url
  videoPreviewTitle.value = `${row.title || t('aiVideo.sections.videos.title')} · ${t('aiVideo.previewVideo')}`
  videoPreviewVisible.value = true
}

/** 清理视频预览状态，避免关闭弹窗后继续持有媒体地址。 */
function closeVideoPreview() {
  videoPreviewVisible.value = false
  videoPreviewUrl.value = ''
  videoPreviewTitle.value = ''
}

/** 为成片提供带权限检查的快速审核入口，并刷新审核后的成片状态。 */
async function submitQuickReview(row, status) {
  if (!canReview.value || !row?.id) return
  const approved = status === 'APPROVED'
  try {
    await ElMessageBox.confirm(
      t(approved ? 'aiVideo.quickReviewApproveConfirm' : 'aiVideo.quickReviewRejectConfirm'),
      t(approved ? 'aiVideo.approveVideo' : 'aiVideo.rejectVideo'),
      { type: approved ? 'success' : 'warning' }
    )
    await reviewAiVideo(row.id, { status })
    await load()
    ElMessage.success(t('aiVideo.quickReviewSaved'))
  } catch (error) {
    if (error === 'cancel' || error === 'close') return
    ElMessage.error(t('aiVideo.quickReviewFailed'))
  }
}

/** 判断当前页面是否允许读取项目选择项。 */
function canListProjects() {
  return auth.isAdmin || auth.hasPermission('ai:video:project:list')
}

/** 为页面读取增加上限，后端异常或网络半开连接时及时交给页面错误态处理。 */
function withLoadTimeout(promise, label) {
  let timer
  const timeout = new Promise((resolve, reject) => {
    timer = window.setTimeout(() => reject(new Error(`${label} load timeout`)), loadTimeoutMs)
  })
  return Promise.race([promise, timeout]).finally(() => window.clearTimeout(timer))
}

/** 加载工作台统计、项目选择项和当前业务列表，避免无关权限导致整页失败。 */
async function load() {
  const requestId = ++loadRequestId
  loading.value = true
  loadError.value = false
  if (!isDashboard.value && !isAnalytics.value) rows.value = []

  const dashboardPermission = auth.isAdmin || auth.hasPermission('ai:video:dashboard:list')
  const canReadCurrent = !isDashboard.value && !isAnalytics.value && can('list')
  const currentResource = current.value.resource
  const currentParams = assetType.value ? { assetType: assetType.value } : {}

  // 项目选项只服务于弹窗，独立加载失败不应阻塞当前页面列表。
  if (canListProjects()) {
    withLoadTimeout(listAiVideoResource('projects'), 'projects')
      .then(data => { if (requestId === loadRequestId) projects.value = data })
      .catch(() => {})
  }

  let criticalRequest
  if ((isDashboard.value || isAnalytics.value) && dashboardPermission) {
    criticalRequest = withLoadTimeout(getAiVideoOverview(), 'overview')
      .then(data => { if (requestId === loadRequestId) Object.assign(overview, data) })
  } else if (canReadCurrent) {
    criticalRequest = withLoadTimeout(listAiVideoResource(currentResource, currentParams), currentResource)
      .then(data => { if (requestId === loadRequestId) rows.value = data })
  }

  try {
    if (criticalRequest) await criticalRequest
    if (requestId !== loadRequestId) return
    if (route.query.mode && canReadCurrent && can('create')) {
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
  if (saving.value || !can('create')) return
  editingId.value = null
  Object.keys(form).forEach(key => delete form[key])
  current.value.fields.forEach(item => { form[item.key] = item.type === 'number' ? (item.min || 0) : item.type === 'select' ? item.options[0]?.value : item.type === 'project' ? null : '' })
  if (configKey.value) form[configKey.value] = ''
  if (current.value.resource === 'projects') Object.assign(form, { aspectRatio: '16:9', resolution: '1920x1080', fps: 25 })
  for (const key of ['providerRouteId', 'renderId']) if (key in form) form[key] = null
  if ('durationMs' in form) form.durationMs = current.value.resource === 'storyboards' ? 5000 : 0
  if (current.value.resource === 'tasks' && ['SCRIPT', 'IMAGE', 'VIDEO', 'TTS', 'MUSIC', 'UPSCALE'].includes(mode)) form.taskType = mode
  if (current.value.resource === 'renders' && ['STORYBOARD', 'TEMPLATE'].includes(mode)) form.configJson = JSON.stringify({ mode }, null, 2)
  resetEditor()
}

/** 将列表行复制到编辑表单。 */
function openEdit(row) {
  if (saving.value || !can('update')) return
  editingId.value = row.id
  Object.keys(form).forEach(key => delete form[key])
  current.value.fields.forEach(item => { form[item.key] = row[item.key] ?? (['providerRouteId', 'renderId'].includes(item.key) ? null : item.type === 'number' ? (item.min || 0) : item.type === 'project' ? null : '') })
  if (configKey.value) form[configKey.value] = row[configKey.value] || ''
  resetEditor()
}

/** 保存新增或编辑的业务资源。 */
async function save() {
  if (saving.value || !can(editingId.value ? 'update' : 'create')) return
  saveError.value = ''
  // 提交期间即使切换模块，也必须使用提交开始时的资源和分类。
  const resource = current.value.resource
  const targetId = editingId.value
  const targetType = assetType.value
  let payload
  try { payload = prepareVideoPayload(form, sectionKey.value) } catch (error) {
    advancedOpen.value = true
    saveError.value = t(error.message === 'configTooLong' ? 'aiVideo.editor.configTooLong' : 'aiVideo.editor.invalidJson')
    return
  }
  saving.value = true
  try { await formRef.value?.validate() } catch { advancedOpen.value = true; saving.value = false; return }
  try {
    if (targetId) await updateAiVideoResource(resource, targetId, payload)
    else await createAiVideoResource(resource, payload, targetType)
    dialogVisible.value = false
    await load()
    ElMessage.success(t('common.successSaved'))
  } catch { saveError.value = t('aiVideo.editor.saveFailed') } finally { saving.value = false }
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
  const rules = []
  if (required[current.value.resource]?.includes(item.key)) rules.push({ required: true, type: ['project', 'number'].includes(item.type) ? 'number' : 'string', whitespace: !['project', 'number'].includes(item.type), message: t('aiVideo.fieldRequired', { field: item.label }), trigger: ['blur', 'change'] })
  if (item.key.endsWith('Url')) rules.push({ validator: (_rule, value, callback) => callback(!value || safeMediaUrl(value) ? undefined : new Error(t('aiVideo.editor.invalidUrl'))), trigger: ['blur', 'change'] })
  return rules
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

/** 将任务类型转换为卡片上的短标签。 */
function taskTypeLabel(value) { return value ? t(`aiVideo.taskTypes.${value}`) : t('aiVideo.taskBoard.unknownType') }

/** 为任务类型提供稳定、易扫读的字母标识。 */
function taskTypeIcon(value) { return ({ VIDEO: 'V', IMAGE: 'I', TTS: 'A', MUSIC: 'M', SCRIPT: 'S', UPSCALE: 'U' })[value] || '·' }

/** 将后端进度限制在进度条可接受的范围内。 */
function taskProgress(row) { return Math.min(100, Math.max(0, Number(row.progress) || 0)) }

/** 映射任务状态到 Element Plus 的语义色。 */
function taskStatusType(status) { return ({ RUNNING: 'primary', QUEUED: 'warning', SUCCEEDED: 'success', FAILED: 'danger', CANCELLED: 'info' })[status] || 'info' }

/** 映射任务状态到进度条终态。 */
function taskProgressStatus(status) { return status === 'SUCCEEDED' ? 'success' : ['FAILED', 'CANCELLED'].includes(status) ? 'exception' : undefined }

/** 生成任务卡片摘要并优先展示失败原因。 */
function taskDescription(row) { return row.errorMessage || row.prompt || t('aiVideo.taskBoard.noPrompt') }

watch(() => route.path, () => { dialogVisible.value = false })
watch(() => route.fullPath, () => { resetFilters(); load() })
onMounted(load)
onBeforeUnmount(closeVideoPreview)
</script>

<style scoped>
.editor-intro { display: flex; align-items: center; gap: 12px; padding: 0 0 20px; }
.editor-intro p, .editor-section-hint, .editor-field-hint { margin: 0; color: var(--app-muted); font-size: 12px; line-height: 1.7; }
.editor-badge { flex-shrink: 0; padding: 5px 10px; border-radius: 8px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; }
.asset-guide, .creation-guide { margin: 16px 0; padding: 16px; border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-surface-soft); }
.asset-guide h3, .creation-guide h3 { margin: 0; font-size: 15px; color: var(--app-text); }
.asset-guide p, .creation-guide p { margin: 8px 0; color: var(--app-muted); font-size: 12px; line-height: 1.7; overflow-wrap: anywhere; }
.asset-guide > small { display: block; margin-top: 12px; color: var(--app-muted); }
.creation-guide-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 12px; }
.creation-guide-grid article { min-width: 0; padding: 12px; border: 1px solid var(--app-border); border-radius: 8px; }
.creation-guide-grid strong { font-size: 13px; color: var(--app-text); }
.creation-guide-grid strong span { color: var(--el-color-primary); }
@media (max-width: 760px) { .creation-guide-grid { grid-template-columns: 1fr; } }
.project-create-guide { margin-bottom: 16px; padding: 16px; border: 1px solid #cbdcf8; border-radius: 12px; background: linear-gradient(135deg, #f5f8ff, #fbfdff); }
.project-create-guide-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 16px; }
.project-create-guide-kicker { color: var(--app-primary); font-size: 11px; font-weight: 700; letter-spacing: .08em; text-transform: uppercase; }
.project-create-guide-head h3 { margin: 4px 0 0; color: var(--app-text); font-size: 16px; }
.project-create-guide-head p { max-width: 680px; margin: 6px 0 0; color: var(--app-muted); font-size: 12px; line-height: 1.6; }
.project-create-guide-steps { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 10px; margin-top: 14px; }
.project-create-guide-step { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 9px; padding: 12px; border: 1px solid #dce7f8; border-radius: 10px; background: rgb(255 255 255 / 76%); }
.project-create-guide-index { display: grid; width: 26px; height: 26px; place-items: center; border-radius: 8px; background: #e5efff; color: var(--app-primary); font-size: 10px; font-weight: 700; }
.project-create-guide-step strong { color: var(--app-text); font-size: 13px; }
.project-create-guide-step p, .project-create-guide-step small { display: block; color: var(--app-muted); font-size: 11px; line-height: 1.5; }
.project-create-guide-step p { margin: 4px 0; }
.project-create-guide-step small { color: var(--app-primary); }
.project-create-guide-next { display: flex; flex-wrap: wrap; gap: 5px 10px; margin-top: 12px; padding-top: 11px; border-top: 1px solid #dce7f8; color: var(--app-muted); font-size: 11px; line-height: 1.5; }
.project-create-guide-next strong { color: var(--app-text); }
.editor-section { margin-bottom: 16px; padding: 20px; border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-surface-soft); }
.editor-section-heading { display: flex; align-items: center; gap: 10px; }
.editor-section-heading span { display: grid; place-items: center; width: 24px; height: 24px; border-radius: 7px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-weight: 600; }
.editor-section-heading h3 { margin: 0; color: var(--app-text); font-size: 15px; }
.editor-section-hint { margin-top: 6px; margin-bottom: 16px; }
.editor-field-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 0 20px; }
.editor-field-grid > * { min-width: 0; }
.editor-field-grid .field-wide { grid-column: 1 / -1; }
.editor-section-toggle { display: flex; justify-content: space-between; width: 100%; padding: 0; border: 0; background: transparent; color: var(--app-text); font: inherit; font-weight: 600; cursor: pointer; }
.editor-field-hint { width: 100%; margin-top: 5px; }
.editor-preview { width: 100%; margin-top: 10px; }
.editor-preview img, .editor-preview video { display: block; max-width: 100%; max-height: 220px; border-radius: 8px; }
.editor-preview audio { width: 100%; }
.ai-video-preview-player { display: block; width: 100%; max-height: min(70vh, 680px); border-radius: 10px; background: #05070b; object-fit: contain; }
.editor-footer { display: flex; justify-content: space-between; align-items: center; gap: 16px; }
.editor-footer > span { color: var(--app-muted); font-size: 12px; }
.editor-alert { margin-bottom: 16px; }
.ai-video-form { max-height: 62vh; overflow-y: auto; padding-right: 8px; }
@media (max-width: 760px) { .project-create-guide-steps { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .editor-field-grid { grid-template-columns: 1fr; } .editor-section { padding: 14px; } .editor-intro { align-items: flex-start; flex-direction: column; } .project-create-guide { padding: 14px; } .project-create-guide-head { flex-direction: column; gap: 8px; } .editor-footer > span { display: none; } .editor-footer { justify-content: flex-end; } }

.ai-video-page { display: grid; gap: 18px; }
.ai-video-hero { display: flex; align-items: center; justify-content: space-between; min-height: 160px; overflow: hidden; background: linear-gradient(125deg, #101b3a, #263d7a 58%, #4c78bc); color: #fff; }
.ai-video-hero h2 { margin: 10px 0 8px; font-size: clamp(26px, 4vw, 42px); letter-spacing: -.03em; }
.ai-video-hero p { max-width: 720px; margin: 0; color: rgb(239 245 255 / 82%); }
.ai-video-hero-actions { display: flex; align-items: center; gap: 18px; }
.ai-video-hero-actions :deep(.el-button) { border-color: rgb(255 255 255 / 55%); }
.ai-video-hero-actions :deep(.ai-video-start-button) { border-color: #fff; background: #fff; color: #24447d; font-weight: 700; }
.ai-video-hero-actions :deep(.ai-video-start-button:hover) { border-color: #edf3ff; background: #edf3ff; color: #1f3b70; }
.ai-video-hero-actions :deep(.ai-video-start-button:focus-visible) { outline: 2px solid #fff; outline-offset: 3px; }
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
.task-workspace { min-height: 360px; }
.task-metrics { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; margin-bottom: 16px; }
.task-metric { display: flex; align-items: center; justify-content: space-between; min-height: 58px; padding: 12px 14px; border: 1px solid var(--app-border); border-radius: 12px; background: var(--app-surface-soft); }
.task-metric span { color: var(--app-muted); font-size: 12px; }
.task-metric strong { color: var(--app-text); font-size: 22px; line-height: 1; }
.task-metric.is-active { border-color: #bfd7ff; background: #f1f6ff; }
.task-metric.is-active strong { color: #2563eb; }
.task-metric.is-queued { border-color: #f4ddb1; background: #fffaf0; }
.task-metric.is-queued strong { color: #b7791f; }
.task-metric.is-danger { border-color: #f4c6c6; background: #fff6f6; }
.task-metric.is-danger strong { color: #d14343; }
.task-grid { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 14px; }
.task-card { display: flex; min-width: 0; flex-direction: column; gap: 14px; padding: 16px; border: 1px solid var(--app-border); border-radius: 14px; background: #fff; box-shadow: 0 5px 16px rgb(18 39 75 / 5%); transition: border-color .16s ease, box-shadow .16s ease, transform .16s ease; }
.task-card:hover { border-color: #b7c9e8; box-shadow: 0 10px 24px rgb(18 39 75 / 9%); transform: translateY(-2px); }
.task-card-head, .task-progress-head, .task-card-meta, .task-card-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; }
.task-type { display: inline-flex; min-width: 0; align-items: center; gap: 7px; color: var(--app-muted); font-size: 12px; font-weight: 600; }
.task-type b { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 9px; background: #edf3ff; color: var(--app-primary); font-size: 12px; }
.task-type-video b { background: #eaf1ff; color: #3b6fdb; }
.task-type-image b { background: #e9faf4; color: #138a68; }
.task-type-tts b, .task-type-music b { background: #fff3df; color: #b7791f; }
.task-card-body { min-width: 0; }
.task-card-body h4 { overflow: hidden; margin: 0 0 7px; color: var(--app-text); font-size: 16px; text-overflow: ellipsis; white-space: nowrap; }
.task-card-body p { display: -webkit-box; min-height: 36px; overflow: hidden; margin: 0 0 16px; color: var(--app-muted); font-size: 12px; line-height: 1.5; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.task-progress-head { margin-bottom: 7px; color: var(--app-muted); font-size: 11px; }
.task-progress-head strong { color: var(--app-text); font-size: 12px; }
.task-card-meta { padding-top: 12px; border-top: 1px solid #eef1f6; color: var(--app-muted); font-size: 11px; }
.task-card-meta span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.meta-dot { display: inline-block; width: 5px; height: 5px; margin-right: 6px; border-radius: 50%; background: #9aabc2; vertical-align: middle; }
.task-error { overflow: hidden; padding: 8px 10px; border-radius: 8px; background: #fff4f4; color: #c24141; font-size: 11px; text-overflow: ellipsis; white-space: nowrap; }
.task-card-actions { justify-content: flex-start; margin-top: auto; }
.task-card-actions :deep(.el-button) { margin-left: 0; }
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
@media (max-width: 1100px) { .task-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 1100px) { .production-path-grid { grid-template-columns: repeat(3, minmax(0, 1fr)); } }
@media (max-width: 800px) { .ai-video-summary { grid-template-columns: repeat(2, minmax(0, 1fr)); } .task-metrics { grid-template-columns: repeat(2, minmax(0, 1fr)); } .task-grid { grid-template-columns: 1fr; } .ai-video-mark { width: 96px; height: 96px; font-size: 12px; } .production-path-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 620px) { .ai-video-hero { align-items: flex-start; } .ai-video-mark { display: none; } .ai-video-hero-actions { align-items: flex-start; } .ai-video-steps { grid-template-columns: 1fr; } .ai-video-filters { align-items: stretch; flex-direction: column; } .ai-video-filters :deep(.el-input), .ai-video-filters :deep(.el-select) { width: 100%; } .ai-video-result-count { margin-left: 0; } }
@media (max-width: 520px) { .production-path-grid { grid-template-columns: 1fr; } }
.guide-heading { display: grid; grid-template-columns: minmax(0, 1fr) minmax(230px, 280px); gap: 20px; align-items: start; }
.guide-heading-main { margin-bottom: 0; }
.guide-progress-card { display: grid; gap: 8px; padding: 14px 16px; border: 1px solid #d9e4f8; border-radius: 12px; background: #f6f9ff; }
.guide-progress-copy { display: flex; align-items: baseline; justify-content: space-between; gap: 12px; }
.guide-progress-copy strong { color: var(--app-primary); font-size: 24px; line-height: 1; }
.guide-progress-copy span, .guide-progress-card small { color: var(--app-muted); font-size: 12px; }
.guide-progress-card small { line-height: 1.4; }
.guide-preparation, .guide-delivery { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--app-border); }
.guide-subsection-head, .guide-flow-head { display: flex; align-items: flex-start; justify-content: space-between; gap: 14px; margin-bottom: 12px; }
.guide-subsection-head h4, .guide-subsection-head p, .guide-flow-head h4, .guide-flow-head p { margin: 0; }
.guide-subsection-head h4, .guide-flow-head h4 { color: var(--app-text); font-size: 16px; }
.guide-subsection-head p, .guide-flow-head p { margin-top: 4px; color: var(--app-muted); font-size: 12px; line-height: 1.5; }
.guide-preparation-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.guide-preparation-item { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 10px; align-items: start; padding: 14px; border: 1px solid var(--app-border); border-radius: 11px; background: var(--app-surface-soft); }
.guide-preparation-icon { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 9px; background: #eaf1ff; color: var(--app-primary); font-size: 11px; font-weight: 700; }
.guide-preparation-item strong { color: var(--app-text); font-size: 13px; }
.guide-preparation-item p { min-height: 54px; margin: 5px 0 8px; color: var(--app-muted); font-size: 12px; line-height: 1.5; }
.guide-preparation-item :deep(.el-button) { grid-column: 2; justify-self: start; padding: 0; }
.guide-flow-head { align-items: center; margin-top: 24px; }
.guide-flow-head > span { flex: 0 0 auto; color: var(--app-muted); font-size: 12px; }
.ai-video-steps { grid-template-columns: repeat(5, minmax(0, 1fr)); }
.ai-video-step { display: flex; min-width: 0; flex-direction: column; gap: 10px; width: auto; cursor: default; }
.ai-video-step.is-done { border-color: #b9e2d0; background: #f5fcf8; }
.ai-video-step.is-locked { background: #fafbfc; }
.ai-video-step-head { display: flex; align-items: center; justify-content: space-between; gap: 8px; }
.ai-video-step-index { display: grid; width: 30px; height: 30px; place-items: center; border-radius: 50%; background: var(--app-primary); color: #fff; font-size: 11px; font-weight: 700; }
.ai-video-step.is-done .ai-video-step-index { background: #1f9d68; }
.ai-video-step.is-locked .ai-video-step-index { background: #9aa8ba; }
.ai-video-step h4 { margin: 0; color: var(--app-text); font-size: 16px; }
.ai-video-step-goal { min-height: 42px; margin: 0; color: var(--app-muted); font-size: 12px; line-height: 1.5; }
.ai-video-step-details { display: grid; gap: 8px; margin: 0; }
.ai-video-step-details div { display: grid; gap: 2px; }
.ai-video-step-details dt, .ai-video-step-checks > span { color: var(--app-text); font-size: 11px; font-weight: 700; }
.ai-video-step-details dd { margin: 0; color: var(--app-muted); font-size: 11px; line-height: 1.45; }
.ai-video-step-checks { padding-top: 10px; border-top: 1px solid rgb(130 150 180 / 20%); }
.ai-video-step-checks ul { display: grid; gap: 4px; margin: 6px 0 0; padding-left: 16px; color: var(--app-muted); font-size: 11px; line-height: 1.4; }
.ai-video-step-action { justify-content: center; width: 100%; margin-top: auto; }
.ai-video-step-action :deep(.el-icon) { margin-left: 5px; }
.production-path strong, .production-path small, .production-path em { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.production-path em { color: var(--app-primary); font-size: 10px; font-style: normal; }
.guide-delivery-grid { display: grid; grid-template-columns: repeat(4, minmax(0, 1fr)); gap: 10px; }
.guide-delivery-item { display: grid; grid-template-columns: auto minmax(0, 1fr); gap: 10px; align-items: start; padding: 13px; border: 1px solid var(--app-border); border-radius: 11px; background: #fff; }
.guide-delivery-index { display: grid; width: 28px; height: 28px; place-items: center; border-radius: 8px; background: #eef3fb; color: var(--app-primary); font-size: 11px; font-weight: 700; }
.guide-delivery-item strong { color: var(--app-text); font-size: 13px; }
.guide-delivery-item p { min-height: 36px; margin: 5px 0; color: var(--app-muted); font-size: 11px; line-height: 1.5; }
.guide-delivery-item small { display: block; color: var(--app-muted); font-size: 10px; line-height: 1.45; }
.guide-delivery-item :deep(.el-button) { grid-column: 2; justify-self: start; padding: 0; }
@media (max-width: 1200px) { .ai-video-steps { grid-template-columns: repeat(3, minmax(0, 1fr)); } .guide-preparation-grid, .guide-delivery-grid { grid-template-columns: repeat(2, minmax(0, 1fr)); } }
@media (max-width: 1000px) { .guide-heading { grid-template-columns: 1fr; } }
@media (max-width: 620px) { .guide-preparation-grid, .guide-delivery-grid, .ai-video-steps { grid-template-columns: 1fr; } }
</style>
