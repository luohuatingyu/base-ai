<!-- AI 生成工作区：按模型能力提供图片与视频生成模式、素材角色和任务历史。 -->
<template>
  <div class="panel generation-panel">
    <div class="section-head">
      <div><h2>{{ t('generation.title') }}</h2><p>{{ t('generation.subtitle') }}</p></div>
      <el-radio-group v-model="type" size="small" @change="onTypeChange">
        <el-radio-button value="image_model">{{ t('generation.image') }}</el-radio-button>
        <el-radio-button value="video_model">{{ t('generation.video') }}</el-radio-button>
      </el-radio-group>
    </div>
    <div v-if="error" class="generation-error" role="alert">{{ error }}</div>
    <div class="generation-layout">
      <aside class="generation-sidebar">
        <el-form label-position="top" size="small">
          <el-form-item :label="t('generation.model')">
            <el-select v-model="modelId" filterable @change="onModelChange">
              <el-option v-for="model in modelsForType" :key="model.id" :label="`${model.name} · ${model.modelName}`" :value="model.id" />
            </el-select>
          </el-form-item>
          <el-form-item v-if="capability.modes?.length" :label="t('generation.mode')">
            <el-select v-model="mode" @change="onModeChange">
              <el-option v-for="value in capability.modes" :key="value" :label="modeLabel(value)" :value="value" />
            </el-select>
          </el-form-item>
        </el-form>
        <div class="capability-card" v-if="capability.profile">
          <strong>{{ capability.profile }}</strong>
          <span>{{ t('generation.inputLimit', { count: capability.totalLimit }) }}</span>
          <span v-if="capability.features?.groups">{{ t('generation.groupsSupported') }}</span>
          <span v-if="capability.features?.layers">{{ t('generation.layersSupported') }}</span>
          <span v-if="capability.features?.draft">{{ t('generation.draftSupported') }}</span>
        </div>
        <div class="history-heading"><span>{{ t('generation.history') }}</span><el-button text size="small" @click="loadHistory">{{ t('generation.refresh') }}</el-button></div>
        <div v-if="!history.length" class="history-empty">{{ t('generation.noHistory') }}</div>
        <button v-for="task in history" :key="task.id" class="generation-history-item" @click="selectHistory(task)">
          <span>{{ task.status }} · {{ task.id }}</span><small>{{ task.generationMode || '' }}</small>
        </button>
      </aside>
      <main class="generation-main">
        <el-form label-position="top" @submit.prevent="submit">
          <el-form-item :label="t('generation.prompt')">
            <el-input v-model="prompt" type="textarea" :rows="5" :placeholder="t('generation.promptPlaceholder')" />
          </el-form-item>
          <section class="asset-section">
            <div class="asset-heading"><span>{{ t('generation.assets') }}</span><span>{{ inputs.length }}/{{ capability.totalLimit || 0 }}</span></div>
            <div class="asset-list">
              <div v-for="(input, index) in inputs" :key="input.dataUrl + index" class="asset-item">
                <img v-if="input.kind === 'IMAGE'" :src="input.dataUrl" :alt="input.fileName" />
                <video v-else-if="input.kind === 'VIDEO'" controls muted :src="input.dataUrl" />
                <span v-else class="asset-file">{{ input.fileName }}</span>
                <span class="asset-name" :title="input.fileName">{{ input.fileName }}</span>
                <el-select v-if="type === 'video_model' && input.kind !== 'TEXT'" v-model="input.role" size="small" :placeholder="t('generation.role')">
                  <el-option v-for="role in rolesFor(input.kind)" :key="role" :label="roleLabel(role)" :value="role" />
                </el-select>
                <el-button text type="danger" size="small" @click="removeInput(index)">{{ t('common.delete') }}</el-button>
              </div>
            </div>
            <input ref="filePicker" type="file" hidden multiple :accept="accept" @change="onFiles" />
            <el-button plain :disabled="busy || inputs.length >= (capability.totalLimit || 0)" @click="filePicker?.click()">{{ t('generation.addAsset') }}</el-button>
            <el-button v-if="mode === 'DRAFT_TO_VIDEO'" plain @click="addDraftTask">{{ t('generation.useDraft') }}</el-button>
          </section>
          <div class="generation-options">
            <el-form-item v-if="type === 'image_model'" :label="t('generation.imageSize')"><el-input v-model="options.size" placeholder="2K" /></el-form-item>
            <el-form-item v-if="type === 'image_model' && capability.features?.groups" :label="t('generation.groupOutput')"><el-switch v-model="groupOutput" @change="syncGroupOptions" /></el-form-item>
            <el-form-item v-if="groupOutput" :label="t('generation.maxImages')"><el-input-number v-model="options.sequential_image_generation_options.max_images" :min="1" :max="15" /></el-form-item>
            <el-form-item v-if="type === 'image_model' && capability.features?.layers" :label="t('generation.layerDecomposition')"><el-switch v-model="options.layer_decomposition" /></el-form-item>
            <el-form-item v-if="type === 'image_model' && capability.features?.transparent" :label="t('generation.transparentBackground')"><el-switch v-model="transparent" @change="syncTransparent" /></el-form-item>
            <el-form-item v-if="type === 'video_model'" :label="t('generation.resolution')"><el-select v-model="options.resolution"><el-option v-for="value in resolutionOptions" :key="value" :label="value" :value="value" /></el-select></el-form-item>
            <el-form-item v-if="type === 'video_model'" :label="t('generation.ratio')"><el-select v-model="options.ratio"><el-option v-for="value in ratioOptions" :key="value" :label="value" :value="value" /></el-select></el-form-item>
            <el-form-item v-if="type === 'video_model'" :label="t('generation.duration')"><el-input-number v-model="options.duration" :min="capability.durationMin || 2" :max="30" /></el-form-item>
            <el-form-item v-if="type === 'video_model' && capability.features?.audio" :label="t('generation.generateAudio')"><el-switch v-model="options.generate_audio" /></el-form-item>
            <el-form-item v-if="type === 'video_model' && capability.features?.draft" :label="t('generation.draft')"><el-switch v-model="options.draft" /></el-form-item>
            <el-form-item :label="t('generation.watermark')"><el-switch v-model="options.watermark" /></el-form-item>
          </div>
          <div class="generation-actions"><el-button type="primary" :loading="busy" :disabled="!canSubmit" @click="submit">{{ t('generation.submit') }}</el-button><el-button @click="reset">{{ t('generation.reset') }}</el-button></div>
        </el-form>
        <section v-if="result" class="generation-result">
          <div class="result-heading"><h3>{{ t('generation.result') }}</h3><el-tag :type="result.status === 'SUCCEEDED' ? 'success' : result.status === 'FAILED' ? 'danger' : 'warning'">{{ result.status }}</el-tag></div>
          <p v-if="result.errorMessage" class="generation-error">{{ result.errorMessage }}</p>
          <div class="result-grid"><template v-for="(output, index) in outputs" :key="output.value + index"><el-image v-if="output.mediaType === 'image'" :src="output.kind === 'base64' ? `data:image/png;base64,${output.value}` : output.value" fit="contain" preview-teleported /><video v-else controls :src="output.value" /></template></div>
          <div v-if="result.outputJson?.errors?.length" class="generation-error">{{ t('generation.partialFailure', { count: result.outputJson.errors.length }) }}</div>
          <el-button v-if="result.status === 'RUNNING' || result.status === 'QUEUED'" text @click="poll(result.id)">{{ t('generation.refresh') }}</el-button>
        </section>
      </main>
    </div>
  </div>
</template>

<script setup>
import { computed, onMounted, reactive, ref } from 'vue'
import { ElMessage } from 'element-plus'
import { useI18n } from 'vue-i18n'
import http, { showHttpError } from '../api/http'

const { t } = useI18n()
const type = ref('image_model')
const models = ref([])
const modelId = ref(null)
const capability = ref({ modes: [], features: {}, options: {}, totalLimit: 0 })
const mode = ref('TEXT_TO_IMAGE')
const prompt = ref('')
const inputs = ref([])
const history = ref([])
const result = ref(null)
const busy = ref(false)
const error = ref('')
const filePicker = ref(null)
const groupOutput = ref(false)
const transparent = ref(false)
const options = reactive({ size: '', response_format: 'url', sequential_image_generation_options: { max_images: 4 }, layer_decomposition: false, background: 'opaque', output_format: 'png', resolution: '720p', ratio: 'adaptive', duration: 5, generate_audio: true, draft: false, watermark: false })
const modelsForType = computed(() => models.value.filter(item => item.modelType === type.value))
const selectedModel = computed(() => models.value.find(item => item.id === modelId.value))
const accept = computed(() => type.value === 'image_model' ? 'image/png,image/jpeg,image/webp,image/bmp,image/tiff,image/gif,image/heic,image/heif,text/plain,text/markdown,.txt,.md' : 'image/png,image/jpeg,image/webp,video/mp4,video/webm,video/quicktime,audio/mpeg,audio/wav,.mp3,.wav')
const canSubmit = computed(() => !busy.value && !!modelId.value && !!mode.value && (!!prompt.value.trim() || inputs.value.length > 0))
const outputs = computed(() => result.value?.outputJson?.outputs || [])
const resolutionOptions = computed(() => capability.value.options?.resolution?.enum || ['480p', '720p', '1080p'])
const ratioOptions = computed(() => capability.value.options?.ratio?.enum || ['adaptive', '16:9', '9:16'])
/** 只提交当前模型能力目录声明的参数，避免隐藏控件的默认值污染请求。 */
const requestOptions = computed(() => Object.fromEntries(Object.entries(options).filter(([key, value]) => capability.value.options?.[key] && value !== '' && value !== undefined && value !== null)))

/** 将后端模式编码转换为用户可理解的生成方式。 */
function modeLabel(value) { return t(`generation.modes.${value}`, value.replaceAll('_', ' ')) }
/** 根据素材类型限定方舟内容角色，首尾帧不能用于音视频素材。 */
function rolesFor(kind) { return kind === 'IMAGE' ? ['reference_image', 'first_frame', 'last_frame'] : kind === 'VIDEO' ? ['reference_video'] : ['reference_audio'] }
/** 将角色编码本地化。 */
function roleLabel(value) { return t(`generation.roles.${value}`, value.replaceAll('_', ' ')) }
/** 读取选定模型的能力目录并同步合法模式。 */
function onModelChange() {
  const item = selectedModel.value
  capability.value = item?.capabilities || { modes: [], features: {}, options: {}, totalLimit: 0 }
  mode.value = capability.value.modes?.[0] || ''
  resetOptions()
}
/** 切换图片或视频时只显示同类模型并清理不兼容素材。 */
function onTypeChange() { modelId.value = modelsForType.value[0]?.id || null; inputs.value = []; result.value = null; onModelChange(); loadHistory() }
/** 切换模式时清除旧角色，避免角色与任务类型混用。 */
function onModeChange() { inputs.value.forEach(input => { input.role = type.value === 'video_model' ? (mode.value.includes('FRAME') ? '' : input.role) : '' }) }
/** 根据组图开关同步供应商参数。 */
function syncGroupOptions(value) { options.sequential_image_generation = value ? 'auto' : 'disabled' }
/** 透明背景要求 PNG，关闭时恢复默认不透明。 */
function syncTransparent(value) { options.background = value ? 'transparent' : 'opaque'; if (value) options.output_format = 'png' }
/** 清空所有生成选项并恢复安全默认值。 */
function resetOptions() { Object.assign(options, { size: '', response_format: 'url', sequential_image_generation: groupOutput.value ? 'auto' : 'disabled', sequential_image_generation_options: { max_images: 4 }, layer_decomposition: false, background: transparent.value ? 'transparent' : 'opaque', output_format: 'png', resolution: '720p', ratio: 'adaptive', duration: 5, generate_audio: true, draft: false, watermark: false }) }
/** 清空当前草稿和结果。 */
function reset() { prompt.value = ''; inputs.value = []; result.value = null; error.value = ''; resetOptions() }
/** 读取文件并转换为后端接受的 Data URL。 */
function readFile(file) { return new Promise((resolve, reject) => { const reader = new FileReader(); reader.onload = () => resolve(String(reader.result)); reader.onerror = reject; reader.readAsDataURL(file) }) }
/** 校验文件类型和大小，防止浏览器提交超出模型能力的素材。 */
async function onFiles(event) {
  const files = Array.from(event.target.files || []); event.target.value = ''
  for (const file of files) {
    const kind = file.type.startsWith('image/') ? 'IMAGE' : file.type.startsWith('video/') ? 'VIDEO' : file.type.startsWith('audio/') ? 'AUDIO' : file.type.startsWith('text/') || /\.(txt|md)$/i.test(file.name) ? 'TEXT' : ''
    const max = kind === 'IMAGE' ? capability.value.imageMaxBytes : kind === 'VIDEO' ? capability.value.videoMaxBytes : capability.value.audioMaxBytes
    if (!kind || file.size > max || inputs.value.length >= capability.value.totalLimit) { ElMessage.warning(t('generation.fileRejected')); continue }
    try {
      const role = type.value !== 'video_model' || kind === 'TEXT' ? '' : kind === 'IMAGE' ? 'reference_image' : kind === 'VIDEO' ? 'reference_video' : 'reference_audio'
      inputs.value.push({ kind, fileName: file.name, contentType: file.type || (kind === 'TEXT' ? 'text/plain' : ''), dataUrl: await readFile(file), role })
    } catch (cause) { showHttpError(cause, 'generation.fileReadFailed') }
  }
}
/** 删除已选素材。 */
function removeInput(index) { inputs.value.splice(index, 1) }
/** 加入历史样片任务作为正式视频的唯一输入。 */
function addDraftTask() { const draft = history.value.find(item => item.status === 'SUCCEEDED' && item.outputJson?.outputs?.length && item.options?.draft); if (draft) inputs.value = [{ kind: 'TASK', fileName: `draft-${draft.id}`, contentType: 'text/plain', dataUrl: String(draft.id), role: '' }]; else ElMessage.warning(t('generation.noDraft')) }
/** 提交任务并轮询异步视频，图片响应直接完成。 */
async function submit() {
  if (!canSubmit.value) return
  busy.value = true; error.value = ''; result.value = null
  const payload = { modelType: type.value, modelId: modelId.value, prompt: prompt.value, generationMode: mode.value, inputs: inputs.value, options: requestOptions.value }
  try { result.value = (await http.post('/ai/generation', payload)).data; if (result.value.status === 'RUNNING' || result.value.status === 'QUEUED') await poll(result.value.id); await loadHistory() } catch (cause) { error.value = t('generation.submitFailed'); showHttpError(cause, 'generation.submitFailed') } finally { busy.value = false }
}
/** 查询任务终态，不重新创建任务。 */
async function poll(id) { const response = (await http.get(`/ai/generation/${id}`)).data; result.value = response; if (response.status === 'RUNNING' || response.status === 'QUEUED') { await new Promise(resolve => setTimeout(resolve, 1500)); return poll(id) } await loadHistory() }
/** 查询当前用户可见的历史任务。 */
async function loadHistory() { try { history.value = ((await http.get('/ai/generation', { params: { type: type.value, page: 0 } })).data.items || []) } catch { history.value = [] } }
/** 从历史记录恢复展示结果。 */
function selectHistory(task) { result.value = task }
/** 加载可用模型目录。 */
async function loadModels() { try { models.value = (await http.get('/ai/generation/models')).data || []; onTypeChange() } catch (cause) { error.value = t('generation.modelsFailed'); showHttpError(cause, 'generation.modelsFailed') } }
onMounted(loadModels)
</script>

<style scoped>
.generation-layout { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 20px; min-height: 620px; }
.generation-sidebar { border-right: 1px solid var(--el-border-color-lighter); padding-right: 18px; }
.capability-card { display: grid; gap: 6px; padding: 12px; margin: 12px 0 22px; background: var(--el-fill-color-light); border-radius: 8px; color: var(--el-text-color-secondary); font-size: 12px; }
.history-heading, .asset-heading, .result-heading { display: flex; justify-content: space-between; align-items: center; font-weight: 600; }
.generation-history-item { display: grid; width: 100%; padding: 9px 4px; border: 0; border-bottom: 1px solid var(--el-border-color-lighter); background: transparent; text-align: left; color: var(--el-text-color-regular); cursor: pointer; }
.generation-history-item small { color: var(--el-text-color-secondary); }
.generation-main { min-width: 0; }
.asset-section { padding: 14px; border: 1px solid var(--el-border-color-lighter); border-radius: 8px; }
.asset-list { display: flex; flex-wrap: wrap; gap: 10px; margin: 12px 0; }
.asset-item { display: grid; grid-template-columns: 70px minmax(80px, 1fr); gap: 5px; width: 220px; padding: 7px; border: 1px solid var(--el-border-color-lighter); border-radius: 6px; }
.asset-item img, .asset-item video { width: 70px; height: 70px; grid-row: span 3; object-fit: cover; }
.asset-file { width: 70px; height: 70px; grid-row: span 3; display: grid; place-items: center; overflow: hidden; background: var(--el-fill-color); font-size: 11px; }
.asset-name { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; font-size: 12px; }
.generation-options { display: grid; grid-template-columns: repeat(3, minmax(150px, 1fr)); gap: 12px; margin-top: 16px; }
.generation-actions { display: flex; gap: 10px; justify-content: flex-end; margin-top: 18px; }
.generation-result { margin-top: 24px; padding-top: 18px; border-top: 1px solid var(--el-border-color-lighter); }
.result-grid { display: grid; grid-template-columns: repeat(auto-fill, minmax(180px, 1fr)); gap: 12px; margin-top: 12px; }
.result-grid :deep(.el-image), .result-grid video { width: 100%; height: 220px; object-fit: contain; background: var(--el-fill-color-light); }
.generation-error { margin: 10px 0; color: var(--el-color-danger); }
@media (max-width: 900px) { .generation-layout { grid-template-columns: 1fr; } .generation-sidebar { border-right: 0; border-bottom: 1px solid var(--el-border-color-lighter); padding: 0 0 16px; } .generation-options { grid-template-columns: repeat(2, minmax(140px, 1fr)); } }
@media (max-width: 560px) { .generation-options { grid-template-columns: 1fr; } }
</style>
