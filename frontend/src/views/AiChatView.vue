<template>
  <div class="panel chat-panel">
    <div class="section-head"><div><h2>通用 AI 对话</h2><p>请求经 Java 权限和任务层转发至 Python Worker。</p></div><el-tag type="success">OpenAI Compatible</el-tag></div>
    <div class="messages">
      <div v-for="(item, index) in messages" :key="index" :class="['message', item.role]">
        <small>{{ item.role === 'user' ? '你' : 'AI' }}</small><div>{{ item.content }}</div>
      </div>
      <el-empty v-if="!messages.length" description="输入问题开始对话" />
    </div>
    <el-input v-model="prompt" type="textarea" :rows="4" placeholder="请输入问题，Ctrl/Cmd + Enter 发送" @keydown.meta.enter="send" @keydown.ctrl.enter="send" />
    <div class="chat-actions"><span v-if="lastTrace">Trace ID：{{ lastTrace }}</span><el-button type="primary" :loading="loading" @click="send">发送</el-button></div>
  </div>
</template>

<script setup>
import { ref } from 'vue'
import { ElMessage } from 'element-plus'
import http from '../api/http'

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
  } catch (error) { ElMessage.error(error.response?.data?.message || '模型调用失败') }
  finally { loading.value = false }
}
</script>
