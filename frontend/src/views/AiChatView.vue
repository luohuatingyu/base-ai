<!-- AI 对话页面：提交请求并展示模型响应、Trace 标识和 Token 统计。 -->
<template>
  <div class="panel chat-panel">
    <div class="section-head chat-heading"><div><h2>{{ t('chat.title') }}</h2><p>{{ t('chat.subtitle') }}</p></div><span class="chat-session-label">{{ conversations.find(item => item.id === conversationId)?.title || t('chat.untitled') }}</span></div>

    <div class="chat-workspace">
    <details class="chat-history" :open="historyInitiallyOpen">
      <summary class="history-heading">{{ t('chat.historyTitle') }} <span>{{ historyTotal }}</span><el-icon class="disclosure-icon"><ArrowDown /></el-icon></summary>
      <div class="history-toolbar">
        <el-button type="primary" plain :icon="Plus" :disabled="loading" @click="newConversation">{{ t('chat.newConversation') }}</el-button>
        <el-button class="history-refresh" text :icon="Refresh" :aria-label="t('chat.refreshHistory')" :title="t('chat.refreshHistory')" :loading="historyLoading" @click="refreshHistory" />
      </div>
      <div class="history-list">
      <p v-if="historyError" class="history-error" role="alert">{{ historyError }}</p><p v-else-if="historyLoading && !conversations.length" class="history-empty">{{ t('chat.loadingHistory') }}</p><p v-else-if="!conversations.length" class="history-empty">{{ t('chat.noConversations') }}</p>
      <div v-for="conversation in conversations" :key="conversation.id" class="history-item" :class="{ selected: conversation.id === conversationId }">
        <button class="history-title" :aria-current="conversation.id === conversationId ? 'true' : undefined" :title="conversation.title || t('chat.untitled')" :disabled="loading" @click="selectConversation(conversation.id)"><el-icon><ChatLineRound /></el-icon><span>{{ conversation.title || t('chat.untitled') }}</span></button>
        <el-button class="history-delete" text :icon="Delete" :aria-label="t('common.delete')" :title="t('common.delete')" :disabled="loading || conversation.generating" @click="deleteConversation(conversation.id)" />
      </div>
      </div>
      <el-pagination v-if="historyTotal > 20" small layout="prev, pager, next" :pager-count="5" :page-size="20" :total="historyTotal" :current-page="historyPage + 1" @current-change="changeHistoryPage" />
    </details>
    <el-tabs v-model="activeTab" class="chat-tabs">
      <el-tab-pane :label="t('chat.conversationTab')" name="conversation">
        <!-- 紧凑模型摘要固定在消息区上方，完整设置通过抽屉展示。 -->
        <div class="model-toolbar">
          <div class="model-summary"><span class="model-summary-type">{{ localizeModelType(modelType, modelTypes, t) }}</span><span class="model-summary-value" :title="mode === 'multi' ? (filteredRoutes.find(route => route.featureCode === featureCode)?.name || t('chat.defaultPool')) : providerId ? (currentModels.find(model => model.id === modelId)?.name || t('chat.selectModel')) : t('chat.selectProvider')">{{ mode === 'multi' ? (filteredRoutes.find(route => route.featureCode === featureCode)?.name || t('chat.defaultPool')) : providerId ? (currentModels.find(model => model.id === modelId)?.name || t('chat.selectModel')) : t('chat.selectProvider') }}</span></div>
          <el-button size="small" :icon="Setting" aria-haspopup="dialog" :aria-expanded="settingsOpen" @click="settingsOpen = true">{{ t('chat.settings') }}</el-button>
        </div>

        <div v-if="pageError" class="chat-error" role="alert"><span>{{ pageError }}</span><el-button v-if="!optionsReady" text :loading="optionsLoading" @click="loadOptions">{{ t('chat.retry') }}</el-button><el-button v-else-if="serverGenerating && !generating" text @click="syncGeneration(conversationId)">{{ t('chat.retry') }}</el-button></div>
        <div ref="messagesElement" class="messages" tabindex="0" :aria-label="t('chat.conversationTab')" @scroll="onMessagesScroll">
      <div v-for="(item, index) in messages" :key="item.id || index" :class="['message', item.role]">
        <!-- 仅 AI 回复展示头像和称呼，用户消息直接展示正文。 -->
        <template v-if="item.role === 'assistant'"><span class="message-avatar" aria-hidden="true"><el-icon><ChatLineRound /></el-icon></span><div class="message-author">{{ t('chat.assistant') }}</div></template>
        <div class="message-content">{{ item.content }}</div>
        <div v-if="item.status && item.status !== 'COMPLETED'" class="message-status" :class="{ 'is-generating': item.status === 'GENERATING' }" role="status"><span v-if="item.status === 'GENERATING'" class="generation-dot" aria-hidden="true" />{{ t(item.status === 'GENERATING' ? 'chat.generating' : 'chat.streamInterrupted') }}</div>
        <div v-if="item.images?.length" class="message-images">
          <el-image v-for="(image, imageIndex) in item.images" :key="image.name + image.dataUrl" :src="image.dataUrl" :alt="image.name" :preview-src-list="item.images.map(value => value.dataUrl)" :initial-index="imageIndex" fit="cover" preview-teleported :tabindex="0" />
        </div>
        <div v-if="item.generationInputs?.length" class="message-images">
          <template v-for="input in item.generationInputs" :key="input.fileName + input.dataUrl">
            <el-image v-if="input.kind === 'IMAGE'" :src="input.dataUrl" :alt="input.fileName" fit="cover" preview-teleported />
            <video v-else-if="input.kind === 'VIDEO'" controls muted preload="metadata" :src="input.dataUrl" />
            <audio v-else-if="input.kind === 'AUDIO'" controls :src="input.dataUrl" />
            <span v-else class="pending-generation-text" :title="input.fileName">{{ input.fileName }}</span>
          </template>
        </div>
        <div class="message-actions">
          <el-button v-if="item.role === 'assistant' && item.content" text size="small" :icon="CopyDocument" @click="copyAnswer(item)">{{ t('chat.copyAnswer') }}</el-button>
          <el-button v-if="item.role === 'user'" text size="small" :icon="EditPen" :disabled="loading || serverGenerating" @click="restoreQuestion(item)">{{ t('chat.restoreQuestion') }}</el-button>
        </div>
        <details v-if="item.role === 'assistant' && hasChatResponseMetadata(item)" class="message-details"><summary>{{ t('chat.responseDetails') }}<el-icon class="disclosure-icon"><ArrowDown /></el-icon></summary>
        <div v-if="item.role === 'assistant' && hasChatResponseMetadata(item)" class="message-metadata">
          <span>{{ t('chat.answerModel') }}: {{ item.model || t('chat.unknownModel') }}</span>
          <span>{{ t('chat.inputTokens') }}: {{ item.inputTokens ?? '-' }}</span>
          <span>{{ t('chat.outputTokens') }}: {{ item.outputTokens ?? '-' }}</span>
          <span>{{ t('chat.totalTokens') }}: {{ item.totalTokens ?? '-' }}</span>
          <el-link
            v-if="item.traceId && auth.hasPermission('operations:task:view')"
            type="primary"
            @click="openTraceLogs(item.traceId)"
          >{{ t('chat.traceId') }}: {{ item.traceId }}</el-link>
          <span v-else-if="item.traceId">{{ t('chat.traceId') }}: {{ item.traceId }}</span>
        </div>
        </details>
        <div v-if="item.media?.length" class="message-media">
          <template v-for="media in item.media" :key="media.value">
            <el-image v-if="media.kind === 'image'" :src="media.value" :preview-src-list="item.media.filter(value => value.kind === 'image').map(value => value.value)" @error="useMediaFallback($event, media)" fit="contain" preview-teleported />
            <video v-else controls preload="metadata" :src="media.value" @error="useMediaFallback($event, media)" />
          </template>
        </div>
      </div>
      <div v-if="!messages.length" class="chat-empty"><div class="chat-empty-icon"><el-icon><ChatLineRound /></el-icon></div><h3>{{ t('chat.empty') }}</h3><p>{{ t('chat.emptyHint') }}</p><div class="chat-suggestions"><button v-for="key in ['explain', 'write', 'plan']" :key="key" :disabled="loading || !!prompt.trim()" @click="useSuggestion(t('chat.suggestions.' + key + 'Prompt'))"><span>{{ t('chat.suggestions.' + key) }}</span><el-icon><Right /></el-icon></button></div></div>
        </div>
        <div class="chat-scroll-actions"><el-button v-if="!followLatest && messages.length" round size="small" :icon="Bottom" @click="scrollToLatest">{{ t('chat.backToLatest') }}</el-button></div>
        <div class="chat-composer" :class="{ 'is-busy': generating }">
        <div v-if="pendingImages.length" class="pending-images">
      <div v-for="image in pendingImages" :key="image.name + image.dataUrl" class="pending-image">
        <img :src="image.dataUrl" :alt="image.name" />
        <el-button circle size="small" type="danger" :aria-label="t('chat.removeImage')" @click="removeImage(image)">×</el-button>
      </div>
        </div>
        <el-input ref="questionInput" v-model="prompt" class="chat-question-input" type="textarea" :autosize="{ minRows: 2, maxRows: 6 }" resize="none" :aria-label="t('chat.placeholder')" :placeholder="t('chat.placeholder')" @keydown.meta.enter.prevent="sendFromKeyboard" @keydown.ctrl.enter.prevent="sendFromKeyboard" />
        <div class="chat-actions">
          <div class="chat-action-buttons">
            <input ref="imageInput" type="file" accept="image/png,image/jpeg,image/webp" multiple hidden @change="onImageSelected" />
            <el-button text :icon="Picture" :disabled="modelType !== 'vision_model' || pendingImages.length >= MAX_IMAGES" :title="modelType === 'vision_model' ? t('chat.imageUploadHint') : t('chat.imageVisionOnly')" @click="openImagePicker">{{ t('chat.uploadImage') }}</el-button>
            <span class="composer-shortcut">{{ t('chat.shortcutHint') }}</span><el-button v-if="generating" class="stop-button" :icon="VideoPause" @click="stopGeneration">{{ t('chat.stopGeneration') }}</el-button><el-button v-else type="primary" :icon="Top" :disabled="!canSend" :loading="loading" @click="send">{{ t('chat.send') }}</el-button>
          </div>
        </div><div class="composer-status" aria-live="polite">{{ optionsLoading ? t('chat.loadingOptions') : serverGenerating && !generating ? t('chat.waitingForSync') : generating ? t('chat.draftHint') : t('chat.composerHint') }}</div>
        </div>
      </el-tab-pane>
      <el-tab-pane :label="t('chat.promptTab')" name="prompt">
        <div class="prompt-settings">
          <div class="prompt-settings-head">
            <div>
              <h3>{{ t('chat.promptTitle') }}</h3>
              <p>{{ t('chat.promptDescription') }}</p>
            </div>
            <el-button plain @click="clearPrompt">{{ t('chat.clearPrompt') }}</el-button>
          </div>
          <input ref="promptFileInput" type="file" accept=".txt,.md,text/plain,text/markdown" hidden @change="onPromptFileSelected" />
          <el-button @click="openPromptFilePicker">{{ t('chat.choosePromptFile') }}</el-button>
          <span v-if="promptFileName" class="prompt-file-name">{{ promptFileName }}</span>
          <el-input v-model="systemPrompt" class="prompt-input" type="textarea" :rows="22" resize="none" :placeholder="t('chat.promptPlaceholder')" />
          <p class="prompt-hint">{{ t('chat.promptHint') }}</p>
        </div>
      </el-tab-pane>
    </el-tabs>
    </div>
    <!-- 抽屉挂载到页面外层，避免配置展开挤压消息区；配置值由页面统一保留。 -->
    <el-drawer v-model="settingsOpen" class="chat-settings-drawer" :title="t('chat.settings')" size="600px" append-to-body>
      <div class="model-config">
        <el-form class="model-settings-form" :inline="true" size="small" :disabled="loading">
          <h3 class="model-section-title">{{ t('chat.modelSelection') }}</h3>
          <div class="model-basic-fields" :class="mode === 'single' ? 'single-model' : 'multi-model'">
            <el-form-item :label="t('chat.modelType')">
              <el-radio-group v-model="modelType" @change="onModelTypeChange"><el-radio-button v-for="type in modelTypes" :key="type.value" :value="type.value">{{ localizeModelType(type.value, modelTypes, t) }}</el-radio-button></el-radio-group>
            </el-form-item>
            <div class="mode-field">
              <span class="mode-field-label">{{ t('chat.mode') }}</span>
              <div class="mode-switch" role="radiogroup" :aria-label="t('chat.mode')">
                <button type="button" class="mode-option" :class="{ 'is-active': mode === 'multi' }" role="radio" :aria-checked="mode === 'multi'" :disabled="loading" @click="setMode('multi')">
                  <span class="mode-option-title">{{ t('chat.multiModel') }}</span>
                  <span class="mode-option-description">{{ t('chat.multiModelDescription') }}</span>
                </button>
                <button type="button" class="mode-option" :class="{ 'is-active': mode === 'single' }" role="radio" :aria-checked="mode === 'single'" :disabled="loading" @click="setMode('single')">
                  <span class="mode-option-title">{{ t('chat.singleModel') }}</span>
                  <span class="mode-option-description">{{ t('chat.singleModelDescription') }}</span>
                </button>
              </div>
            </div>
            <el-form-item v-if="mode === 'single'" :label="t('chat.provider')">
              <el-select v-model="providerId" :placeholder="t('chat.selectProvider')" filterable @change="onProviderChange">
                <el-option v-for="p in filteredProviders" :key="p.id" :value="p.id" :label="p.name + ' (' + p.code + ')'" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="mode === 'single' && providerId" :label="t('chat.model')">
              <el-select v-model="modelId" :placeholder="t('chat.selectModel')" filterable>
                <el-option v-for="m in currentModels" :key="m.id" :value="m.id" :label="m.name + ' (' + m.modelName + ')'" />
              </el-select>
            </el-form-item>
            <el-form-item v-if="mode === 'multi'" :label="t('chat.modelPool')">
              <el-select v-model="featureCode" :placeholder="t('chat.defaultPool')" clearable filterable>
                <el-option v-if="defaultRouteSupportsType" value="" :label="t('chat.defaultPool')" />
                <el-option v-for="route in filteredRoutes" :key="route.id" :value="route.featureCode" :label="route.name + ' (' + route.featureCode + ')'" />
              </el-select>
            </el-form-item>
            <div v-if="mode === 'single' && !providerId" class="model-selection-hint">
              <el-icon><Setting /></el-icon><span>{{ t('chat.selectProviderFirst') }}</span>
            </div>
          </div>
          <div class="model-capability-fields">
            <h3 class="model-section-title">{{ t('chat.thinkingSettings') }}</h3>
            <div class="model-capability-grid">
              <el-form-item class="thinking-field" :label="t('chat.enableThinking')">
                <div class="thinking-control">
                  <el-switch v-model="enableThinking" />
                  <span :class="{ 'is-enabled': enableThinking }">{{ enableThinking ? t('common.enabled') : t('common.disabled') }}</span>
                </div>
              </el-form-item>
              <el-form-item v-if="enableThinking" :label="t('chat.thinkingLevel')">
                <el-select v-model="thinkingLevel">
                  <el-option value="LOW" :label="t('chat.thinkingLow')" />
                  <el-option value="MEDIUM" :label="t('chat.thinkingMedium')" />
                  <el-option value="HIGH" :label="t('chat.thinkingHigh')" />
                  <el-option value="EXTRA_HIGH" :label="t('chat.thinkingExtraHigh')" />
                  <el-option value="MAX" :label="t('chat.thinkingMax')" />
                  <el-option value="ULTRA" :label="t('chat.thinkingUltra')" />
                </el-select>
              </el-form-item>
            </div>
          </div>
        </el-form>
      </div>
    </el-drawer>
  </div>
</template>

<script setup>
import { ref, computed, watch, nextTick, onMounted, onBeforeUnmount } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { ArrowDown, Bottom, ChatLineRound, CopyDocument, Delete, EditPen, Picture, Plus, Refresh, Right, Setting, Top, VideoPause } from '@element-plus/icons-vue'
import http, { showHttpError, chatStreamHeaders } from '../api/http'
import { useI18n } from 'vue-i18n'
import { useRouter, useRoute } from 'vue-router'
import { hasChatResponseMetadata } from '../utils/chatResponse'
import { readChatStream, restoreChatMessage } from '../utils/chatStream'
import { localizeModelType } from '../utils/localization'
import { isPromptFile, readPromptFile } from '../utils/prompt'
import { useAuthStore } from '../stores/auth'

const { t } = useI18n()
const router = useRouter()
const route = useRoute()
const conversationId = ref(null)
const conversations = ref([])
const historyPage = ref(0)
const historyTotal = ref(0)
// 窄屏初次进入时收起历史，后续展开状态交由原生 details 保留。
const historyInitiallyOpen = window.matchMedia('(min-width: 769px)').matches
let streamController = null
let unmounted = false
let syncTimer = null
let historyRequest = 0
let stopped = false
const generating = ref(false)
const serverGenerating = ref(false)
const optionsReady = ref(false)
const optionsLoading = ref(false)
const historyLoading = ref(false)
const pageError = ref('')
const historyError = ref('')
const messagesElement = ref(null)
const questionInput = ref(null)
const followLatest = ref(true)
const auth = useAuthStore()
const prompt = ref('')
const systemPrompt = ref('')
const promptFileName = ref('')
const promptFileInput = ref(null)
const activeTab = ref('conversation')
const settingsOpen = ref(false)
const messages = ref([])
const pendingImages = ref([])
const imageInput = ref(null)
const loading = ref(false)
const MAX_IMAGES = 4
const MAX_IMAGE_SIZE = 30 * 1024 * 1024
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp', 'image/bmp', 'image/tiff', 'image/gif', 'image/heic', 'image/heif'])
const MAX_PROMPT_FILE_SIZE = 1024 * 1024

// 模型配置
const mode = ref('multi') // multi=模型池(能力路由) / single=供应商+模型直连
const featureCode = ref('')
const modelType = ref('text_model')
const modelTypes = ref([])
const enableThinking = ref(false)
const thinkingLevel = ref('MEDIUM')
const routes = ref([])
const providers = ref([])
const providerId = ref(null)
const modelId = ref(null)

// 根据动态类型目录过滤模型池、供应商和模型
const matchesType = model => Array.isArray(model?.supportedModelTypes) && model.supportedModelTypes.includes(modelType.value)
const defaultRouteSupportsType = computed(() => routes.value.some(route => route.featureCode === 'DEFAULT' && route.supportedModelTypes?.includes(modelType.value)))
const filteredRoutes = computed(() => routes.value.filter(route => route.featureCode !== 'DEFAULT' && route.supportedModelTypes?.includes(modelType.value)))
const filteredProviders = computed(() => providers.value.filter(provider => provider.models.some(matchesType)))
const currentModels = computed(() => (providers.value.find(p => p.id === providerId.value)?.models || []).filter(matchesType))
const canSend = computed(() => optionsReady.value && !loading.value && !serverGenerating.value && !!prompt.value.trim()
  && (mode.value === 'single' ? !!modelId.value : featureCode.value
    ? filteredRoutes.value.some(route => route.featureCode === featureCode.value) : defaultRouteSupportsType.value))

/** 按原文复制回答，剪贴板不可用或权限被拒绝时给出明确反馈。 */
async function copyAnswer(message) {
  try {
    await navigator.clipboard.writeText(message.content)
    ElMessage.success(t('chat.answerCopied'))
  } catch { ElMessage.error(t('chat.answerCopyFailed')) }
}

/** 恢复原问题及附件但不自动重发，覆盖现有草稿前确认并防止异步确认覆盖新输入。 */
async function restoreQuestion(message) {
  if (loading.value || serverGenerating.value || message.role !== 'user') return
  if (message.images?.length && modelType.value !== 'vision_model') {
    ElMessage.warning(t('chat.imageVisionOnly'))
    return
  }
  const draft = prompt.value
  const images = pendingImages.value
  const id = conversationId.value
  if (draft || images.length) {
    try { await ElMessageBox.confirm(t('chat.replaceDraftConfirm'), t('chat.restoreQuestion'), { type: 'warning' }) }
    catch { return }
  }
  if (unmounted || loading.value || serverGenerating.value || conversationId.value !== id || prompt.value !== draft || pendingImages.value !== images) return
  prompt.value = message.content
  pendingImages.value = (message.images || []).map(image => ({ ...image }))
  activeTab.value = 'conversation'
  await nextTick()
  questionInput.value?.focus()
}

/** 仅在用户位于底部附近时跟随流式内容，保留向上阅读的位置。 */
function onMessagesScroll() {
  const element = messagesElement.value
  if (element) followLatest.value = element.scrollHeight - element.clientHeight - element.scrollTop < 80
}

/** 等待消息布局完成后回到最新位置，不改变键盘焦点。 */
async function scrollToLatest() {
  followLatest.value = true
  await nextTick()
  if (messagesElement.value) messagesElement.value.scrollTop = messagesElement.value.scrollHeight
}

watch(() => messages.value.map(item => [item.content, item.status]), async () => {
  if (followLatest.value) await scrollToLatest()
}, { flush: 'post' })

/** 示例只填入空草稿，用户确认内容后自行发送。 */
async function useSuggestion(content) {
  if (loading.value || prompt.value.trim()) return
  prompt.value = content
  await nextTick()
  questionInput.value?.focus()
}

/** 中文输入法确认候选时不触发快捷发送。 */
function sendFromKeyboard(event) {
  if (!event.isComposing) return send()
}

/** 用户主动中断读取，已接收内容保留，服务端终态通过历史同步。 */
function stopGeneration() {
  if (!generating.value) return
  stopped = true
  streamController?.abort()
}

/** 切换供应商时清空已选模型。 */
function onProviderChange() {
  modelId.value = null
}
/** 切换模型来源时只保留当前模式需要的配置，避免隐藏字段继续影响请求。 */
function setMode(nextMode) {
  if (loading.value || mode.value === nextMode) return
  mode.value = nextMode
  if (nextMode === 'multi') {
    providerId.value = null
    modelId.value = null
  } else {
    featureCode.value = ''
  }
}
/** 切换类型后移除不兼容的模型、路由和待发送图片。 */
function onModelTypeChange() {
  if (featureCode.value && !filteredRoutes.value.some(route => route.featureCode === featureCode.value)) featureCode.value = ''
  if (!filteredProviders.value.some(provider => provider.id === providerId.value)) providerId.value = null
  if (!currentModels.value.some(model => model.id === modelId.value)) modelId.value = null
  if (modelType.value !== 'vision_model') pendingImages.value = []
}

/** 打开系统文件选择器，仅允许视觉模型选择图片。 */
function openImagePicker() {
  if (modelType.value === 'vision_model') imageInput.value?.click()
}

/** 读取并校验用户选择的图片，转换为可直接发送的 Data URL。 */
async function onImageSelected(event) {
  const files = Array.from(event.target.files || [])
  event.target.value = ''
  if (modelType.value !== 'vision_model') return
  if (pendingImages.value.length + files.length > MAX_IMAGES) {
    ElMessage.warning(t('chat.imageLimit', { count: MAX_IMAGES }))
    return
  }
  for (const file of files) {
    if (!IMAGE_TYPES.has(file.type)) {
      ElMessage.warning(t('chat.imageType'))
      continue
    }
    if (file.size > MAX_IMAGE_SIZE) {
      ElMessage.warning(t('chat.imageSize'))
      continue
    }
    const dataUrl = await readImage(file)
    pendingImages.value.push({ name: file.name, dataUrl })
  }
}

/** 将图片文件读取为 Base64 Data URL。 */
function readImage(file) {
  return new Promise((resolve, reject) => {
    const reader = new FileReader()
    reader.onload = () => resolve(String(reader.result))
    reader.onerror = () => {
      ElMessage.error(t('chat.imageReadFailed'))
      reject(reader.error)
    }
    reader.readAsDataURL(file)
  })
}

/** 从待发送列表移除图片。 */
function removeImage(image) {
  pendingImages.value = pendingImages.value.filter(item => item !== image)
}

/** 打开系统文件选择器，读取 Markdown 或纯文本提示词文件。 */
function openPromptFilePicker() {
  promptFileInput.value?.click()
}

/** 读取提示词文件并校验扩展名、大小和文本内容。 */
async function onPromptFileSelected(event) {
  const file = event.target.files?.[0]
  event.target.value = ''
  if (!file) return
  if (!isPromptFile(file)) {
    ElMessage.warning(t('chat.promptFileType'))
    return
  }
  if (file.size > MAX_PROMPT_FILE_SIZE) {
    ElMessage.warning(t('chat.promptFileSize'))
    return
  }
  try {
    systemPrompt.value = await readPromptFile(file)
    promptFileName.value = file.name
  } catch (error) {
    console.error('Failed to read prompt file:', error)
    ElMessage.error(t('chat.promptFileReadFailed'))
  }
}

/** 清空当前页面中的提示词和已选择的文件名。 */
function clearPrompt() {
  systemPrompt.value = ''
  promptFileName.value = ''
}

/** 跳转任务调度页并按当前 Trace ID 自动打开链路日志。 */
function openTraceLogs(traceId) {
  router.push({ path: '/tasks', query: { traceId, openLogs: 'true' } })
}

/** 将页面消息转换为后端兼容的纯文本或多模态消息。 */
function toApiMessage(message) {
  if (!message.images?.length) return { role: message.role, content: message.content }
  return {
    role: message.role,
    content: [
      ...(message.content ? [{ type: 'text', text: message.content }] : []),
      ...message.images.map(image => ({ type: 'image_url', image_url: { url: image.dataUrl } }))
    ]
  }
}

/** 加载必要模型目录，工具权限失败不阻断普通对话。 */
async function loadOptions() {
  optionsLoading.value = true
  pageError.value = ''
  try {
    const [routesRes, providersRes, typesRes] = await Promise.all([
      http.get('/ai/chat/routes'), http.get('/ai/chat/providers'), http.get('/ai/chat/model-types')
    ])
    if (unmounted) return
    routes.value = routesRes.data
    providers.value = providersRes.data
    modelTypes.value = typesRes.data
    if (!modelTypes.value.some(type => type.value === modelType.value)) modelType.value = modelTypes.value[0]?.value || 'text_model'
    optionsReady.value = true
    const selected = Number(route.query.conversation)
    if (!conversationId.value && Number.isSafeInteger(selected) && selected > 0) await selectConversation(selected)
  } catch (error) {
    if (!unmounted) pageError.value = t('chat.optionsFailed')
  } finally { optionsLoading.value = false }
}

onMounted(async () => { await Promise.all([loadOptions(), refreshHistory()]) })

/** 分页读取摘要，不缓存其他用户的会话正文。 */
async function refreshHistory() {
  const request = ++historyRequest
  historyLoading.value = true
  historyError.value = ''
  try {
    const response = await http.get('/ai/conversations', { params: { page: historyPage.value } })
    if (unmounted || request !== historyRequest) return
    conversations.value = response.data.items
    historyTotal.value = response.data.total
  } catch (error) {
    if (!unmounted && request === historyRequest) historyError.value = t('chat.historyFailed')
  } finally { if (request === historyRequest) historyLoading.value = false }
}

/** 切换历史页并读取数据。 */
async function changeHistoryPage(page) {
  if (loading.value) return
  historyPage.value = page - 1
  await refreshHistory()
}

/** 新建持久会话并保留当前模型设置。 */
async function newConversation() {
  if (loading.value) return
  loading.value = true
  try {
  const response = await http.post('/ai/conversations')
  conversationId.value = response.data.id
  clearTimeout(syncTimer)
  serverGenerating.value = false
  pageError.value = ''
  activeTab.value = 'conversation'
  messages.value = []
  prompt.value = ''
  pendingImages.value = []
  historyPage.value = 0
  await router.replace({ query: { ...route.query, conversation: String(conversationId.value) } })
  await refreshHistory()
  } catch (error) { pageError.value = t('chat.newConversationFailed') }
  finally { loading.value = false }
}

/** 恢复会话历史和模型配置，刷新页面后仍可继续对话。 */
async function selectConversation(id) {
  if (loading.value) return
  loading.value = true
  try {
    const response = await http.get(`/ai/conversations/${id}`)
    if (unmounted) return
    clearTimeout(syncTimer)
    conversationId.value = id
    serverGenerating.value = Boolean(response.data.conversation?.generating)
    pageError.value = ''
    activeTab.value = 'conversation'
    messages.value = response.data.messages.map(restoreChatMessage)
    const settings = response.data.settings
    modelType.value = settings.modelType || 'text_model'
    featureCode.value = settings.featureCode || ''
    modelId.value = settings.modelId || null
    mode.value = modelId.value ? 'single' : 'multi'
    providerId.value = providers.value.find(provider => provider.models.some(model => model.id === modelId.value))?.id || null
    enableThinking.value = Boolean(settings.enableThinking)
    thinkingLevel.value = settings.thinkingLevel || 'MEDIUM'
    systemPrompt.value = settings.systemPrompt || ''
    promptFileName.value = ''
    prompt.value = ''
    pendingImages.value = []
    await router.replace({ query: { ...route.query, conversation: String(id) } })
    await scrollToLatest()
    if (serverGenerating.value) scheduleSync(id)
  } catch (error) { if (!unmounted) pageError.value = t('chat.conversationFailed') }
  finally { loading.value = false }
}

/** 用户确认后删除会话及历史消息。 */
async function deleteConversation(id) {
  if (loading.value) return
  try { await ElMessageBox.confirm(t('chat.deleteConversationConfirm'), t('common.delete'), { type: 'warning' }) }
  catch { return }
  loading.value = true
  try {
  await http.delete(`/ai/conversations/${id}`)
  if (conversationId.value === id) {
    clearTimeout(syncTimer)
    conversationId.value = null
    serverGenerating.value = false
    messages.value = []
    await router.replace({ query: { ...route.query, conversation: undefined } })
  }
  if (conversations.value.length === 1 && historyPage.value > 0) historyPage.value--
  await refreshHistory()
  } catch (error) { pageError.value = t('chat.deleteFailed') }
  finally { loading.value = false }
}

/** 离开页面取消网络读取，上游随代理断连释放生成资源。 */
onBeforeUnmount(() => {
  unmounted = true
  clearTimeout(syncTimer)
  streamController?.abort()
})

/** 停止或恢复生成中的历史后，等待服务端结束保存，避免马上重发产生冲突。 */
function scheduleSync(id) {
  clearTimeout(syncTimer)
  syncTimer = setTimeout(() => syncGeneration(id), 1500)
}

/** 同步服务端终态，不清空输入、不覆盖本地已接收但尚未落库的部分文本。 */
async function syncGeneration(id) {
  if (unmounted || id !== conversationId.value) return
  try {
    const response = await http.get('/ai/conversations/' + id)
    if (unmounted || id !== conversationId.value) return
    serverGenerating.value = Boolean(response.data.conversation?.generating)
    if (serverGenerating.value) { scheduleSync(id); return }
    // 保留停止后已显示的内容；缺失或更长的服务端内容才更新当前消息。
    for (const message of response.data.messages.map(restoreChatMessage)) {
      const existing = messages.value.find(item => item.id === message.id)
      if (existing && message.content.length >= existing.content.length) Object.assign(existing, message)
    }
    await refreshHistory()
  } catch (error) {
    if (!unmounted && id === conversationId.value) pageError.value = t('chat.syncFailed')
  }
}

/** 只发送新问题，立即分离当前草稿，避免首个流事件清空下一条输入。 */
async function send() {
  if (!canSend.value) return
  const content = prompt.value.trim()
  const draft = prompt.value
  const images = [...pendingImages.value]
  if (images.length && modelType.value !== 'vision_model') {
    ElMessage.warning(t('chat.imageVisionOnly'))
    return
  }
  const settings = { modelType: modelType.value, featureCode: featureCode.value.trim() || null,
    modelId: mode.value === 'single' ? modelId.value : null,
    enableThinking: enableThinking.value, thinkingLevel: thinkingLevel.value, systemPrompt: systemPrompt.value }
  const userMessage = { role: 'user', content, images }
  let assistant = null
  let started = false
  let turnConversationId = null
  const previousLength = messages.value.length
  loading.value = true
  pageError.value = ''
  prompt.value = ''
  pendingImages.value = []
  stopped = false
  try {
    if (!conversationId.value) {
      const created = await http.post('/ai/conversations')
      if (unmounted) return
      conversationId.value = created.data.id
      historyPage.value = 0
      await router.replace({ query: { ...route.query, conversation: String(conversationId.value) } })
    }
    if (unmounted) return
    turnConversationId = conversationId.value
    messages.value.push(userMessage, { role: 'assistant', content: '', status: 'GENERATING' })
    assistant = messages.value[messages.value.length - 1]
    streamController = new AbortController()
    generating.value = true
    await scrollToLatest()
    const response = await fetch('/api/ai/conversations/' + turnConversationId + '/messages/stream', {
      method: 'POST', credentials: 'same-origin', headers: chatStreamHeaders(), signal: streamController.signal,
      body: JSON.stringify({ requestId: crypto.randomUUID(), content: toApiMessage(userMessage).content, settings })
    })
    await readChatStream(response, event => {
      if (event.type === 'start') { started = true; assistant.id = event.messageId; assistant.traceId = event.traceId }
      // 路由在首个增量前已确定优先模型，先展示模型；done 事件会覆盖故障切换后的实际模型。
      if (event.type === 'model') Object.assign(assistant, { model: event.model, providerCode: event.providerCode })
      if (event.type === 'delta') assistant.content += event.content
      if (event.type === 'done') Object.assign(assistant, event, { status: 'COMPLETED' })
    })
  } catch (error) {
    if (unmounted) return
    if (started) {
      assistant.status = 'INTERRUPTED'
    } else {
      messages.value.splice(previousLength)
      if (!prompt.value) prompt.value = draft
      if (!pendingImages.value.length && modelType.value === 'vision_model') pendingImages.value = images
    }
    if (error.response?.status === 401) await router.push({ path: '/login', query: { redirect: route.fullPath } })
    else if (!stopped) {
      pageError.value = error.streamMessage || t(started ? 'chat.interruptedHint' : 'chat.sendFailed')
      showHttpError(error, 'chat.callFailed')
    }
    if (turnConversationId && (started || stopped || !error.response?.status || error.response?.status === 409)) {
      serverGenerating.value = true
      scheduleSync(turnConversationId)
    }
  } finally {
    loading.value = false
    generating.value = false
    streamController = null
    if (!unmounted && turnConversationId) await refreshHistory()
  }
}

/** 聊天媒体原地址失效时切换到 OSS 备份地址。 */
function useMediaFallback(event, media) {
  const target = event?.target
  if (!target || target.dataset.ossFallback === '1' || !media?.ossUrl || target.src === media.ossUrl) return
  target.dataset.ossFallback = '1'
  target.src = media.ossUrl
}

</script>

<style scoped>
.chat-workspace { display: grid; flex: 1; min-height: 0; overflow: hidden; grid-template-columns: 224px minmax(0, 1fr); gap: 28px; }
.chat-heading { align-items: center; gap: 16px; }
.chat-heading h2 { margin-bottom: 4px; }
.chat-heading p { margin: 0; color: var(--app-muted); font-size: 13px; }
.chat-session-label { max-width: min(36%, 320px); overflow: hidden; color: var(--app-muted); font-size: 12px; text-overflow: ellipsis; white-space: nowrap; }
.chat-history { min-width: 0; min-height: 0; overflow: hidden; display: flex; flex-direction: column; background: var(--app-canvas); border: 1px solid var(--app-border); border-radius: 12px; padding: 12px; }
.chat-history::details-content { min-height: 0; display: flex; flex: 1; flex-direction: column; overflow: hidden; }
.history-heading { display: flex; align-items: center; gap: 8px; padding: 4px 4px 12px; color: var(--app-muted); font-size: 12px; font-weight: 600; cursor: pointer; }
.history-heading span { font-variant-numeric: tabular-nums; }
summary { list-style: none; }
summary::-webkit-details-marker { display: none; }
.disclosure-icon { margin-left: auto; flex-shrink: 0; transition: transform .15s; }
details[open] > summary > .disclosure-icon { transform: rotate(180deg); }
summary:focus-visible, .history-title:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; border-radius: 6px; }
.history-toolbar { display: flex; align-items: center; gap: 4px; padding-bottom: 12px; }
.history-toolbar > .el-button:first-child { flex: 1; min-width: 0; padding-inline: 8px; }
.history-refresh.el-button { margin-left: 0; padding: 8px; }
.history-list { flex: 1; min-height: 0; overflow-y: auto; scrollbar-width: thin; }
.history-empty, .history-error { color: var(--app-muted); font-size: 12px; text-align: center; margin: 24px 0; }
.history-error { color: var(--el-color-danger); }
.history-item { display: flex; align-items: center; border-radius: 8px; padding: 2px; margin-bottom: 4px; }
.history-item:hover { background: #edf1f7; }
.history-item.selected { background: var(--el-color-primary-light-9); color: var(--app-primary); }
.history-title { display: flex; align-items: center; gap: 8px; flex: 1; min-width: 0; border: 0; background: none; text-align: left; cursor: pointer; padding: 10px 6px; color: inherit; font: inherit; font-size: 13px; }
.history-title span { overflow: hidden; text-overflow: ellipsis; white-space: nowrap; }
.history-title .el-icon { flex-shrink: 0; color: var(--app-muted); }
.history-delete.el-button { margin: 0; padding: 6px; color: var(--app-muted); }
.history-delete.el-button:hover { color: var(--el-color-danger); }
.chat-history :deep(.el-pagination) { padding-top: 8px; gap: 2px; flex-wrap: nowrap; }
.chat-history :deep(.el-pager li), .chat-history :deep(.el-pagination button) { min-width: 24px; }
.chat-tabs { min-width: 0; }
.chat-error { display: flex; align-items: center; justify-content: space-between; gap: 12px; margin: 8px 12px 0; padding: 9px 12px; border: 1px solid var(--el-color-danger-light-7); border-radius: 9px; color: var(--el-color-danger); background: var(--el-color-danger-light-9); font-size: 12px; }
.model-toolbar { display: flex; flex: 0 0 auto; align-items: center; gap: 12px; min-width: 0; padding: 6px 4px 10px; border-bottom: 1px solid var(--app-border); }
.model-toolbar > .el-button { flex-shrink: 0; }
.model-summary { display: flex; flex: 1; align-items: center; gap: 10px; min-width: 0; font-size: 12px; }
.model-summary-type { flex-shrink: 0; color: var(--app-muted); }
.model-summary-value { min-width: 0; overflow: hidden; text-overflow: ellipsis; white-space: nowrap; color: var(--app-text); }
.message-media { display: flex; flex-wrap: wrap; gap: 10px; margin-top: 10px; }
.message-media :deep(.el-image), .message-media video { width: min(360px, 100%); max-height: 360px; border: 1px solid var(--app-border); border-radius: 8px; background: #111827; object-fit: contain; }
.model-config { min-width: 0; }

.prompt-settings {
  width: 100%;
  min-width: 0;
  min-height: 0;
  overflow-y: auto;
  padding: 4px 2px 16px;
}

.prompt-settings-head {
  display: flex;
  align-items: flex-start;
  justify-content: space-between;
  gap: 16px;
  margin-bottom: 16px;
}

.prompt-settings-head h3,
.prompt-settings-head p,
.prompt-hint {
  margin: 0;
}

.prompt-settings-head p,
.prompt-hint,
.prompt-file-name {
  color: #909399;
  font-size: 13px;
}

.prompt-file-name {
  margin-left: 12px;
}

.prompt-input {
  margin-top: 16px;
}

.prompt-hint {
  margin-top: 8px;
}

.model-config .model-settings-form {
  display: block;
  margin-bottom: 0;
  padding-bottom: 14px;
}

.model-basic-fields,
.model-capability-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 12px 16px;
}

.mode-field { min-width: 0; padding: 9px 10px; border: 1px solid transparent; border-radius: 8px; transition: border-color .15s ease, background-color .15s ease; }
.mode-field:hover { border-color: var(--app-border); background: var(--app-surface); }
.mode-field-label { display: block; min-height: 22px; color: var(--app-muted); font-size: 12px; line-height: 1.5; }
.mode-switch { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 6px; }
.mode-option { display: flex; min-width: 0; flex-direction: column; align-items: flex-start; gap: 2px; min-height: 56px; padding: 8px 10px; border: 1px solid var(--app-border); border-radius: 8px; color: var(--app-text); background: var(--app-canvas); text-align: left; cursor: pointer; transition: border-color .15s ease, background-color .15s ease, box-shadow .15s ease, transform .15s ease; }
.mode-option:hover:not(:disabled) { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); transform: translateY(-1px); }
.mode-option.is-active { border-color: var(--app-primary); background: var(--el-color-primary-light-9); box-shadow: inset 0 0 0 1px var(--app-primary); }
.mode-option:focus-visible { outline: none; box-shadow: 0 0 0 3px var(--el-color-primary-light-9), 0 0 0 4px var(--el-color-primary-light-5); }
.mode-option.is-active:focus-visible { box-shadow: inset 0 0 0 1px var(--app-primary), 0 0 0 3px var(--el-color-primary-light-9), 0 0 0 4px var(--el-color-primary-light-5); }
.mode-option:disabled { cursor: not-allowed; opacity: .6; }
.mode-option-title { font-size: 12px; font-weight: 600; }
.mode-option-description { max-width: 100%; overflow: hidden; color: var(--app-muted); font-size: 11px; line-height: 1.35; text-overflow: ellipsis; white-space: nowrap; }
.model-selection-hint { grid-column: 1 / -1; display: flex; align-items: center; gap: 7px; align-self: end; min-height: 34px; margin: 0 10px 9px; padding: 7px 10px; border: 1px dashed var(--el-color-primary-light-5); border-radius: 8px; color: var(--app-muted); background: var(--el-color-primary-light-9); font-size: 12px; }
.model-selection-hint .el-icon { flex-shrink: 0; color: var(--app-primary); }

.model-capability-fields { margin-top: 24px; padding-top: 20px; border-top: 1px solid var(--app-border); }
.model-section-title { margin: 0 0 12px; color: var(--app-text); font-size: 14px; font-weight: 600; }

.model-config .el-form-item {
  display: flex;
  flex-direction: column;
  min-width: 0;
  margin: 0;
  padding: 9px 10px;
  border: 1px solid transparent;
  border-radius: 8px;
  transition: border-color .15s ease, background-color .15s ease;
}
.model-config .el-form-item:hover { border-color: var(--app-border); background: var(--app-surface); }
.model-config :deep(.el-form-item__label) { justify-content: flex-start; height: auto; min-height: 22px; padding: 0; font-size: 12px; color: var(--app-muted); line-height: 1.5; }
.model-config :deep(.el-form-item__content) { width: 100%; min-width: 0; }
.model-config :deep(.el-select) { width: 100% !important; }
.model-config :deep(.el-select__wrapper) { min-height: 34px; transition: border-color .15s ease, box-shadow .15s ease, background-color .15s ease; }
.model-config :deep(.el-select__wrapper.is-focused), .model-config :deep(.el-input__wrapper.is-focus) { border-color: var(--el-color-primary-light-5); box-shadow: 0 0 0 3px var(--el-color-primary-light-9) !important; }
.model-config :deep(.el-radio-button__original-radio:focus-visible + .el-radio-button__inner) { outline: none; box-shadow: 0 0 0 3px var(--el-color-primary-light-9), 0 0 0 4px var(--el-color-primary-light-5); }
.model-config :deep(.el-radio-group) { flex-wrap: wrap; gap: 4px 0; }
.model-config :deep(.el-radio-button__inner) { padding: 8px 10px; }
.thinking-control { display: flex; align-items: center; gap: 9px; min-height: 34px; color: var(--app-muted); font-size: 12px; }
.thinking-control span.is-enabled { color: var(--app-primary); font-weight: 600; }
.message-author { font-size: 12px; font-weight: 600; color: var(--app-muted); margin-bottom: 6px; }
.chat-empty { display: flex; min-height: 100%; flex-direction: column; align-items: center; justify-content: center; text-align: center; padding: 24px 12px; }
.chat-empty-icon { display: grid; place-items: center; width: 52px; height: 52px; border-radius: 16px; color: var(--app-primary); background: var(--el-color-primary-light-9); font-size: 26px; }
.chat-empty h3 { font-size: 20px; font-weight: 600; margin: 20px 0 8px; }
.chat-empty p { color: var(--app-muted); font-size: 13px; margin: 0; }
.chat-composer { min-width: 0; border: 1px solid var(--app-border); border-radius: 14px; padding: 12px; background: var(--app-surface); box-shadow: 0 4px 16px rgb(31 53 91 / 4%); }
.chat-composer:focus-within { border-color: var(--el-color-primary-light-5); box-shadow: 0 0 0 3px var(--el-color-primary-light-9); }
.chat-question-input :deep(.el-textarea__inner) { padding: 4px; background: transparent; box-shadow: none; line-height: 1.65; }
.chat-composer .chat-actions { margin-top: 8px; }

.message-metadata {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
  overflow-wrap: anywhere;
}
.message-metadata > * { min-width: 0; }
.message-metadata :deep(.el-link) { font-size: inherit; }
.message-actions { display: flex; flex-wrap: wrap; margin-top: 6px; }
.message.user .message-actions { justify-content: flex-end; }
.message-actions .el-button { margin-left: 0; color: var(--app-muted); }
.message-details { margin-top: 10px; color: var(--app-muted); font-size: 12px; }
.message-details > summary { display: inline-flex; align-items: center; gap: 5px; cursor: pointer; }
.message-details[open] > summary { color: var(--app-primary); }
.message-details .disclosure-icon { margin-left: 2px; }

.message-images,
.pending-images {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-top: 8px;
}

.message-images img,
.pending-image img {
  width: 96px;
  height: 96px;
  object-fit: cover;
  border-radius: 6px;
  border: 1px solid #dcdfe6;
}

.pending-image {
  position: relative;
}

.pending-image .el-button {
  position: absolute;
  top: -8px;
  right: -8px;
}
.pending-generation-inputs { display: flex; flex-wrap: wrap; gap: 8px; margin: 0 0 12px; padding: 6px; max-height: 150px; overflow-y: auto; }
.pending-generation-input { position: relative; }
.pending-generation-text { display: inline-flex; align-items: center; width: 96px; min-height: 64px; padding: 8px; border: 1px solid #dcdfe6; border-radius: 6px; overflow-wrap: anywhere; white-space: normal; }
.pending-generation-input img, .pending-generation-input video { width: 96px; height: 96px; object-fit: cover; border: 1px solid #dcdfe6; border-radius: 6px; background: #111827; }
.pending-generation-input .el-button { position: absolute; top: -8px; right: -8px; }

.chat-action-buttons {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  justify-content: space-between;
  width: 100%;
  gap: 8px;
}
.chat-action-buttons > .el-button + .el-button { margin-left: 0; }
.chat-action-buttons > .el-button:last-child { margin-left: auto; }
.chat-composer .pending-images { margin: 0 0 12px; padding: 6px; max-height: 110px; overflow-y: auto; }
.chat-composer .pending-image img { width: 64px; height: 64px; }
.composer-shortcut { flex: 1; color: var(--app-muted); font-size: 11px; text-align: right; }
.composer-status { min-height: 16px; margin-top: 5px; color: var(--app-muted); font-size: 11px; }
.stop-button { --el-button-bg-color: var(--el-color-danger-light-9); --el-button-border-color: var(--el-color-danger-light-7); --el-button-text-color: var(--el-color-danger); }
.chat-scroll-actions { position: relative; z-index: 2; display: flex; justify-content: center; height: 0; pointer-events: none; }
.chat-scroll-actions .el-button { pointer-events: auto; transform: translateY(-44px); box-shadow: 0 4px 14px rgb(31 53 91 / 14%); }
.chat-suggestions { display: grid; grid-template-columns: repeat(3, minmax(0, 1fr)); gap: 8px; width: min(100%, 680px); margin-top: 24px; }
.chat-suggestions button { display: flex; align-items: center; justify-content: space-between; gap: 8px; min-height: 42px; padding: 10px 12px; border: 1px solid var(--app-border); border-radius: 10px; color: var(--app-text); background: var(--app-surface); text-align: left; cursor: pointer; transition: border-color .15s ease, background-color .15s ease, transform .15s ease; }
.chat-suggestions button:hover:not(:disabled), .chat-suggestions button:focus-visible { border-color: var(--el-color-primary-light-5); background: var(--el-color-primary-light-9); transform: translateY(-1px); }
.chat-suggestions button:focus-visible { outline: 2px solid var(--app-primary); outline-offset: 2px; }
.chat-suggestions button:disabled { cursor: not-allowed; opacity: .55; }
.messages:focus-visible { outline: 2px solid var(--el-color-primary-light-5); outline-offset: -2px; border-radius: 10px; }
@keyframes chat-pulse { 0%, 100% { opacity: .35; transform: scale(.8); } 50% { opacity: 1; transform: scale(1); } }
@media (max-width: 768px) {
  .chat-workspace { grid-template-columns: minmax(0, 1fr); grid-template-rows: auto minmax(0, 1fr); gap: 12px; }
  .chat-history { max-height: 176px; padding: 8px 10px; }
  .history-heading { padding-bottom: 4px; }
  .history-toolbar { padding-bottom: 4px; }
  .history-toolbar > .el-button:first-child { flex: 0 1 auto; }
  .history-list { min-height: 40px; }
  .history-empty { margin: 8px 0; }
  .model-basic-fields.multi-model, .model-basic-fields.single-model, .model-capability-grid { grid-template-columns: minmax(0, 1fr); }
  .model-selection-hint { grid-column: auto; margin-inline: 0; }
  .mode-switch { grid-template-columns: repeat(2, minmax(0, 1fr)); }
  .model-summary { flex-direction: column; align-items: flex-start; gap: 2px; }
  .model-summary-value { max-width: 100%; }
  .model-capability-fields { margin-top: 20px; padding-top: 16px; }
  .chat-composer { padding: 8px; }
  .chat-composer .chat-actions { flex-direction: row; }
  .chat-composer .el-button { width: auto; }
  .chat-empty h3 { font-size: 16px; }
  .chat-empty { padding: 12px 8px; }
}
</style>
