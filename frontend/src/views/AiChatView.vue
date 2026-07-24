<!-- AI 对话页面：提交请求并展示模型响应、Trace 标识和 Token 统计。 -->
<template>
  <div class="panel chat-panel">
    <div class="section-head"><div><h2>{{ t('chat.title') }}</h2><p>{{ t('chat.description') }}</p></div><el-tag type="success">OpenAI Compatible</el-tag></div>

    <el-tabs v-model="activeTab" class="chat-tabs">
      <el-tab-pane :label="t('chat.conversationTab')" name="conversation">
        <!-- 模型配置选择器 -->
        <div class="model-config">
      <el-form :inline="true" size="small">
        <el-form-item :label="t('chat.modelType')">
          <el-radio-group v-model="modelType" @change="onModelTypeChange"><el-radio-button v-for="type in modelTypes" :key="type.value" :value="type.value">{{ type.label }}</el-radio-button></el-radio-group>
        </el-form-item>
        <el-form-item :label="t('chat.mode')">
          <el-radio-group v-model="mode">
            <el-radio-button value="multi">{{ t('chat.multiModel') }}</el-radio-button>
            <el-radio-button value="single">{{ t('chat.singleModel') }}</el-radio-button>
          </el-radio-group>
        </el-form-item>
        <el-form-item v-if="mode === 'multi'" :label="t('chat.modelPool')">
          <el-select v-model="featureCode" :placeholder="t('chat.defaultPool')" style="width: 180px" clearable filterable>
            <el-option v-if="defaultRouteSupportsType" value="" :label="t('chat.defaultPool')" />
            <el-option v-for="route in filteredRoutes" :key="route.id" :value="route.featureCode" :label="route.name + ' (' + route.featureCode + ')'" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="mode === 'single'" :label="t('chat.provider')">
          <el-select v-model="providerId" :placeholder="t('chat.selectProvider')" style="width: 180px" filterable @change="onProviderChange">
            <el-option v-for="p in filteredProviders" :key="p.id" :value="p.id" :label="p.name + ' (' + p.code + ')'" />
          </el-select>
        </el-form-item>
        <el-form-item v-if="mode === 'single'" :label="t('chat.model')">
          <el-select v-model="modelId" :placeholder="t('chat.selectModel')" style="width: 180px" filterable>
            <el-option v-for="m in currentModels" :key="m.id" :value="m.id" :label="m.name + ' (' + m.modelName + ')'" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('chat.enableThinking')">
          <el-switch v-model="enableThinking" />
        </el-form-item>
        <el-form-item v-if="enableThinking" :label="t('chat.thinkingLevel')">
          <el-select v-model="thinkingLevel" style="width: 130px">
            <el-option value="LOW" :label="t('chat.thinkingLow')" />
            <el-option value="MEDIUM" :label="t('chat.thinkingMedium')" />
            <el-option value="HIGH" :label="t('chat.thinkingHigh')" />
            <el-option value="EXTRA_HIGH" :label="t('chat.thinkingExtraHigh')" />
            <el-option value="MAX" :label="t('chat.thinkingMax')" />
            <el-option value="ULTRA" :label="t('chat.thinkingUltra')" />
          </el-select>
        </el-form-item>
      </el-form>
        </div>

        <div class="messages">
      <div v-for="(item, index) in messages" :key="index" :class="['message', item.role]">
        <small>{{ item.role === 'user' ? t('chat.user') : t('chat.assistant') }}</small>
        <div>{{ item.content }}</div>
        <div v-if="item.images?.length" class="message-images">
          <img v-for="image in item.images" :key="image.name + image.dataUrl" :src="image.dataUrl" :alt="image.name" />
        </div>
        <div v-if="item.role === 'assistant' && hasChatResponseMetadata(item)" class="message-metadata">
          <span>{{ t('chat.answerModel') }}: {{ item.model || t('chat.unknownModel') }}</span>
          <span>{{ t('chat.inputTokens') }}: {{ item.inputTokens ?? '-' }}</span>
          <span>{{ t('chat.outputTokens') }}: {{ item.outputTokens ?? '-' }}</span>
          <span>{{ t('chat.totalTokens') }}: {{ item.totalTokens ?? '-' }}</span>
          <span v-if="item.traceId">{{ t('chat.traceId') }}: {{ item.traceId }}</span>
        </div>
      </div>
      <el-empty v-if="!messages.length" :description="t('chat.empty')" />
        </div>
        <div v-if="pendingImages.length" class="pending-images">
      <div v-for="image in pendingImages" :key="image.name + image.dataUrl" class="pending-image">
        <img :src="image.dataUrl" :alt="image.name" />
        <el-button circle size="small" type="danger" @click="removeImage(image)">×</el-button>
      </div>
        </div>
        <el-input v-model="prompt" type="textarea" :rows="4" :placeholder="t('chat.placeholder')" @keydown.meta.enter="send" @keydown.ctrl.enter="send" />
        <div class="chat-actions">
          <span v-if="lastTrace">{{ t('chat.traceId') }}: {{ lastTrace }}</span>
          <div class="chat-action-buttons">
            <input ref="imageInput" type="file" accept="image/png,image/jpeg,image/webp" multiple hidden @change="onImageSelected" />
            <el-button :disabled="modelType !== 'vision_model' || pendingImages.length >= MAX_IMAGES" @click="openImagePicker">{{ t('chat.uploadImage') }}</el-button>
            <el-button type="primary" :loading="loading" @click="send">{{ t('chat.send') }}</el-button>
          </div>
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
          <el-input v-model="systemPrompt" class="prompt-input" type="textarea" :rows="16" :placeholder="t('chat.promptPlaceholder')" />
          <p class="prompt-hint">{{ t('chat.promptHint') }}</p>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useI18n } from 'vue-i18n'
import { createAssistantMessage, hasChatResponseMetadata } from '../utils/chatResponse'
import { isPromptFile, readPromptFile, withSystemPrompt } from '../utils/prompt'

const { t } = useI18n()
const prompt = ref('')
const systemPrompt = ref('')
const promptFileName = ref('')
const promptFileInput = ref(null)
const activeTab = ref('conversation')
const messages = ref([])
const pendingImages = ref([])
const imageInput = ref(null)
const loading = ref(false)
const lastTrace = ref('')
const MAX_IMAGES = 4
const MAX_IMAGE_SIZE = 10 * 1024 * 1024
const IMAGE_TYPES = new Set(['image/png', 'image/jpeg', 'image/webp'])
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
const legacyTypes = model => String(model?.modelType || '').split(',').map(value => value.trim().toLowerCase()).flatMap(value => value === 'both' ? ['text_model', 'vision_model'] : [value === 'text' ? 'text_model' : value === 'vision' ? 'vision_model' : value])
const matchesType = model => (Array.isArray(model?.supportedModelTypes) ? model.supportedModelTypes : legacyTypes(model)).includes(modelType.value)
const defaultRouteSupportsType = computed(() => routes.value.some(route => route.featureCode === 'DEFAULT' && route.supportedModelTypes?.includes(modelType.value)))
const filteredRoutes = computed(() => routes.value.filter(route => route.featureCode !== 'DEFAULT' && route.supportedModelTypes?.includes(modelType.value)))
const filteredProviders = computed(() => providers.value.filter(provider => provider.models.some(matchesType)))
const currentModels = computed(() => (providers.value.find(p => p.id === providerId.value)?.models || []).filter(matchesType))

// 切换供应商时清空已选模型
function onProviderChange() {
  modelId.value = null
}
function onModelTypeChange() {
  if (!defaultRouteSupportsType.value && !filteredRoutes.value.some(route => route.featureCode === featureCode.value)) featureCode.value = ''
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

// 加载路由列表和供应商列表
onMounted(async () => {
  try {
    const [routesRes, providersRes, typesRes] = await Promise.all([
      http.get('/ai/chat/routes'),
      http.get('/ai/chat/providers'),
      http.get('/ai/chat/model-types')
    ])
    routes.value = routesRes.data
    providers.value = providersRes.data
    modelTypes.value = typesRes.data
    if (!modelTypes.value.some(type => type.value === modelType.value)) modelType.value = modelTypes.value[0]?.value || 'text_model'
  } catch (error) {
    console.error('Failed to load chat options:', error)
  }
})

/** 将当前对话发送到受权限保护的模型代理接口。 */
async function send() {
  const content = prompt.value.trim()
  if (!content || loading.value) return
  if (pendingImages.value.length && modelType.value !== 'vision_model') {
    ElMessage.warning(t('chat.imageVisionOnly'))
    return
  }
  if (mode.value === 'single' && !modelId.value) { ElMessage.warning(t('chat.selectModel')); return }
  if (mode.value === 'multi' && !defaultRouteSupportsType.value && !filteredRoutes.value.some(route => route.featureCode === featureCode.value)) { ElMessage.warning(t('chat.selectModelPool')); return }
  messages.value.push({ role: 'user', content, images: pendingImages.value })
  prompt.value = ''
  pendingImages.value = []
  loading.value = true
  try {
    const payload = {
      messages: withSystemPrompt(messages.value.map(toApiMessage), systemPrompt.value),
      temperature: 0,
      model_type: modelType.value
    }

    // 单模型模式：直连所选模型；否则走能力路由(模型池)
    if (mode.value === 'single' && modelId.value) {
      payload.modelId = modelId.value
    } else if (featureCode.value && featureCode.value.trim()) {
      payload.featureCode = featureCode.value.trim()
    }

    if (enableThinking.value) {
      payload.enableThinking = true
      payload.thinkingLevel = thinkingLevel.value
    }

    const { data } = await http.post('/ai/chat', payload)
    messages.value.push(createAssistantMessage(data))
    lastTrace.value = data.traceId
  } catch (error) { ElMessage.error(error.response?.data?.message || t('chat.callFailed')) }
  finally { loading.value = false }
}
</script>

<style scoped>
.model-config {
  margin-bottom: 20px;
  padding: 16px;
  background-color: #f5f7fa;
  border-radius: 8px;
  border: 1px solid #e4e7ed;
}

.chat-tabs :deep(.el-tabs__content) {
  overflow: visible;
}

.prompt-settings {
  max-width: 960px;
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

.model-config .el-form {
  margin-bottom: 0;
}

.model-config .el-form-item {
  margin-bottom: 0;
  margin-right: 16px;
}

.message-metadata {
  display: flex;
  flex-wrap: wrap;
  gap: 4px 12px;
  margin-top: 8px;
  color: #909399;
  font-size: 12px;
}

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

.chat-action-buttons {
  display: flex;
  gap: 8px;
}
</style>
