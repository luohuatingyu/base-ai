<template>
  <el-dialog :model-value="true" :title="`${t('servers.terminal')} · ${server.name}`" width="90%"
    :fullscreen="fullscreen" :close-on-click-modal="false" :close-on-press-escape="false"
    destroy-on-close @opened="connect" @close="close">
    <div class="terminal-toolbar">
      <span role="status">{{ t(statusKey) }}</span>
      <div>
        <el-button @click="fullscreen = !fullscreen">{{ t('servers.terminalFullscreen') }}</el-button>
        <el-button v-if="connected || connecting" @click="disconnect">{{ t('servers.terminalDisconnect') }}</el-button>
        <el-button v-else type="primary" @click="connect">{{ t('servers.terminalReconnect') }}</el-button>
      </div>
    </div>
    <div ref="container" class="terminal-screen" :class="{ 'terminal-fullscreen': fullscreen }" />
    <p class="terminal-hint">{{ t('servers.terminalHint') }}</p>
  </el-dialog>
</template>

<script setup>
import { computed, onBeforeUnmount, ref } from 'vue'
import { useI18n } from 'vue-i18n'
import { Terminal } from '@xterm/xterm'
import { FitAddon } from '@xterm/addon-fit'
import '@xterm/xterm/css/xterm.css'
import http from '../api/http'

const props = defineProps({ server: { type: Object, required: true } })
const emit = defineEmits(['close'])
const { t } = useI18n()
const container = ref(null)
const fullscreen = ref(false)
const connected = ref(false)
const connecting = ref(false)
const failed = ref(false)
const statusKey = computed(() => connecting.value ? 'servers.terminalConnecting'
  : connected.value ? 'servers.terminalConnected' : failed.value ? 'servers.terminalFailed' : 'servers.terminalDisconnected')
let terminal
let fit
let observer
let socket
let generation = 0

/** 仅发送到当前已就绪连接，限制浏览器端积压。 */
function send(message) {
  if (!connected.value || socket?.readyState !== WebSocket.OPEN) return
  if (socket.bufferedAmount > 262144) { disconnect(); failed.value = true; return }
  socket.send(JSON.stringify(message))
}

/** 按容器实际尺寸同步 PTY 窗口，保持全屏程序布局。 */
function resize() {
  if (!terminal || !container.value?.clientWidth || !container.value?.clientHeight) return
  fit.fit()
  const cols = Math.max(2, Math.min(500, terminal.cols))
  const rows = Math.max(1, Math.min(200, terminal.rows))
  terminal.resize(cols, rows)
  send({ type: 'resize', cols, rows })
}

/** 初始化真实终端，逐次签发票据；异步旧请求不得复活已关闭窗口。 */
async function connect() {
  if (connecting.value || connected.value) return
  disconnect()
  const current = generation
  connecting.value = true
  failed.value = false
  terminal = new Terminal({ cursorBlink: true, scrollback: 3000, fontSize: 14, theme: { background: '#10151d' } })
  fit = new FitAddon()
  terminal.loadAddon(fit)
  terminal.open(container.value)
  terminal.onData(data => {
    for (let offset = 0; offset < data.length;) {
      let end = Math.min(data.length, offset + 4096)
      if (end < data.length && data.charCodeAt(end - 1) >= 0xd800 && data.charCodeAt(end - 1) <= 0xdbff) end--
      send({ type: 'input', data: data.slice(offset, end) })
      offset = end
    }
  })
  observer = new ResizeObserver(resize)
  observer.observe(container.value)
  resize()
  try {
    const { data } = await http.post(`/servers/${props.server.id}/terminal`, {}, { silentError: true })
    if (generation !== current) return
    const url = new URL('/api/servers/terminal/socket', window.location.href)
    url.protocol = url.protocol === 'https:' ? 'wss:' : 'ws:'
    const connection = new WebSocket(url)
    socket = connection
    connection.binaryType = 'arraybuffer'
    connection.onopen = () => {
      if (generation !== current) { connection.close(); return }
      connection.send(JSON.stringify({ type: 'connect', ticket: data.ticket }))
    }
    connection.onmessage = event => {
      if (generation !== current) return
      if (event.data instanceof ArrayBuffer) { terminal.write(new Uint8Array(event.data)); return }
      try {
        if (JSON.parse(event.data).type === 'ready') {
          connecting.value = false
          connected.value = true
          resize()
          terminal.focus()
        }
      } catch { connection.close(); failed.value = true }
    }
    connection.onclose = event => {
      if (generation !== current) return
      failed.value = connecting.value || event.code !== 1000
      connecting.value = false
      connected.value = false
    }
    connection.onerror = () => { if (generation === current) failed.value = true }
  } catch {
    if (generation !== current) return
    connecting.value = false
    failed.value = true
  }
}

/** 幂等断开连接并释放观察器及终端缓冲，不保留凭据或浏览器存储。 */
function disconnect() {
  generation++
  socket?.close()
  socket = null
  observer?.disconnect()
  observer = null
  terminal?.dispose()
  terminal = null
  connected.value = false
  connecting.value = false
  failed.value = false
}

/** 关闭窗口立即释放远端会话。 */
function close() { disconnect(); emit('close') }
onBeforeUnmount(disconnect)
</script>

<style scoped>
.terminal-toolbar { display: flex; justify-content: space-between; align-items: center; gap: 12px; flex-wrap: wrap; margin-bottom: 12px; }
.terminal-screen { height: 60vh; min-height: 240px; padding: 12px; background: #10151d; border-radius: 6px; overflow: hidden; }
.terminal-fullscreen { height: calc(100vh - 190px); }
.terminal-hint { color: var(--el-text-color-secondary); font-size: 12px; margin-bottom: 0; }
</style>
