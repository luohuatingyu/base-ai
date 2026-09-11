import assert from 'node:assert/strict'
import { spawn } from 'node:child_process'
import http from 'node:http'
import test from 'node:test'
import { createHash } from 'node:crypto'

/** 在回环地址获取一个临时端口并立即释放给被测进程。 */
async function availablePort() {
  const server = http.createServer()
  await new Promise((resolve, reject) => server.listen(0, '127.0.0.1', resolve).once('error', reject))
  const port = server.address().port
  await new Promise(resolve => server.close(resolve))
  return port
}

/** 验证生产代理保持 Host、双向终端字节和断连清理。 */
test('frontend proxies terminal WebSocket upgrade and binary output', async () => {
  const backendPort = await availablePort()
  const frontendPort = await availablePort()
  const backend = http.createServer()
  let upstreamSocket
  let resolveInput
  const input = new Promise(resolve => { resolveInput = resolve })
  backend.on('upgrade', (request, socket) => {
    upstreamSocket = socket
    assert.equal(request.headers.host, `127.0.0.1:${frontendPort}`)
    assert.equal(request.url, '/api/servers/terminal/socket')
    const accept = createHash('sha1').update(request.headers['sec-websocket-key'] + '258EAFA5-E914-47DA-95CA-C5AB0DC85B11').digest('base64')
    socket.write(`HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: ${accept}\r\n\r\n`)
    socket.write(Buffer.from([0x82, 3, 0x53, 0x53, 0x48]))
    let buffered = Buffer.alloc(0)
    socket.on('data', chunk => {
      buffered = Buffer.concat([buffered, chunk])
      if (buffered.length < 6) return
      const length = buffered[1] & 127
      if (buffered.length < 6 + length) return
      const payload = Buffer.from(buffered.subarray(6, 6 + length))
      for (let index = 0; index < length; index++) payload[index] ^= buffered[2 + index % 4]
      resolveInput(payload.toString())
    })
  })
  await new Promise(resolve => backend.listen(backendPort, '127.0.0.1', resolve))
  const diagnostics = []
  const child = spawn(process.execPath, ['server.mjs'], {
    env: { ...process.env, PORT: String(frontendPort), BACKEND_URL: `http://127.0.0.1:${backendPort}` },
    stdio: ['ignore', 'ignore', 'pipe']
  })
  child.stderr.on('data', chunk => diagnostics.push(chunk.toString()))
  let socket
  try {
    await waitForHealth(`http://127.0.0.1:${frontendPort}`, child, diagnostics)
    socket = new WebSocket(`ws://127.0.0.1:${frontendPort}/api/servers/terminal/socket`)
    socket.binaryType = 'arraybuffer'
    const output = new Promise((resolve, reject) => {
      socket.onmessage = event => resolve(Buffer.from(event.data).toString())
      socket.onerror = reject
    })
    await new Promise((resolve, reject) => { socket.onopen = resolve; socket.onerror = reject })
    socket.send('pwd\r')
    assert.equal(await output, 'SSH')
    assert.equal(await input, 'pwd\r')
  } finally {
    socket?.close()
    upstreamSocket?.destroy()
    await stop(child)
    await new Promise(resolve => backend.close(resolve))
  }
})

/** 等待 Frontend 健康端点可用，超时后保留子进程诊断信息。 */
async function waitForHealth(origin, child, diagnostics) {
  const deadline = Date.now() + 10_000
  while (Date.now() < deadline) {
    if (child.exitCode !== null) throw new Error(`frontend exited early: ${diagnostics.join('')}`)
    try {
      const response = await fetch(`${origin}/health`)
      if (response.ok) return
    } catch { /* 启动窗口内连接失败属于预期。 */ }
    await new Promise(resolve => setTimeout(resolve, 50))
  }
  throw new Error(`frontend health timeout: ${diagnostics.join('')}`)
}

/** 停止测试子进程，避免 E2E 结束后遗留监听端口。 */
async function stop(child) {
  if (child.exitCode !== null) return
  child.kill('SIGTERM')
  await Promise.race([
    new Promise(resolve => child.once('exit', resolve)),
    new Promise(resolve => setTimeout(resolve, 2_000)),
  ])
  if (child.exitCode === null) child.kill('SIGKILL')
}

test('生产前端服务完整处理运行配置、SPA、API 代理和畸形路径', async () => {
  const backend = http.createServer((request, response) => {
    response.writeHead(200, { 'content-type': 'application/json' })
    response.end(JSON.stringify({ method: request.method, url: request.url, marker: request.headers['x-e2e-marker'] }))
  })
  await new Promise((resolve, reject) => backend.listen(0, '127.0.0.1', resolve).once('error', reject))
  const backendPort = backend.address().port
  const frontendPort = await availablePort()
  const diagnostics = []
  const child = spawn(process.execPath, ['server.mjs'], {
    cwd: new URL('../', import.meta.url),
    env: {
      ...process.env,
      PORT: String(frontendPort),
      BACKEND_URL: `http://127.0.0.1:${backendPort}`,
      APP_PLATFORM_NAME_EN: 'E2E Platform',
    },
    stdio: ['ignore', 'ignore', 'pipe'],
  })
  child.stderr.on('data', chunk => diagnostics.push(chunk.toString()))
  const origin = `http://127.0.0.1:${frontendPort}`
  try {
    await waitForHealth(origin, child, diagnostics)

    const runtime = await fetch(`${origin}/runtime-config.js`)
    assert.equal(runtime.headers.get('cache-control'), 'no-store')
    assert.match(await runtime.text(), /E2E Platform/)

    const spa = await fetch(`${origin}/workflow/canvas`)
    assert.equal(spa.status, 200)
    assert.match(spa.headers.get('content-type'), /^text\/html/)
    assert.match(await spa.text(), /<div id="app"><\/div>/)

    const proxied = await fetch(`${origin}/api/probe?value=1`, { headers: { 'x-e2e-marker': 'forwarded' } })
    assert.deepEqual(await proxied.json(), { method: 'GET', url: '/api/probe?value=1', marker: 'forwarded' })

    const malformed = await fetch(`${origin}/%E0%A4%A`)
    assert.equal(malformed.status, 400)
    const traversal = await fetch(`${origin}/%2e%2e%2fpackage.json`)
    assert.equal(traversal.status, 400)
    assert.equal((await fetch(`${origin}/health`)).status, 200)
  } finally {
    await stop(child)
    await new Promise(resolve => backend.close(resolve))
  }
})
