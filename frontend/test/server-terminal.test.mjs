import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/ServerTerminal.vue', import.meta.url), 'utf8')

/** 执行真实组件会话逻辑，仅隔离终端渲染和网络边界。 */
function harness(post) {
  const script = source.split('<script setup>')[1].split('</script>')[0].replace(/^import .*$/gm, '')
  const connections = []
  const terminals = []
  class Socket {
    static OPEN = 1
    constructor(url) { this.url = url; this.readyState = 1; this.bufferedAmount = 0; this.sent = []; connections.push(this) }
    send(data) { this.sent.push(JSON.parse(data)) }
    close() { this.closed = true }
  }
  class Terminal {
    constructor() { this.cols = 80; this.rows = 24; this.output = []; terminals.push(this) }
    loadAddon() {}
    open() {}
    onData(callback) { this.input = callback }
    resize(cols, rows) { this.cols = cols; this.rows = rows }
    write(data) { this.output.push(data) }
    focus() { this.focused = true }
    dispose() { this.disposed = true }
  }
  const context = {
    defineProps: () => ({ server: { id: 9 } }), defineEmits: () => () => {},
    useI18n: () => ({ t: key => key }), ref: value => ({ value }), computed: getter => ({ get value() { return getter() } }),
    onBeforeUnmount: () => {}, Terminal, FitAddon: class { fit() {} },
    ResizeObserver: class { observe() {} disconnect() {} }, WebSocket: Socket,
    http: { post }, URL, window: { location: { href: 'https://example.test/servers' } }, ArrayBuffer, Uint8Array
  }
  const state = runInNewContext(`${script}\ncontainer.value = { clientWidth: 800, clientHeight: 500 }; ({ connect, disconnect, resize, connected, connecting, failed })`, context)
  return { state, connections, terminals }
}

test('终端票据仅通过首帧发送，输入和窗口尺寸转发且二进制输出保留', async () => {
  const { state, connections, terminals } = harness(async () => ({ data: { ticket: 'one-time' } }))
  await state.connect()
  const socket = connections[0]
  assert.equal(String(socket.url), 'wss://example.test/api/servers/terminal/socket')
  socket.onopen()
  assert.deepEqual(socket.sent[0], { type: 'connect', ticket: 'one-time' })
  socket.onmessage({ data: '{"type":"ready"}' })
  assert.equal(state.connected.value, true)
  assert.deepEqual(socket.sent[1], { type: 'resize', cols: 80, rows: 24 })
  terminals[0].input('cd /tmp\r\u0003')
  assert.equal(socket.sent[2].data, 'cd /tmp\r\u0003')
  const large = 'a'.repeat(4095) + '😀中文'
  terminals[0].input(large)
  assert.equal(socket.sent.slice(3).map(message => message.data).join(''), large)
  assert.equal(socket.sent[3].data.length, 4095)
  const output = new Uint8Array([228, 184, 173]).buffer
  socket.onmessage({ data: output })
  assert.deepEqual([...terminals[0].output[0]], [228, 184, 173])
  state.disconnect()
  assert.equal(socket.closed, true)
  assert.equal(terminals[0].disposed, true)
})

test('关闭后的异步票据响应不能重新连接', async () => {
  let resolve
  const { state, connections, terminals } = harness(() => new Promise(done => { resolve = done }))
  const pending = state.connect()
  state.disconnect()
  resolve({ data: { ticket: 'expired-view' } })
  await pending
  assert.equal(connections.length, 0)
  assert.equal(terminals[0].disposed, true)
})

test('失败可重连，旧连接消息不能改变新连接状态', async () => {
  const { state, connections } = harness(async () => ({ data: { ticket: 'new-ticket' } }))
  await state.connect()
  connections[0].onclose({ code: 1006 })
  assert.equal(state.failed.value, true)
  await state.connect()
  connections[0].onmessage({ data: '{"type":"ready"}' })
  assert.equal(state.connected.value, false)
  connections[1].onmessage({ data: '{"type":"ready"}' })
  assert.equal(state.connected.value, true)
  connections[1].bufferedAmount = 262145
  const terminalInput = connections[1].sent.length
  state.resize()
  assert.equal(connections[1].closed, true)
  assert.equal(connections[1].sent.length, terminalInput)
})

test('票据接口失败显示错误并允许重试', async () => {
  const { state, connections } = harness(async () => { throw new Error('forbidden') })
  await state.connect()
  assert.equal(state.failed.value, true)
  assert.equal(state.connecting.value, false)
  assert.equal(connections.length, 0)
})
