<!-- AI 对话页面：提交请求并展示模型响应、Trace 标识和 Token 统计。 -->
<template>
  <div class="panel chat-panel">
    <div class="section-head"><div><h2>{{ t('chat.title') }}</h2><p>{{ t('chat.description') }}</p></div><el-tag type="success">OpenAI Compatible</el-tag></div>

    <!-- 模型配置选择器 -->
    <div class="model-config">
      <el-form :inline="true" size="small">
        <el-form-item :label="t('chat.modelPool')">
          <el-select v-model="featureCode" :placeholder="t('chat.defaultPool')" style="width: 180px" clearable filterable>
            <el-option value="" :label="t('chat.defaultPool')" />
            <el-option v-for="route in routes" :key="route.id" :value="route.featureCode" :label="route.name + ' (' + route.featureCode + ')'" />
          </el-select>
        </el-form-item>
        <el-form-item :label="t('chat.modelType')">
          <el-select v-model="modelType" style="width: 130px">
            <el-option value="text_model" :label="t('chat.textModel')" />
            <el-option value="vision_model" :label="t('chat.visionModel')" />
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
import { ref, onMounted } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const prompt = ref('')
const messages = ref([])
const loading = ref(false)
const lastTrace = ref('')

// 模型配置
const featureCode = ref('')
const modelType = ref('text_model')
const enableThinking = ref(false)
const thinkingLevel = ref('MEDIUM')
const routes = ref([])

// 加载路由列表
onMounted(async () => {
  try {
    const { data } = await http.get('/models/routes')
    routes.value = data.filter(r => r.enabled)
  } catch (error) {
    console.error('Failed to load routes:', error)
  }
})

/** 将当前对话发送到受权限保护的模型代理接口。 */
async function send() {
  const content = prompt.value.trim()
  if (!content || loading.value) return
  messages.value.push({ role: 'user', content })
  prompt.value = ''
  loading.value = true
  try {
    const payload = {
      messages: messages.value,
      temperature: 0,
      model_type: modelType.value
    }

    // 添加可选参数
    if (featureCode.value && featureCode.value.trim()) {
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
