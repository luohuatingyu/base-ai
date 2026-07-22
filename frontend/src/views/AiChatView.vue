<!-- AI 对话页面：提交请求并展示模型响应、Trace 标识和 Token 统计。 -->
<template>
  <div class="panel chat-panel">
    <div class="section-head"><div><h2>{{ t('chat.title') }}</h2><p>{{ t('chat.description') }}</p></div><el-tag type="success">OpenAI Compatible</el-tag></div>
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
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'
import { useI18n } from 'vue-i18n'

const { t } = useI18n()
const prompt = ref('')
const messages = ref([])
const loading = ref(false)
const lastTrace = ref('')

/** 将当前对话发送到受权限保护的模型代理接口。 */
async function send() {
  const content = prompt.value.trim()
  if (!content || loading.value) return
  messages.value.push({ role: 'user', content })
  prompt.value = ''
  loading.value = true
  try {
    const { data } = await http.post('/ai/chat', { messages: messages.value, temperature: 0 })
    messages.value.push({ role: 'assistant', content: data.content })
    lastTrace.value = data.traceId
  } catch (error) { ElMessage.error(error.response?.data?.message || t('chat.callFailed')) }
  finally { loading.value = false }
}
</script>
