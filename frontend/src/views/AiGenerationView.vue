<template>
  <div class="panel generation-panel">
    <div class="generation-hero">
      <div class="generation-hero-copy">
        <span class="generation-kicker">{{ t('generation.kicker') }}</span>
        <h2>{{ t('generation.title') }}</h2>
        <p>{{ t('generation.subtitle') }}</p>
      </div>
      <el-radio-group v-model="type" class="generation-type-switch" @change="onTypeChange">
        <el-radio-button value="image_model">
          <span class="type-switch-icon">✦</span>
          <span>{{ t('generation.image') }}</span>
        </el-radio-button>
        <el-radio-button value="video_model">
          <span class="type-switch-icon">▶</span>
          <span>{{ t('generation.video') }}</span>
        </el-radio-button>
      </el-radio-group>
    </div>

    <div class="generation-workflow" aria-label="generation workflow">
      <div class="workflow-step is-active"><span>1</span>{{ t('generation.workflowPrompt') }}</div>
      <div class="workflow-line"></div>
      <div class="workflow-step"><span>2</span>{{ t('generation.workflowAssets') }}</div>
      <div class="workflow-line"></div>
      <div class="workflow-step"><span>3</span>{{ t('generation.workflowResult') }}</div>
    </div>

    <details class="generation-guide">
      <summary>{{ t('generation.guideTitle') }}<span>{{ t('generation.guideSummary') }}</span></summary>
      <ol>
        <li>{{ t('generation.guideStepModel') }}</li>
        <li>{{ t('generation.guideStepPrompt') }}</li>
        <li>{{ t('generation.guideStepAssets') }}</li>
        <li>{{ t('generation.guideStepResult') }}</li>
      </ol>
      <p>{{ t('generation.guideTip') }}</p>
    </details>

    <div v-if="error" class="generation-error generation-alert" role="alert">{{ error }}</div>
    <div class="generation-layout">
      <aside class="generation-sidebar">
        <section class="setup-card">
          <div class="card-eyebrow">{{ t('generation.setup') }}</div>
          <el-form class="setup-form" label-position="top" size="small">
            <el-form-item class="setup-field" :label="t('generation.model')">
              <el-select v-model="modelId" class="model-select" filterable :disabled="!modelsForType.length || busy" @change="onModelChange">
                <el-option v-for="model in modelsForType" :key="model.id" :label="`${model.name} · ${model.modelName}`" :value="model.id" />
              </el-select>
              <div v-if="!modelsForType.length" class="generation-empty">
                <p>{{ t('generation.noModels') }}</p>
                <el-button text type="primary" size="small" @click="goModelManagement">{{ t('generation.openModels') }}</el-button>
              </div>
            </el-form-item>
            <el-form-item class="setup-field" :label="t('generation.mode')">
              <el-select v-model="mode" class="mode-select" :disabled="!capability.modes?.length || busy" @change="onModeChange">
                <el-option v-for="value in capability.modes" :key="value" :label="modeLabel(value)" :value="value" />
              </el-select>
              <div v-if="!capability.modes?.length" class="generation-empty">{{ t('generation.modeUnavailable') }}</div>
            </el-form-item>
          </el-form>
          <div v-if="capability.profile" class="capability-card">
            <div class="capability-title"><span class="capability-dot"></span>{{ capability.profile }}</div>
            <div class="capability-chips">
              <span class="capability-chip">{{ t('generation.inputLimit', { count: capability.totalLimit }) }}</span>
              <span v-if="capability.features?.groups" class="capability-chip">{{ t('generation.groupsSupported') }}</span>
              <span v-if="capability.features?.layers" class="capability-chip">{{ t('generation.layersSupported') }}</span>
              <span v-if="capability.features?.draft" class="capability-chip">{{ t('generation.draftSupported') }}</span>
            </div>
            <div v-if="capabilityHint" class="capability-limits">{{ capabilityHint }}</div>
          </div>
        </section>

        <section class="history-card">
          <div class="history-heading">
            <div><div class="card-eyebrow">{{ t('generation.history') }}</div><strong>{{ t('generation.historyHint') }}</strong></div>
            <el-button text size="small" @click="loadHistory">{{ t('generation.refresh') }}</el-button>
          </div>
          <div v-if="!history.length" class="history-empty">{{ t('generation.noHistory') }}</div>
          <button v-for="task in history" :key="task.id" class="generation-history-item" @click="selectHistory(task)">
            <span class="history-item-preview" :class="`history-${task.status?.toLowerCase()}`"><img v-if="outputMediaType(historyPreview(task), task.generationType) === 'image' && historyPreview(task)" :src="outputSource(historyPreview(task), task.generationType)" :alt="historyPreview(task)?.name || modeLabel(task.generationMode || '')" /><span v-else-if="task.generationType === 'video_model'">▶</span><span v-else>✦</span></span>
            <span class="history-item-copy"><strong>{{ modeLabel(task.generationMode || '') }}</strong><small class="history-item-prompt">{{ promptSummary(task.prompt) }}</small><small>#{{ task.id }} · {{ task.inputCount || 0 }} {{ t('generation.assetsShort') }} · {{ outputCountLabel(task) }}</small><small v-if="task.createdAt" class="history-item-time">{{ task.createdAt }}</small><small v-if="task.errorMessage" class="history-item-error">{{ failureReason(task.errorMessage) }}</small></span>
            <el-tag size="small" :type="statusType(task.status)">{{ statusLabel(task.status) }}</el-tag>
          </button>
        </section>
      </aside>

      <main class="generation-main">
        <div class="generation-canvas">
        <el-form label-position="top" @submit.prevent="submit">
          <section class="composer-card">
            <div class="section-title-row">
              <div><span class="card-eyebrow">{{ t('generation.createKicker') }}</span><h3>{{ t('generation.prompt') }}</h3></div>
              <span class="section-hint">{{ t('generation.promptHint') }}</span>
            </div>
            <el-form-item>
              <el-input v-model="prompt" class="prompt-input" type="textarea" :rows="6" :placeholder="t('generation.promptPlaceholder')" />
            </el-form-item>
            <div class="prompt-footer">
              <span class="prompt-state"><span class="prompt-state-dot"></span>{{ type === 'image_model' ? t('generation.image') : t('generation.video') }}</span>
              <span>{{ t('generation.submitHint') }}</span>
            </div>
          </section>

          <div class="generation-input-grid">
          <section class="asset-section">
            <div class="section-title-row">
              <div><span class="card-eyebrow">{{ t('generation.assetsKicker') }}</span><h3>{{ t('generation.assets') }}</h3></div>
              <span class="asset-count" :class="{ 'is-full': inputs.length >= (capability.totalLimit || 0) }">{{ inputs.length }}/{{ capability.totalLimit || 0 }}</span>
            </div>
            <label class="asset-dropzone" :class="{ 'is-disabled': assetsDisabled }" @dragover.prevent @drop.prevent="onDrop">
              <input ref="filePicker" type="file" hidden multiple :accept="accept" :disabled="assetsDisabled" @change="onFiles" />
              <span class="dropzone-icon">＋</span>
              <strong>{{ t('generation.dropAssets') }}</strong>
              <small>{{ assetsAllowed ? t('generation.assetHint') : t('generation.modeNoAssets') }}</small>
              <el-button plain size="small" :disabled="assetsDisabled" @click.prevent="filePicker?.click()">{{ t('generation.addAsset') }}</el-button>
            </label>
            <div v-if="inputs.length" class="asset-list">
              <div v-for="(input, index) in inputs" :key="input.dataUrl + index" class="asset-item">
                <div class="asset-preview">
                  <img v-if="input.kind === 'IMAGE'" :src="input.dataUrl" :alt="input.fileName" />
                  <video v-else-if="input.kind === 'VIDEO'" muted :src="input.dataUrl" />
                  <span v-else class="asset-file">{{ input.kind }}</span>
                </div>
                <div class="asset-copy"><strong class="asset-name" :title="input.fileName">{{ input.fileName }}</strong><small>{{ input.kind }}</small></div>
                <el-select v-if="type === 'video_model' && input.kind !== 'TEXT'" v-model="input.role" size="small" :placeholder="t('generation.role')">
                  <el-option v-for="role in rolesFor(input.kind)" :key="role" :label="roleLabel(role)" :value="role" />
                </el-select>
                <el-button text type="danger" size="small" @click="removeInput(index)">{{ t('common.delete') }}</el-button>
              </div>
            </div>
            <div class="asset-actions">
              <span>{{ t('generation.assetFormats') }}</span>
              <el-button v-if="mode === 'DRAFT_TO_VIDEO'" plain size="small" @click="addDraftTask">{{ t('generation.useDraft') }}</el-button>
            </div>
          </section>

          <section class="options-section">
            <div class="section-title-row"><div><span class="card-eyebrow">{{ t('generation.optionsKicker') }}</span><h3>{{ t('generation.mediaOptions') }}</h3></div><span class="section-hint">{{ t('generation.optionsHint') }}</span></div>
            <div class="generation-options" :class="{ 'is-video': type === 'video_model' }">
              <section v-if="type === 'image_model'" class="output-setting-group output-setting-group-media">
                <div class="output-setting-heading"><span class="output-setting-icon">✦</span><div><strong>{{ t('generation.imageOutput') }}</strong><small>{{ t('generation.imageOutputHint') }}</small></div></div>
                <div class="output-setting-fields">
                  <el-form-item :label="t('generation.imageSize')"><el-select v-model="options.size"><el-option v-for="value in imageSizeOptions" :key="value" :label="value" :value="value" /></el-select></el-form-item>
                  <el-form-item v-if="capability.features?.groups" :label="t('generation.groupOutput')"><el-switch v-model="groupOutput" @change="syncGroupOptions" /></el-form-item>
                  <el-form-item v-if="groupOutput" :label="t('generation.maxImages')"><el-input-number v-model="options.sequential_image_generation_options.max_images" :min="1" :max="15" /></el-form-item>
                  <el-form-item v-if="capability.features?.layers" :label="t('generation.layerDecomposition')"><el-switch v-model="options.layer_decomposition" /></el-form-item>
                  <el-form-item v-if="capability.features?.transparent" :label="t('generation.transparentBackground')"><el-switch v-model="transparent" @change="syncTransparent" /></el-form-item>
                </div>
              </section>
              <section v-if="type === 'video_model'" class="output-setting-group output-setting-group-media">
                <div class="output-setting-heading"><span class="output-setting-icon">▶</span><div><strong>{{ t('generation.videoOutput') }}</strong><small>{{ t('generation.videoOutputHint') }}</small></div></div>
                <div class="output-setting-fields video-output-fields">
                  <div class="video-output-row video-output-specs">
                    <el-form-item :label="t('generation.resolution')"><el-select v-model="options.resolution" :disabled="draftMode"><el-option v-for="value in resolutionOptions" :key="value" :label="value" :value="value" /></el-select></el-form-item>
                    <el-form-item :label="t('generation.ratio')"><el-select v-model="options.ratio" :disabled="lockedRatio"><el-option v-for="value in ratioOptions" :key="value" :label="value" :value="value" /></el-select></el-form-item>
                    <el-form-item v-if="!lockedVideoSettings" :label="t('generation.duration')"><el-input-number v-model="options.duration" :min="durationMin" :max="durationMax" /></el-form-item>
                    <el-form-item v-if="lockedVideoSettings" :label="t('generation.duration')"><el-input :model-value="lockedDurationLabel" disabled /></el-form-item>
                  </div>
                  <div v-if="capability.options?.return_last_frame || (capability.features?.audio && !draftToVideo)" class="video-output-row video-output-toggles">
                    <el-form-item v-if="capability.options?.return_last_frame" :label="t('generation.returnLastFrame')"><el-switch v-model="options.return_last_frame" /></el-form-item>
                    <el-form-item v-if="capability.features?.audio && !draftToVideo" :label="t('generation.generateAudio')"><el-switch v-model="options.generate_audio" /></el-form-item>
                  </div>
                  <div v-if="capability.options?.output_format || mode === 'DRAFT_VIDEO'" class="video-output-row video-output-extra">
                    <el-form-item v-if="capability.options?.output_format" :label="t('generation.outputFormat')"><el-select v-model="options.output_format"><el-option v-for="value in outputFormatOptions" :key="value" :label="value" :value="value" /></el-select></el-form-item>
                    <el-form-item v-if="mode === 'DRAFT_VIDEO'" :label="t('generation.draft')"><el-switch v-model="options.draft" /></el-form-item>
                  </div>
                </div>
              </section>
              <section class="output-setting-group output-setting-group-common">
                <div class="output-setting-heading"><span class="output-setting-icon">⚙</span><div><strong>{{ t('generation.commonOutput') }}</strong><small>{{ t('generation.commonOutputHint') }}</small></div></div>
                <div class="output-setting-fields"><el-form-item :label="t('generation.watermark')"><el-switch v-model="options.watermark" /></el-form-item></div>
              </section>
            </div>
            <p v-if="modeHint" class="generation-hint">{{ modeHint }}</p>
            <p v-if="lockedVideoSettings" class="generation-hint">{{ t('generation.lockedSettings') }}</p>
          </section>
          </div>

          <div class="generation-actions">
            <span class="submit-hint">{{ t('generation.submitHint') }}</span>
            <el-button @click="reset">{{ t('generation.reset') }}</el-button>
            <el-button type="primary" :loading="busy" :disabled="!canSubmit" @click="submit">{{ t('generation.submit') }}</el-button>
          </div>
        </el-form>

        </div>
      </main>
    </div>

    <el-dialog
      v-model="historyDialogVisible"
      class="generation-history-dialog"
      :title="dialogTitle"
      width="min(860px, calc(100vw - 32px))"
      destroy-on-close
      @closed="clearHistorySelection"
    >
      <div v-if="selectedHistoryTask" class="history-dialog-content">
        <div class="result-heading history-dialog-heading">
          <div>
            <span class="card-eyebrow">{{ t('generation.resultKicker') }}</span>
            <h3>{{ modeLabel(selectedHistoryTask.generationMode || '') }}</h3>
          </div>
          <el-tag :type="statusType(selectedHistoryTask.status)" effect="light">{{ statusLabel(selectedHistoryTask.status) }}</el-tag>
        </div>
        <div class="result-meta">
          <span v-if="selectedHistoryTask.modelName">{{ selectedHistoryTask.modelName }}</span>
          <span v-if="selectedHistoryTask.createdAt">{{ selectedHistoryTask.createdAt }}</span>
          <span>{{ selectedHistoryTask.inputCount || 0 }} {{ t('generation.assetsShort') }}</span>
        </div>
        <p v-if="selectedHistoryTask.errorMessage" class="generation-error generation-alert">{{ failureReason(selectedHistoryTask.errorMessage) }}</p>
        <div v-if="historyOutputs.length" class="result-grid">
          <article v-for="(output, index) in historyOutputs" :key="output.value + index" class="result-card">
            <el-image v-if="outputMediaType(output, selectedHistoryTask.generationType) === 'image'" class="result-media" :src="outputSource(output, selectedHistoryTask.generationType)" :alt="outputCaption(output, index)" @error="useOssFallback($event, output, selectedHistoryTask.generationType)" fit="contain" preview-teleported />
            <video v-else class="result-media" controls preload="metadata" :src="outputSource(output, selectedHistoryTask.generationType)" @error="useOssFallback($event, output, selectedHistoryTask.generationType)" />
            <div class="result-card-footer"><div class="result-card-title"><strong>{{ outputCaption(output, index) }}</strong><el-button text size="small" :loading="downloadingOutput === `${selectedHistoryTask.id}:${index}`" @click.stop="downloadOutput(output, index)">{{ t('generation.download') }}</el-button></div><small v-if="output.output_format || output.size || outputMimeType(output, selectedHistoryTask.generationType)">{{ output.output_format || outputMimeType(output, selectedHistoryTask.generationType) }}<span v-if="(output.output_format || outputMimeType(output, selectedHistoryTask.generationType)) && output.size"> · </span>{{ output.size || '' }}</small></div>
          </article>
        </div>
        <div v-else class="result-empty"><span class="result-empty-icon">✦</span><strong>{{ selectedHistoryTask.status === 'RUNNING' || selectedHistoryTask.status === 'QUEUED' ? t('generation.resultWaiting') : t('generation.resultEmpty') }}</strong><small>{{ selectedHistoryTask.status === 'RUNNING' || selectedHistoryTask.status === 'QUEUED' ? t('generation.resultWaitingHint') : t('generation.resultEmptyHint') }}</small></div>
        <div v-if="selectedHistoryTask.outputJson?.errors?.length" class="generation-error generation-alert">{{ t('generation.partialFailure', { count: selectedHistoryTask.outputJson.errors.length }) }}</div>
        <el-button v-if="selectedHistoryTask.status === 'RUNNING' || selectedHistoryTask.status === 'QUEUED'" text :loading="busy" @click="poll(selectedHistoryTask.id)">{{ t('generation.refresh') }}</el-button>
      </div>
      <div v-else-if="dialogError" class="history-dialog-content">
        <p class="generation-error generation-alert">{{ dialogError }}</p>
      </div>
    </el-dialog>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import { useRouter } from 'vue-router'
import http, { showHttpError } from '../api/http'
import { generationFailureReasonKey, parseGenerationFailure } from '../utils/generationFailure.js'
import { outputFallbackSource, outputFilename, outputMediaType, outputMimeType, outputSource } from '../utils/generationOutput.js'

const { t } = useI18n()
const router = useRouter()
const type = ref('image_model')
const models = ref([])
const modelId = ref(null)
const capability = ref({ modes: [], features: {}, options: {}, totalLimit: 0 })
const mode = ref('TEXT_TO_IMAGE')
const prompt = ref('')
const inputs = ref([])
const history = ref([])
const result = ref(null)
const historyDialogVisible = ref(false)
const selectedHistoryTask = ref(null)
const dialogError = ref('')
const dialogMode = ref('history')
const busy = ref(false)
const downloadingOutput = ref('')
const error = ref('')
const filePicker = ref(null)
const groupOutput = ref(false)
const transparent = ref(false)
const options = reactive({ size: '2K', response_format: 'url', sequential_image_generation_options: { max_images: 4 }, layer_decomposition: false, background: 'opaque', output_format: 'png', resolution: '720p', ratio: 'adaptive', duration: 5, generate_audio: true, draft: false, return_last_frame: false, watermark: false })
const modelsForType = computed(() => models.value.filter(item => item.modelType === type.value))
const selectedModel = computed(() => models.value.find(item => item.id === modelId.value && item.modelType === type.value))
/** 每个生成模式声明可接受的素材类型和数量，避免上传素材改变任务语义。 */
const modeInputPolicy = computed(() => {
  const common = {
    TEXT_TO_IMAGE: { kinds: [], max: 0 }, TEXT_TO_GROUP: { kinds: [], max: 0 },
    IMAGE_TEXT_TO_IMAGE: { kinds: ['IMAGE'], max: 1 }, IMAGE_TO_GROUP: { kinds: ['IMAGE'], max: 1 },
    MULTI_IMAGE_FUSION: { kinds: ['IMAGE'], max: 14 }, MULTI_IMAGE_TO_GROUP: { kinds: ['IMAGE'], max: 14 },
    LAYER_DECOMPOSITION: { kinds: ['IMAGE'], max: 1 }, INTERACTIVE_EDIT: { kinds: ['IMAGE'], max: 1 }, TRANSPARENT_IMAGE: { kinds: ['IMAGE'], max: 1 },
    TEXT_TO_VIDEO: { kinds: [], max: 0 }, IMAGE_TO_VIDEO: { kinds: ['IMAGE'], max: 1 }, FIRST_LAST_FRAME_TO_VIDEO: { kinds: ['IMAGE'], max: 2 },
    REFERENCE_IMAGE_TO_VIDEO: { kinds: ['IMAGE'], max: 9 }, VIDEO_TO_VIDEO: { kinds: ['VIDEO'], max: 3 },
    IMAGE_VIDEO_AUDIO_TO_VIDEO: { kinds: ['IMAGE', 'VIDEO', 'AUDIO'], max: 12 }, STORYBOARD_TO_VIDEO: { kinds: ['IMAGE', 'VIDEO', 'AUDIO'], max: 12 },
    ONE_CLICK_VIDEO: { kinds: ['IMAGE', 'VIDEO'], max: 12 }, VIDEO_TRANSITION: { kinds: ['VIDEO'], max: 3 },
    EDIT_VIDEO: { kinds: ['VIDEO'], max: 3 }, EXTEND_VIDEO: { kinds: ['VIDEO'], max: 3 }, DRAFT_VIDEO: { kinds: [], max: 0 }, DRAFT_TO_VIDEO: { kinds: ['TASK'], max: 1 }
  }
  return common[mode.value] || (type.value === 'image_model' ? { kinds: ['IMAGE'], max: 14 } : { kinds: ['IMAGE', 'VIDEO', 'AUDIO'], max: capability.value.totalLimit || 0 })
})
const assetsAllowed = computed(() => modeInputPolicy.value.kinds.length > 0)
const assetsDisabled = computed(() => busy.value || !assetsAllowed.value || inputs.value.length >= Math.min(capability.value.totalLimit || 0, modeInputPolicy.value.max))
const accept = computed(() => {
  const formats = { IMAGE: 'image/png,image/jpeg,image/webp,image/bmp,image/tiff,image/gif,image/heic,image/heif', VIDEO: 'video/mp4,video/webm,video/quicktime', AUDIO: 'audio/mpeg,audio/wav,.mp3,.wav', TASK: '' }
  return modeInputPolicy.value.kinds.map(kind => formats[kind]).filter(Boolean).join(',')
})
const canSubmit = computed(() => !busy.value && !!modelId.value && !!mode.value && (!!prompt.value.trim() || inputs.value.length > 0) && (assetsAllowed.value || inputs.value.length === 0))
const dialogTitle = computed(() => t(dialogMode.value === 'result' ? 'generation.result' : 'generation.history'))
const historyOutputs = computed(() => selectedHistoryTask.value?.outputJson?.outputs || [])
const draftMode = computed(() => mode.value === 'DRAFT_VIDEO')
const draftToVideo = computed(() => mode.value === 'DRAFT_TO_VIDEO')
const editVideo = computed(() => mode.value === 'EDIT_VIDEO')
const extendVideo = computed(() => mode.value === 'EXTEND_VIDEO')
const lockedVideoSettings = computed(() => editVideo.value || draftToVideo.value)
const lockedRatio = computed(() => type.value === 'video_model' && (lockedVideoSettings.value || extendVideo.value || mode.value.includes('FRAME')))
const durationMin = computed(() => { const min = Number(capability.value.options?.duration?.min ?? capability.value.durationMin ?? 2); return min < 1 ? Number(capability.value.durationMin || 2) : min })
const durationMax = computed(() => Number(capability.value.options?.duration?.max ?? 30))
const lockedDurationLabel = computed(() => editVideo.value ? t('generation.editLockedDuration') : t('generation.draftLockedDuration'))
const imageSizeOptions = computed(() => capability.value.options?.size?.enum || ['1K', '2K', '4K'])
const resolutionOptions = computed(() => draftMode.value ? ['480p'] : capability.value.options?.resolution?.enum || ['480p', '720p', '1080p'])
const ratioOptions = computed(() => lockedRatio.value ? ['adaptive'] : capability.value.options?.ratio?.enum || ['adaptive', '16:9', '9:16'])
const outputFormatOptions = computed(() => capability.value.options?.output_format?.enum || ['mp4', 'mov'])
/** 将模型能力目录中的数量、时长和帧数限制直接展示给用户。 */
const capabilityHint = computed(() => {
  const value = capability.value
  const parts = []
  if (value.imageLimit !== undefined) parts.push(t('generation.imageLimit', { count: value.imageLimit }))
  if (value.videoLimit) parts.push(t('generation.videoLimit', { count: value.videoLimit }))
  if (value.audioLimit) parts.push(t('generation.audioLimit', { count: value.audioLimit }))
  const duration = value.options?.duration
  if (duration?.min !== undefined && duration?.max !== undefined) parts.push(t('generation.durationLimit', { min: Number(duration.min) < 1 ? value.durationMin : duration.min, max: duration.max }))
  const frames = value.options?.frames
  if (frames?.min !== undefined && frames?.max !== undefined) parts.push(t('generation.framesLimit', { min: frames.min, max: frames.max }))
  const resolution = value.options?.resolution?.enum
  if (resolution?.length) parts.push(t('generation.resolutionLimit', { values: resolution.join(' / ') }))
  const ratio = value.options?.ratio?.enum
  if (ratio?.length) parts.push(t('generation.ratioLimit', { values: ratio.join(' / ') }))
  return parts.join(' · ')
})
/** 说明当前模式所需素材，避免用户只看到通用控件。 */
const modeHint = computed(() => {
  const messages = {
    TEXT_TO_IMAGE: 'generation.constraints.promptOnly', TEXT_TO_VIDEO: 'generation.constraints.promptOnly',
    IMAGE_TEXT_TO_IMAGE: 'generation.constraints.referenceImage', IMAGE_TO_GROUP: 'generation.constraints.referenceImage',
    IMAGE_TO_VIDEO: 'generation.constraints.referenceImage', REFERENCE_IMAGE_TO_VIDEO: 'generation.constraints.referenceImage',
    FIRST_LAST_FRAME_TO_VIDEO: 'generation.constraints.firstLastFrame', VIDEO_TO_VIDEO: 'generation.constraints.referenceVideo',
    EDIT_VIDEO: 'generation.constraints.referenceVideo', EXTEND_VIDEO: 'generation.constraints.referenceVideo',
    VIDEO_TRANSITION: 'generation.constraints.transition', DRAFT_TO_VIDEO: 'generation.constraints.draftOnly'
  }
  return messages[mode.value] ? t(messages[mode.value]) : ''
})
/** 只提交当前模型能力目录声明的参数，避免隐藏控件的默认值污染请求。 */
const requestOptions = computed(() => {
  const result = Object.fromEntries(Object.entries(options).filter(([key, value]) => capability.value.options?.[key] && value !== '' && value !== undefined && value !== null))
  if (type.value === 'image_model') {
    const grouped = mode.value.endsWith('GROUP')
    if (grouped) {
      result.sequential_image_generation = 'auto'
    } else {
      delete result.sequential_image_generation_options
      delete result.sequential_image_generation
    }
  }
  if (type.value === 'video_model') {
    if (draftToVideo.value) return { watermark: Boolean(options.watermark) }
    if (draftMode.value) result.resolution = '480p'
    if (lockedRatio.value) result.ratio = 'adaptive'
    if (editVideo.value) result.duration = -1
    if ((editVideo.value || extendVideo.value) && capability.value.options?.output_format) result.output_format = 'mov'
  }
  return result
})

/** 将后端模式编码转换为用户可理解的生成方式。 */
function modeLabel(value) { return t(`generation.modes.${value}`, value.replaceAll('_', ' ')) }
/** 根据素材类型限定方舟内容角色，首尾帧不能用于音视频素材。 */
function rolesFor(kind) {
  if (kind === 'IMAGE') return mode.value === 'IMAGE_TO_VIDEO' ? ['first_frame'] : mode.value === 'FIRST_LAST_FRAME_TO_VIDEO' ? ['first_frame', 'last_frame'] : type.value === 'video_model' ? ['reference_image'] : []
  return kind === 'VIDEO' ? ['reference_video'] : ['reference_audio']
}
/** 将角色编码本地化。 */
function roleLabel(value) { return t(`generation.roles.${value}`, value.replaceAll('_', ' ')) }
/** 将任务状态映射为 Element Plus 标签样式，统一历史和结果反馈。 */
function statusType(status) { return status === 'SUCCEEDED' ? 'success' : status === 'FAILED' || status === 'CANCELLED' ? 'danger' : 'warning' }
/** 将后端状态本地化，避免页面直接展示英文枚举。 */
function statusLabel(status) { return t(`generation.status.${status}`, status || 'UNKNOWN') }
/** 将后端失败码转换为当前语言的说明，并保留供应商状态、错误码和详情。 */
function failureReason(code) {
  const parsed = parseGenerationFailure(code)
  if (!parsed.code) return ''
  const key = generationFailureReasonKey(parsed.code)
  const label = t(key || 'generation.failureReasons.UNKNOWN', key ? undefined : parsed.code)
  if (!parsed.detail) return label
  const context = [parsed.status, parsed.providerCode].filter(Boolean).join(' · ')
  return `${label}${context ? ` (${context})` : ''}: ${parsed.detail}`
}
/** 为没有名称的结果生成稳定的可读标题。 */
function outputCaption(output, index) { return output.name || `${outputMediaType(output, selectedHistoryTask.value?.generationType) === 'image' ? t('generation.image') : t('generation.video')} ${index + 1}` }
/** 取历史记录中的首个输出，作为列表缩略图或媒体占位。 */
function historyPreview(task) { return task?.outputJson?.outputs?.[0] || null }
/** 截断提示词，保证历史列表的状态和结果信息可见。 */
function promptSummary(value) { const text = String(value || '').trim(); return text ? (text.length > 72 ? `${text.slice(0, 72)}…` : text) : t('generation.noPrompt') }
/** 显示任务输出数量，区分尚未生成和已有部分结果。 */
function outputCountLabel(task) { const count = task?.outputJson?.outputs?.length || 0; return count ? t('generation.outputCount', { count }) : t('generation.noOutput') }
/** 图片或视频加载失败时只切换一次 OSS 备份，避免失败地址之间循环。 */
function useOssFallback(event, output, fallbackType) {
  const target = event?.target
  if (!target || target.dataset.ossFallback === '1' || !output?.ossUrl || target.src === output.ossUrl) return
  target.dataset.ossFallback = '1'
  target.src = outputFallbackSource(output, fallbackType)
}
/** 下载远程或 Base64 媒体；跨域 CDN 禁止 Blob 时回退到签名地址。 */
async function downloadOutput(output, index) {
  const task = selectedHistoryTask.value
  const key = `${task?.id}:${index}`
  downloadingOutput.value = key
  try {
    let blob
    if (output.kind === 'base64') {
      const binary = atob(String(output.value || ''))
      const bytes = Uint8Array.from(binary, character => character.charCodeAt(0))
      blob = new Blob([bytes], { type: outputMimeType(output, task?.generationType) })
    } else {
      const response = await fetch(output.value, { credentials: 'omit' })
      if (!response.ok) throw new Error(`HTTP ${response.status}`)
      blob = await response.blob()
    }
    const url = URL.createObjectURL(blob)
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = outputFilename(output, index, task?.id, task?.generationType)
    document.body.append(anchor)
    anchor.click()
    anchor.remove()
    setTimeout(() => URL.revokeObjectURL(url), 0)
  } catch {
    if (output?.value || output?.ossUrl) window.open(outputFallbackSource(output, task?.generationType), '_blank', 'noopener,noreferrer')
    ElMessage.info(t('generation.downloadFallback'))
  } finally { downloadingOutput.value = '' }
}
/** 读取选定模型的能力目录并同步合法模式。 */
function onModelChange() {
  const item = selectedModel.value
  capability.value = item?.capabilities || { modes: [], features: {}, options: {}, totalLimit: 0 }
  mode.value = capability.value.modes?.[0] || ''
  groupOutput.value = false
  transparent.value = false
  resetOptions()
  syncModeOptions()
}
/** 切换图片或视频时只显示同类模型并清理不兼容素材。 */
function onTypeChange() { modelId.value = modelsForType.value[0]?.id || null; inputs.value = []; result.value = null; onModelChange(); loadHistory() }
/** 切换模式时清除旧角色，避免角色与任务类型混用。 */
function onModeChange() { reconcileInputsForMode(); syncModeOptions() }
/** 按新模式清理不兼容素材，并为首帧、尾帧和参考素材设置唯一角色。 */
function reconcileInputsForMode() {
  const policy = modeInputPolicy.value
  if (!assetsAllowed.value) { inputs.value = []; return }
  inputs.value = inputs.value.filter(input => policy.kinds.includes(input.kind)).slice(0, policy.max)
  if (mode.value === 'IMAGE_TO_VIDEO') inputs.value.forEach(input => { input.role = 'first_frame' })
  else if (mode.value === 'FIRST_LAST_FRAME_TO_VIDEO') inputs.value.forEach((input, index) => { input.role = index === 0 ? 'first_frame' : 'last_frame' })
  else if (type.value === 'video_model') inputs.value.forEach(input => { input.role = input.kind === 'IMAGE' ? 'reference_image' : input.kind === 'VIDEO' ? 'reference_video' : input.kind === 'AUDIO' ? 'reference_audio' : '' })
  else inputs.value.forEach(input => { input.role = '' })
}
/** 根据组图开关同步供应商参数。 */
function syncGroupOptions(value) { options.sequential_image_generation = value ? 'auto' : 'disabled' }
/** 透明背景要求 PNG，关闭时恢复默认不透明。 */
function syncTransparent(value) { options.background = value ? 'transparent' : 'opaque'; if (value) options.output_format = 'png' }
/** 清空所有生成选项并恢复安全默认值。 */
function resetOptions() { Object.assign(options, { size: imageSizeOptions.value[1] || imageSizeOptions.value[0] || '2K', response_format: 'url', sequential_image_generation: groupOutput.value ? 'auto' : 'disabled', sequential_image_generation_options: { max_images: 4 }, layer_decomposition: false, background: transparent.value ? 'transparent' : 'opaque', output_format: type.value === 'video_model' ? 'mp4' : 'png', resolution: '720p', ratio: 'adaptive', duration: 5, generate_audio: true, draft: false, return_last_frame: false, watermark: false }) }
/** 按方舟任务类型同步被锁定的参数，避免隐藏控件的默认值污染请求。 */
function syncModeOptions() {
  if (draftMode.value) { options.resolution = '480p'; options.draft = true }
  if (editVideo.value) { options.duration = -1; options.ratio = 'adaptive'; options.output_format = 'mov' }
  if (extendVideo.value || mode.value.includes('FRAME')) { options.ratio = 'adaptive'; if (extendVideo.value) options.output_format = 'mov' }
}
/** 清空当前草稿和结果。 */
function reset() { prompt.value = ''; inputs.value = []; result.value = null; error.value = ''; groupOutput.value = false; transparent.value = false; resetOptions() }
/** 当当前输出类型没有模型时跳转到模型管理，避免用户停留在不可操作的空表单。 */
function goModelManagement() { router.push('/models') }
/** 读取文件并转换为后端接受的 Data URL。 */
function readFile(file) { return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsDataURL(file) }) }
/** 校验文件类型和大小，防止浏览器提交超出模型能力的素材。 */
async function onFiles(event) {
  const files = Array.from(event.target?.files || event.dataTransfer?.files || []); if (event.target) event.target.value = ''
  for (const file of files) {
    const kind = file.type.startsWith('image/') ? 'IMAGE' : file.type.startsWith('video/') ? 'VIDEO' : file.type.startsWith('audio/') ? 'AUDIO' : file.type.startsWith('text/') || /\.(txt|md)$/i.test(file.name) ? 'TEXT' : ''
    const max = kind === 'IMAGE' ? capability.value.imageMaxBytes : kind === 'VIDEO' ? capability.value.videoMaxBytes : capability.value.audioMaxBytes
    const policy = modeInputPolicy.value
    if (!policy.kinds.includes(kind) || file.size > max || inputs.value.length >= Math.min(capability.value.totalLimit || 0, policy.max)) { ElMessage.warning(t('generation.fileRejected')); continue }
    try {
      const role = mode.value === 'IMAGE_TO_VIDEO' && kind === 'IMAGE' ? 'first_frame' : mode.value === 'FIRST_LAST_FRAME_TO_VIDEO' && kind === 'IMAGE' ? (inputs.value.some(input => input.role === 'first_frame') ? 'last_frame' : 'first_frame') : type.value !== 'video_model' || kind === 'TEXT' ? '' : kind === 'IMAGE' ? 'reference_image' : kind === 'VIDEO' ? 'reference_video' : 'reference_audio'
      inputs.value.push({ kind, fileName: file.name, contentType: file.type || (kind === 'TEXT' ? 'text/plain' : ''), dataUrl: await readFile(file), role })
    } catch (cause) { showHttpError(cause, 'generation.fileReadFailed') }
  }
}
/** 处理拖拽文件并复用上传校验逻辑。 */
function onDrop(event) { if (!assetsDisabled.value) onFiles(event) }
/** 删除已选素材。 */
function removeInput(index) { inputs.value.splice(index, 1) }
/** 加入历史样片任务作为正式视频的唯一输入。 */
function addDraftTask() { const draft = history.value.find(item => item.modelId === modelId.value && item.status === 'SUCCEEDED' && item.outputJson?.outputs?.length && item.options?.draft); if (draft) inputs.value = [{ kind: 'TASK', fileName: `draft-${draft.id}`, contentType: 'text/plain', dataUrl: String(draft.id), role: '' }]; else ElMessage.warning(t('generation.noDraft')) }
/** 提交任务并轮询异步视频，图片响应直接完成。 */
async function submit() {
  if (!canSubmit.value) return
  busy.value = true; error.value = ''; dialogError.value = ''; result.value = null
  const payload = { modelType: type.value, modelId: modelId.value, prompt: prompt.value, generationMode: mode.value, inputs: inputs.value, options: requestOptions.value }
  try {
    result.value = (await http.post('/ai/generation', payload)).data
    openResultDialog(result.value)
    if (result.value.status === 'RUNNING' || result.value.status === 'QUEUED') await poll(result.value.id)
    await loadHistory()
  } catch (cause) {
    error.value = t('generation.submitFailed')
    dialogError.value = t('generation.submitFailed')
    selectedHistoryTask.value = null
    dialogMode.value = 'result'
    historyDialogVisible.value = true
    showHttpError(cause, 'generation.submitFailed')
  } finally { busy.value = false }
}
/** 查询任务终态，不重新创建任务。 */
async function poll(id) {
  const response = (await http.get(`/ai/generation/${id}`)).data
  result.value = response
  openResultDialog(response)
  if (response.status === 'RUNNING' || response.status === 'QUEUED') { await new Promise(resolve => setTimeout(resolve, 1500)); return poll(id) }
  await loadHistory()
}
/** 查询当前用户可见的历史任务。 */
async function loadHistory() { try { history.value = ((await http.get('/ai/generation', { params: { type: type.value, page: 0 } })).data.items || []) } catch { history.value = [] } }
/** 打开历史任务详情弹窗，不覆盖当前创作草稿和结果。 */
function selectHistory(task) { dialogError.value = ''; dialogMode.value = 'history'; selectedHistoryTask.value = task; historyDialogVisible.value = true }
/** 打开生成结果弹窗，并在轮询时同步弹窗内的任务状态。 */
function openResultDialog(task) { dialogError.value = ''; dialogMode.value = 'result'; selectedHistoryTask.value = task; historyDialogVisible.value = true }
/** 关闭历史任务详情后清理选择状态，避免保留大媒体数据。 */
function clearHistorySelection() { selectedHistoryTask.value = null; dialogMode.value = 'history' }
/** 加载可用模型目录。 */
async function loadModels() {
  try {
    models.value = (await http.get('/ai/generation/models')).data || []
    const firstAvailableType = ['image_model', 'video_model'].find(value => models.value.some(item => item.modelType === value))
    if (firstAvailableType) type.value = firstAvailableType
    onTypeChange()
  } catch (cause) { error.value = t('generation.modelsFailed'); showHttpError(cause, 'generation.modelsFailed') }
}
onMounted(loadModels)
</script>

<style scoped>
.generation-panel {
  overflow: visible;
  padding: 20px;
  background:
    radial-gradient(circle at 90% 0%, color-mix(in srgb, var(--el-color-primary) 7%, transparent), transparent 30%),
    var(--el-bg-color);
}

.generation-hero {
  position: relative;
  display: flex;
  align-items: flex-end;
  justify-content: space-between;
  gap: 28px;
  min-height: 164px;
  overflow: hidden;
  padding: 28px 30px 26px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 16%, var(--el-border-color-lighter));
  border-radius: 18px;
  background:
    linear-gradient(120deg, color-mix(in srgb, var(--el-color-primary) 13%, var(--el-bg-color)), var(--el-bg-color) 54%, color-mix(in srgb, #7c6cf2 8%, var(--el-bg-color)));
  box-shadow: 0 14px 34px color-mix(in srgb, var(--el-color-primary) 10%, transparent);
}

.generation-hero::before,
.generation-hero::after {
  position: absolute;
  border-radius: 50%;
  content: '';
  pointer-events: none;
}

.generation-hero::before {
  top: -92px;
  right: 15%;
  width: 230px;
  height: 230px;
  border: 1px solid color-mix(in srgb, var(--el-color-primary) 16%, transparent);
  box-shadow: 0 0 0 24px color-mix(in srgb, var(--el-color-primary) 5%, transparent), 0 0 0 48px color-mix(in srgb, var(--el-color-primary) 3%, transparent);
}

.generation-hero::after {
  right: -72px;
  bottom: -112px;
  width: 230px;
  height: 230px;
  background: color-mix(in srgb, #7c6cf2 8%, transparent);
}

.generation-hero-copy,
.generation-type-switch { position: relative; z-index: 1; }
.generation-hero-copy { min-width: 0; }
.generation-kicker,
.card-eyebrow {
  display: block;
  color: var(--el-color-primary);
  font-size: 11px;
  font-weight: 800;
  letter-spacing: .13em;
  line-height: 1.2;
  text-transform: uppercase;
}

.generation-hero h2 { margin: 8px 0 8px; color: var(--el-text-color-primary); font-size: clamp(26px, 3vw, 38px); letter-spacing: -.04em; line-height: 1.08; }
.generation-hero p { max-width: 700px; margin: 0; color: var(--el-text-color-secondary); line-height: 1.65; }
.generation-type-switch { padding: 4px; border: 1px solid color-mix(in srgb, var(--el-color-primary) 18%, var(--el-border-color)); border-radius: 13px; background: color-mix(in srgb, var(--el-bg-color) 76%, transparent); box-shadow: 0 8px 18px color-mix(in srgb, var(--el-color-primary) 8%, transparent); }
.generation-type-switch :deep(.el-radio-button__inner) { display: flex; align-items: center; justify-content: center; gap: 8px; min-width: 138px; padding: 11px 16px; border: 0; border-radius: 9px !important; background: transparent; box-shadow: none !important; color: var(--el-text-color-secondary); font-weight: 650; transition: background-color .2s ease, color .2s ease, transform .2s ease; }
.generation-type-switch :deep(.el-radio-button:first-child .el-radio-button__inner) { border-left: 0; }
.generation-type-switch :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner) { background: var(--el-color-primary); color: #fff; box-shadow: 0 6px 14px color-mix(in srgb, var(--el-color-primary) 28%, transparent) !important; }
.generation-type-switch :deep(.el-radio-button__inner:hover) { color: var(--el-color-primary); }
.generation-type-switch :deep(.el-radio-button__original-radio:checked + .el-radio-button__inner:hover) { color: #fff; transform: translateY(-1px); }
.type-switch-icon { font-size: 16px; line-height: 1; }

.generation-workflow { display: flex; align-items: center; gap: 9px; margin: 18px 4px 15px; color: var(--el-text-color-secondary); font-size: 12px; }
.workflow-step { display: inline-flex; align-items: center; gap: 8px; min-width: 0; white-space: nowrap; }
.workflow-step span { display: grid; flex: 0 0 25px; place-items: center; width: 25px; height: 25px; border: 1px solid var(--el-border-color); border-radius: 50%; background: var(--el-bg-color); color: var(--el-text-color-secondary); font-size: 11px; font-weight: 700; transition: border-color .2s ease, background-color .2s ease, color .2s ease; }
.workflow-step.is-active { color: var(--el-color-primary); font-weight: 700; }
.workflow-step.is-active span { border-color: var(--el-color-primary); background: var(--el-color-primary); color: #fff; box-shadow: 0 0 0 4px var(--el-color-primary-light-9); }
.workflow-line { flex: 1 1 52px; max-width: 110px; height: 1px; background: linear-gradient(90deg, var(--el-border-color), var(--el-border-color-lighter)); }

.generation-guide { margin: 0 0 18px; padding: 12px 15px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: linear-gradient(100deg, var(--el-fill-color-light), color-mix(in srgb, var(--el-color-primary) 3%, var(--el-bg-color))); color: var(--el-text-color-regular); }
.generation-guide summary { display: flex; align-items: center; gap: 10px; cursor: pointer; color: var(--el-text-color-primary); font-weight: 700; list-style-position: inside; }
.generation-guide summary::marker { color: var(--el-color-primary); }
.generation-guide summary span { color: var(--el-text-color-secondary); font-size: 12px; font-weight: 400; }
.generation-guide ol { margin: 11px 0 6px 21px; padding: 0; line-height: 1.75; }
.generation-guide p { margin: 7px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.55; }
.generation-alert { padding: 11px 13px; border: 1px solid color-mix(in srgb, var(--el-color-danger) 30%, transparent); border-radius: 10px; background: color-mix(in srgb, var(--el-color-danger) 7%, var(--el-bg-color)); }

.generation-layout { display: grid; grid-template-columns: 292px minmax(0, 1fr); gap: 22px; min-height: 620px; align-items: start; }
.generation-sidebar { position: static; display: flex; min-width: 0; flex-direction: column; gap: 14px; }
.setup-card,
.history-card,
.composer-card,
.asset-section,
.options-section { border: 1px solid var(--el-border-color-lighter); border-radius: 14px; background: var(--el-bg-color); box-shadow: 0 7px 22px color-mix(in srgb, #243b68 5%, transparent); }
.setup-card,
.history-card { padding: 17px; }
.setup-card { background: linear-gradient(160deg, var(--el-bg-color), color-mix(in srgb, var(--el-color-primary) 3%, var(--el-bg-color))); }
.setup-card .card-eyebrow { margin-bottom: 14px; color: var(--el-text-color-secondary); }
.setup-form { display: grid; gap: 8px; }
.setup-field { margin-bottom: 0 !important; padding: 0; }
.setup-field :deep(.el-form-item__label) { display: flex; align-items: center; gap: 6px; margin-bottom: 7px; color: var(--el-text-color-secondary); font-size: 11px; font-weight: 700; line-height: 1.3; }
.setup-field:first-child :deep(.el-form-item__label)::before { content: '◈'; color: var(--el-color-primary); font-size: 12px; }
.setup-field:nth-child(2) :deep(.el-form-item__label)::before { content: '✦'; color: var(--el-color-primary); font-size: 12px; }
.setup-field :deep(.el-select) { width: 100%; }
.setup-field :deep(.el-select__wrapper) { min-height: 38px; padding: 0 12px; border: 1px solid color-mix(in srgb, var(--el-color-primary) 12%, var(--el-border-color)); border-radius: 10px; background: var(--el-bg-color); box-shadow: 0 3px 10px color-mix(in srgb, #243b68 6%, transparent); transition: border-color .2s ease, box-shadow .2s ease, background-color .2s ease; }
.setup-field :deep(.el-select__wrapper:hover) { border-color: var(--el-color-primary-light-5); background: color-mix(in srgb, var(--el-color-primary) 2%, var(--el-bg-color)); }
.setup-field :deep(.el-select__wrapper.is-focused) { border-color: var(--el-color-primary); box-shadow: 0 0 0 3px var(--el-color-primary-light-9), 0 5px 12px color-mix(in srgb, var(--el-color-primary) 12%, transparent); }
.setup-field :deep(.el-select__wrapper.is-disabled) { border-color: var(--el-border-color-lighter); background: var(--el-fill-color-light); box-shadow: none; }
.setup-field :deep(.el-input__inner) { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.model-select,
.mode-select { width: 100%; }
.capability-card { display: grid; gap: 10px; margin-top: 3px; padding: 12px; border: 1px solid color-mix(in srgb, var(--el-color-primary) 13%, var(--el-border-color-lighter)); border-radius: 10px; background: linear-gradient(135deg, var(--el-color-primary-light-9), var(--el-fill-color-light)); color: var(--el-text-color-secondary); font-size: 12px; }
.capability-title { display: flex; align-items: center; gap: 8px; color: var(--el-text-color-primary); font-weight: 750; }
.capability-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--el-color-primary); box-shadow: 0 0 0 4px var(--el-color-primary-light-8); }
.capability-chips { display: flex; flex-wrap: wrap; gap: 5px; }
.capability-chip { padding: 4px 8px; border: 1px solid var(--el-border-color-lighter); border-radius: 999px; background: color-mix(in srgb, var(--el-bg-color) 80%, transparent); font-size: 11px; }
.capability-limits { color: var(--el-text-color-secondary); font-size: 11px; line-height: 1.5; }
.history-card { min-height: 230px; background: var(--el-fill-color-light); }
.history-heading,
.section-title-row,
.result-heading { display: flex; align-items: flex-start; justify-content: space-between; gap: 12px; }
.history-heading strong { display: block; margin-top: 5px; color: var(--el-text-color-primary); font-size: 13px; }
.history-empty { padding: 30px 8px; color: var(--el-text-color-secondary); font-size: 12px; text-align: center; }
.generation-history-item { display: grid; grid-template-columns: 30px minmax(0, 1fr) auto; align-items: center; gap: 9px; width: 100%; margin-top: 7px; padding: 9px 5px; border: 0; border-bottom: 1px solid var(--el-border-color-lighter); background: transparent; color: var(--el-text-color-regular); text-align: left; cursor: pointer; transition: background-color .2s ease, border-color .2s ease, transform .2s ease; }
.generation-history-item:hover { border-radius: 8px; background: var(--el-bg-color); transform: translateX(2px); }
.generation-history-item:focus-visible { outline: 2px solid var(--el-color-primary); outline-offset: 2px; }
.history-item-preview { display: grid; place-items: center; width: 29px; height: 29px; border: 1px solid var(--el-color-primary-light-8); border-radius: 8px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 12px; }
.history-item-preview img { display: block; width: 100%; height: 100%; border-radius: 7px; object-fit: cover; }
.history-item-preview.history-failed,
.history-item-preview.history-cancelled { border-color: var(--el-color-danger-light-8); background: var(--el-color-danger-light-9); color: var(--el-color-danger); }
.history-item-copy { display: grid; min-width: 0; gap: 3px; }
.history-item-copy strong,
.history-item-copy small { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-item-copy strong { font-size: 12px; font-weight: 650; }
.history-item-copy small { color: var(--el-text-color-secondary); font-size: 11px; }
.history-item-prompt { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-item-time { font-size: 10px !important; }
.history-item-copy .history-item-error { color: var(--el-color-danger); }

.generation-main { min-width: 0; }
.generation-canvas { display: grid; gap: 14px; min-width: 0; }
.composer-card,
.asset-section,
.options-section { padding: 19px; }
.composer-card { border-color: color-mix(in srgb, var(--el-color-primary) 22%, var(--el-border-color-lighter)); background: linear-gradient(145deg, color-mix(in srgb, var(--el-color-primary) 5%, var(--el-bg-color)), var(--el-bg-color) 50%); }
.composer-card h3,
.asset-section h3,
.options-section h3 { margin: 5px 0 0; color: var(--el-text-color-primary); font-size: 17px; letter-spacing: -.015em; }
.section-hint { max-width: 250px; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; text-align: right; }
.prompt-input { margin-top: 15px; }
.prompt-input :deep(.el-textarea__inner) { min-height: 150px !important; padding: 15px 16px; border: 1px solid var(--el-border-color); border-radius: 11px; background: color-mix(in srgb, var(--el-fill-color-light) 70%, var(--el-bg-color)); box-shadow: inset 0 1px 2px rgb(31 48 82 / 3%); line-height: 1.7; transition: border-color .2s ease, box-shadow .2s ease, background-color .2s ease; }
.prompt-input :deep(.el-textarea__inner:hover) { border-color: var(--el-color-primary-light-5); }
.prompt-input :deep(.el-textarea__inner:focus) { border-color: var(--el-color-primary); background: var(--el-bg-color); box-shadow: 0 0 0 3px var(--el-color-primary-light-9); }
.prompt-footer { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin-top: 12px; color: var(--el-text-color-secondary); font-size: 11px; }
.prompt-state { display: inline-flex; align-items: center; gap: 7px; color: var(--el-text-color-primary); font-weight: 700; }
.prompt-state-dot { width: 7px; height: 7px; border-radius: 50%; background: var(--el-color-primary); box-shadow: 0 0 0 4px var(--el-color-primary-light-9); }
.generation-input-grid { display: grid; grid-template-columns: minmax(0, 1.18fr) minmax(300px, .82fr); gap: 14px; align-items: stretch; }
.generation-input-grid .asset-section,
.generation-input-grid .options-section { min-width: 0; margin-top: 0; }
.generation-input-grid .options-section { background: color-mix(in srgb, var(--el-fill-color-light) 34%, var(--el-bg-color)); }
.asset-count { min-width: 48px; padding: 5px 9px; border: 1px solid var(--el-color-primary-light-8); border-radius: 999px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 12px; font-weight: 700; text-align: center; }
.asset-count.is-full { border-color: var(--el-color-warning-light-8); background: var(--el-color-warning-light-9); color: var(--el-color-warning); }
.asset-dropzone { display: grid; justify-items: center; gap: 6px; min-height: 148px; margin-top: 15px; padding: 24px 16px; border: 1px dashed color-mix(in srgb, var(--el-color-primary) 52%, var(--el-border-color)); border-radius: 12px; background: linear-gradient(145deg, var(--el-color-primary-light-9), color-mix(in srgb, var(--el-color-primary) 3%, var(--el-bg-color))); color: var(--el-text-color-secondary); text-align: center; cursor: pointer; transition: border-color .2s ease, background-color .2s ease, box-shadow .2s ease, transform .2s ease; }
.asset-dropzone:hover,
.asset-dropzone:focus-within { border-color: var(--el-color-primary); background: var(--el-color-primary-light-8); box-shadow: 0 8px 18px color-mix(in srgb, var(--el-color-primary) 11%, transparent); transform: translateY(-1px); }
.asset-dropzone.is-disabled { cursor: not-allowed; opacity: .6; transform: none; }
.dropzone-icon { display: grid; place-items: center; width: 34px; height: 34px; border-radius: 11px; background: var(--el-color-primary); color: #fff; font-size: 21px; box-shadow: 0 6px 12px color-mix(in srgb, var(--el-color-primary) 25%, transparent); }
.asset-dropzone strong { color: var(--el-text-color-primary); font-size: 13px; }
.asset-dropzone small,
.asset-actions span { color: var(--el-text-color-secondary); font-size: 11px; }
.asset-dropzone .el-button { margin-top: 5px; }
.asset-list { display: grid; grid-template-columns: repeat(auto-fill, minmax(220px, 1fr)); gap: 10px; margin-top: 13px; }
.asset-item { display: grid; grid-template-columns: 54px minmax(0, 1fr); align-items: center; gap: 9px; min-width: 0; padding: 9px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-light); transition: border-color .2s ease, box-shadow .2s ease; }
.asset-item:hover { border-color: var(--el-color-primary-light-7); box-shadow: 0 5px 14px color-mix(in srgb, #243b68 6%, transparent); }
.asset-preview,
.asset-preview img,
.asset-preview video,
.asset-file { width: 54px; height: 54px; border-radius: 7px; }
.asset-preview { overflow: hidden; background: var(--el-fill-color); }
.asset-preview img,
.asset-preview video { display: block; object-fit: cover; }
.asset-file { display: grid; place-items: center; color: var(--el-text-color-secondary); font-size: 10px; }
.asset-copy { display: grid; min-width: 0; gap: 3px; }
.asset-copy small { color: var(--el-text-color-secondary); font-size: 10px; }
.asset-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.asset-item .el-select,
.asset-item .el-button { grid-column: 2; width: 100%; }
.asset-actions { display: flex; align-items: center; justify-content: space-between; gap: 10px; margin-top: 13px; }

.generation-options { display: grid; gap: 8px; margin-top: 13px; }
.output-setting-group { display: grid; grid-template-columns: minmax(126px, .62fr) minmax(0, 1.38fr); align-items: start; gap: 12px; padding: 11px; border: 1px solid var(--el-border-color-lighter); border-radius: 10px; background: var(--el-fill-color-light); }
.output-setting-group-common { background: var(--el-bg-color); }
.output-setting-heading { display: flex; align-items: flex-start; gap: 8px; min-width: 0; }
.output-setting-icon { display: grid; flex: 0 0 25px; place-items: center; width: 25px; height: 25px; border: 1px solid var(--el-color-primary-light-8); border-radius: 7px; background: var(--el-color-primary-light-9); color: var(--el-color-primary); font-size: 12px; }
.output-setting-heading strong,
.output-setting-heading small { display: block; }
.output-setting-heading strong { color: var(--el-text-color-primary); font-size: 12px; font-weight: 750; line-height: 1.35; }
.output-setting-heading small { display: -webkit-box; max-width: 180px; margin-top: 3px; overflow: hidden; color: var(--el-text-color-secondary); font-size: 10px; line-height: 1.35; -webkit-box-orient: vertical; -webkit-line-clamp: 2; }
.output-setting-fields { display: grid; grid-template-columns: repeat(auto-fit, minmax(96px, 1fr)); align-items: end; gap: 8px 9px; min-width: 0; }
.generation-options.is-video .output-setting-fields { grid-template-columns: repeat(auto-fit, minmax(104px, 1fr)); }
.output-setting-fields :deep(.el-form-item) { margin-bottom: 0; }
.output-setting-fields :deep(.el-form-item__label) { margin-bottom: 4px; color: var(--el-text-color-secondary); font-size: 10px; font-weight: 650; line-height: 1.25; }
.output-setting-fields :deep(.el-select),
.output-setting-fields :deep(.el-input),
.output-setting-fields :deep(.el-input-number) { width: 100%; }
.output-setting-fields :deep(.el-input__wrapper),
.output-setting-fields :deep(.el-input-number .el-input__wrapper) { min-height: 30px; border-radius: 7px; }
.output-setting-fields :deep(.el-switch) { height: 30px; }
.video-output-fields { display: grid; grid-template-columns: 1fr; gap: 8px; }
.generation-options.is-video .video-output-fields { grid-template-columns: 1fr; }
.video-output-row { display: grid; align-items: end; gap: 8px 9px; min-width: 0; }
.video-output-specs { grid-template-columns: repeat(3, minmax(0, 1fr)); }
.video-output-toggles,
.video-output-extra { grid-template-columns: repeat(2, minmax(0, 1fr)); }
.generation-hint { margin: 12px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.55; }
.generation-actions { display: flex; align-items: center; justify-content: flex-end; gap: 10px; margin-top: 0; padding: 13px 16px; border: 1px solid var(--el-border-color-lighter); border-radius: 12px; background: var(--el-fill-color-light); }
.submit-hint { flex: 1; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.45; }
.generation-actions :deep(.el-button--primary) { min-width: 112px; }

.result-heading { align-items: center; }
.result-meta { display: flex; flex-wrap: wrap; gap: 7px 14px; margin-top: 9px; color: var(--el-text-color-secondary); font-size: 12px; }
.result-meta span + span::before { content: '·'; margin-right: 14px; color: var(--el-border-color); }
.generation-progress { display: flex; align-items: center; gap: 8px; margin: 15px 0 0; color: var(--el-color-primary); font-size: 13px; font-weight: 600; }
.progress-dot { width: 8px; height: 8px; border-radius: 50%; background: currentColor; box-shadow: 0 0 0 4px var(--el-color-primary-light-9); animation: generation-pulse 1.1s ease-in-out infinite; }
.result-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(200px, 1fr)); gap: 14px; margin-top: 17px; }
.result-card { overflow: hidden; border: 1px solid var(--el-border-color-lighter); border-radius: 11px; background: var(--el-fill-color-light); transition: border-color .2s ease, box-shadow .2s ease, transform .2s ease; }
.result-card:hover { border-color: var(--el-color-primary-light-7); box-shadow: 0 10px 22px color-mix(in srgb, #243b68 10%, transparent); transform: translateY(-2px); }
.result-media { display: block; width: 100%; height: 238px; object-fit: contain; background: var(--el-fill-color); }
.result-card-footer { display: grid; gap: 3px; padding: 10px 11px; }
.result-card-title { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-width: 0; }
.result-card-footer strong { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.result-card-footer small { color: var(--el-text-color-secondary); font-size: 11px; }
.result-empty { display: grid; justify-items: center; gap: 7px; margin-top: 17px; padding: 44px 16px; border: 1px dashed var(--el-border-color); border-radius: 11px; background: var(--el-fill-color-light); color: var(--el-text-color-secondary); text-align: center; }
.result-empty strong { color: var(--el-text-color-primary); font-size: 13px; }
.result-empty small { max-width: 360px; font-size: 11px; line-height: 1.5; }
.result-empty-icon,
.loading-spark { color: var(--el-color-primary); font-size: 25px; }
.loading-spark { animation: generation-spin 1.4s linear infinite; }
.generation-error { margin: 10px 0; color: var(--el-color-danger); line-height: 1.5; }
.generation-empty { margin: 6px 0 0; color: var(--el-text-color-secondary); font-size: 12px; line-height: 1.5; }
.generation-empty p { margin: 0 0 2px; }
.history-dialog-content { display: grid; gap: 14px; }
.history-dialog-heading { margin-bottom: 0; }
.history-dialog-content .result-meta,
.history-dialog-content .result-grid,
.history-dialog-content .result-empty { margin-top: 0; }
.history-dialog-content .generation-alert { margin: 0; }
:deep(.generation-history-dialog .el-dialog__body) { max-height: none; overflow: visible; }

@keyframes generation-pulse { 0%, 100% { opacity: .35; transform: scale(.8); } 50% { opacity: 1; transform: scale(1.1); } }
@keyframes generation-spin { to { transform: rotate(360deg); } }

@media (max-width: 1000px) {
  .generation-panel { padding: 18px; }
  .generation-layout { grid-template-columns: 238px minmax(0, 1fr); gap: 16px; }
  .generation-input-grid { grid-template-columns: 1fr; }
  .output-setting-fields,
  .generation-options.is-video .output-setting-fields { grid-template-columns: repeat(2, minmax(130px, 1fr)); }
  .generation-options.is-video .video-output-fields { grid-template-columns: 1fr; }
  .video-output-specs { grid-template-columns: repeat(3, minmax(0, 1fr)); }
}

@media (max-width: 780px) {
  .generation-hero { align-items: stretch; flex-direction: column; min-height: 0; padding: 24px 22px; }
  .generation-type-switch { align-self: flex-start; }
  .generation-layout { grid-template-columns: 1fr; }
  .generation-sidebar { display: grid; grid-template-columns: minmax(0, 1fr) minmax(0, 1fr); align-items: start; }
  .history-card { min-height: 0; }
}

@media (max-width: 560px) {
  .generation-panel { padding: 12px; }
  .generation-hero { padding: 22px 18px; border-radius: 14px; }
  .generation-hero h2 { font-size: 27px; }
  .generation-workflow { gap: 5px; margin: 14px 1px 13px; font-size: 10px; }
  .workflow-step { gap: 5px; }
  .workflow-step span { flex-basis: 22px; width: 22px; height: 22px; }
  .workflow-line { flex-basis: 13px; }
  .generation-guide summary { align-items: flex-start; flex-direction: column; gap: 3px; }
  .generation-sidebar { display: flex; }
  .prompt-footer { align-items: flex-start; flex-direction: column; gap: 6px; }
  .generation-type-switch { width: 100%; }
  .generation-type-switch :deep(.el-radio-button) { flex: 1; }
  .generation-type-switch :deep(.el-radio-button__inner) { min-width: 0; width: 100%; padding: 10px 8px; }
  .composer-card,
  .asset-section,
  .options-section,
  .setup-card,
  .history-card { padding: 15px; border-radius: 12px; }
  .section-title-row { display: block; }
  .section-hint { display: block; max-width: none; margin-top: 6px; text-align: left; }
  .output-setting-group { grid-template-columns: 1fr; }
  .output-setting-fields,
  .generation-options.is-video .output-setting-fields { grid-template-columns: 1fr; }
  .video-output-specs,
  .video-output-toggles,
  .video-output-extra { grid-template-columns: 1fr; }
  .generation-actions { align-items: stretch; flex-wrap: wrap; }
  .submit-hint { flex-basis: 100%; }
  .generation-actions .el-button { flex: 1; }
  .result-grid { grid-template-columns: 1fr 1fr; gap: 9px; }
  .result-media { height: 170px; }
}
</style>
