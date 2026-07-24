<!-- AI 对话页面：提交请求并展示模型响应、Trace 标识和 Token 统计。 -->
<template>
  <div class="panel chat-panel">
    <div class="section-head"><div><h2>{{ t('chat.title') }}</h2><p>{{ t('chat.description') }}</p></div><el-tag type="success">OpenAI Compatible</el-tag></div>

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
        <small>{{ item.role === 'user' ? t('chat.user') : t('chat.assistant') }}</small><div>{{ item.content }}</div>
      </div>
      <el-empty v-if="!messages.length" :description="t('chat.empty')" />
    </div>
    <el-input v-model="prompt" type="textarea" :rows="4" :placeholder="t('chat.placeholder')" @keydown.meta.enter="send" @keydown.ctrl.enter="send" />
    <div class="chat-actions"><span v-if="lastTrace">{{ t('chat.traceId') }}: {{ lastTrace }}</span><el-button type="primary" :loading="loading" @click="send">{{ t('chat.send') }}</el-button></div>
  </div>
</template>

<script setup>
import { ref, computed, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const prompt = ref('')
const messages = ref([])
const loading = ref(false)
const lastTrace = ref('')

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
  if (mode.value === 'single' && !modelId.value) { ElMessage.warning(t('chat.selectModel')); return }
  if (mode.value === 'multi' && !defaultRouteSupportsType.value && !filteredRoutes.value.some(route => route.featureCode === featureCode.value)) { ElMessage.warning(t('chat.selectModelPool')); return }
  messages.value.push({ role: 'user', content })
  prompt.value = ''
  loading.value = true
  try {
    const payload = {
      messages: messages.value,
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
    messages.value.push({ role: 'assistant', content: data.content })
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

.model-config .el-form {
  margin-bottom: 0;
}

.model-config .el-form-item {
  margin-bottom: 0;
  margin-right: 16px;
}
</style>
