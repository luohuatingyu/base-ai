import http from 'http'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), 'dist')
const agentRoot = path.join(path.dirname(fileURLToPath(import.meta.url)), 'agent-dist')
const port = Number(process.env.PORT || 80)
const backend = new URL(process.env.BACKEND_URL || 'http://backend:8080')
const maxRequestBytes = Number(process.env.APP_MAX_REQUEST_BYTES || 67108864)
const configuredRouteHealthCheckIntervalMs = Number(process.env.LLM_ROUTE_HEALTH_CHECK_INTERVAL_MS || 3600000)
const platformConfig = {
  code: process.env.APP_PLATFORM_CODE || 'ai-platform',
  nameEn: process.env.APP_PLATFORM_NAME_EN || 'AI Platform',
  nameZh: process.env.APP_PLATFORM_NAME_ZH || 'AI平台',
  shortName: process.env.APP_PLATFORM_SHORT_NAME || 'AI',
  defaultLocale: process.env.APP_DEFAULT_LOCALE || 'en-US',
  routeHealthCheckEnabled: String(process.env.LLM_ROUTE_HEALTH_CHECK_ENABLED || 'true').toLowerCase() === 'true',
  routeHealthCheckIntervalMs: Number.isFinite(configuredRouteHealthCheckIntervalMs) && configuredRouteHealthCheckIntervalMs > 0
    ? configuredRouteHealthCheckIntervalMs : 3600000
}
const contentTypes = {
  '.html': 'text/html; charset=utf-8', '.js': 'text/javascript; charset=utf-8',
  '.css': 'text/css; charset=utf-8', '.json': 'application/json; charset=utf-8',
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon',
  '.sh': 'text/x-shellscript; charset=utf-8', '.env': 'text/plain; charset=utf-8'
}

const securityHeaders = {
  'content-security-policy': "default-src 'self'; script-src 'self'; style-src 'self' 'unsafe-inline'; img-src 'self' data: https:; font-src 'self' data:; connect-src 'self'; object-src 'none'; base-uri 'self'; frame-ancestors 'none'; form-action 'self'",
  'x-content-type-options': 'nosniff',
  'x-frame-options': 'DENY',
  'referrer-policy': 'strict-origin-when-cross-origin',
  'permissions-policy': 'camera=(), microphone=(), geolocation=(), payment=(), usb=()',
  'cross-origin-opener-policy': 'same-origin'
}

/** 返回请求路径，统一处理查询参数和非法 URL 编码。 */
function requestPath(requestUrl) {
  try { return { pathname: new URL(requestUrl, 'http://localhost').pathname } } catch { return { status: 400 } }
}

/** 发送带有统一安全响应头的本地响应。 */
function sendResponse(response, status, headers, body) {
  response.writeHead(status, { ...securityHeaders, ...headers })
  response.end(body)
}

/** 将同源 API 请求代理到 Java 后端。 */
function proxy(request, response) {
  const forwardedHeaders = {}
  const allowedHeaders = new Set([
    'accept', 'accept-encoding', 'accept-language', 'authorization', 'content-type',
    'content-length', 'cookie', 'if-none-match', 'if-modified-since', 'origin',
    'referer', 'user-agent', 'x-api-key', 'x-csrf-token', 'x-request-id'
  ])
  for (const [name, value] of Object.entries(request.headers)) {
    if (allowedHeaders.has(name.toLowerCase())) forwardedHeaders[name] = value
  }
  forwardedHeaders.host = backend.host
  forwardedHeaders['x-forwarded-host'] = request.headers.host || ''
  forwardedHeaders['x-forwarded-proto'] = request.socket.encrypted ? 'https' : 'http'
  // 只转发当前 TCP 对端地址，避免把浏览器伪造的 X-Forwarded-For 交给后端。
  forwardedHeaders['x-forwarded-for'] = request.socket.remoteAddress || ''
  const declaredLength = Number(request.headers['content-length'] || 0)
  if (Number.isFinite(declaredLength) && declaredLength > maxRequestBytes) {
    return sendResponse(response, 413, { 'content-type': 'application/json; charset=utf-8' }, JSON.stringify({ message: '请求体过大' }))
  }
  const upstream = http.request({
    hostname: backend.hostname,
    port: backend.port || 80,
    path: request.url,
    method: request.method,
    headers: forwardedHeaders
  }, (upstreamResponse) => {
    response.writeHead(upstreamResponse.statusCode || 502, { ...upstreamResponse.headers, ...securityHeaders })
    upstreamResponse.pipe(response)
  })
  response.on('close', () => upstream.destroy())
  upstream.on('error', () => {
    if (response.destroyed) return
    if (response.headersSent) { response.destroy(); return }
    sendResponse(response, 502, { 'content-type': 'application/json; charset=utf-8' }, JSON.stringify({ message: '后端服务不可用' }))
  })
  let receivedBytes = 0
  let rejected = false
  request.on('data', chunk => {
    if (rejected) return
    receivedBytes += chunk.length
    if (receivedBytes > maxRequestBytes) {
      rejected = true
      upstream.destroy()
      sendResponse(response, 413, { 'content-type': 'application/json; charset=utf-8' }, JSON.stringify({ message: '请求体过大' }))
      request.destroy()
      return
    }
    upstream.write(chunk)
  })
  request.on('end', () => { if (!rejected) upstream.end() })
  request.on('aborted', () => upstream.destroy())
}

/** 解析静态路径并拒绝畸形编码和越出构建目录的路径。 */
function resolveStaticPath(baseRoot, requestUrl, fallbackToIndex) {
  const parsed = requestPath(requestUrl)
  if (parsed.status) return parsed
  let pathname
  try { pathname = decodeURIComponent(parsed.pathname) } catch { return { status: 400 } }
  const relative = (pathname === '/' ? 'index.html' : pathname).replace(/^[/\\]+/, '')
  const candidate = path.resolve(baseRoot, relative)
  if (candidate !== baseRoot && !candidate.startsWith(`${baseRoot}${path.sep}`)) return { status: 400 }
  try {
    if (fs.statSync(candidate).isFile()) return { status: 200, file: candidate }
  } catch { /* 不存在或竞争删除的静态文件统一回退到 SPA 首页。 */ }
  return fallbackToIndex ? { status: 200, file: path.join(baseRoot, 'index.html') } : { status: 404 }
}

/** 返回静态文件，未知前端路由回退到 SPA 首页。 */
function serve(request, response, baseRoot = root, fallbackToIndex = true, requestUrl = request.url) {
  const resolved = resolveStaticPath(baseRoot, requestUrl, fallbackToIndex)
  if (!resolved.file) {
    return sendResponse(response, resolved.status, { 'content-type': 'text/plain; charset=utf-8' }, resolved.status === 404 ? 'Not Found' : 'Bad Request')
  }
  const safePath = resolved.file
  response.writeHead(200, { ...securityHeaders, 'content-type': contentTypes[path.extname(safePath)] || 'application/octet-stream' })
  fs.createReadStream(safePath).pipe(response)
}

const server = http.createServer((request, response) => {
  const parsed = requestPath(request.url)
  if (parsed.status) return sendResponse(response, 400, { 'content-type': 'text/plain; charset=utf-8' }, 'Bad Request')
  if (parsed.pathname === '/api/internal' || parsed.pathname.startsWith('/api/internal/')) {
    return sendResponse(response, 404, { 'content-type': 'text/plain; charset=utf-8' }, 'Not Found')
  }
  if (request.url === '/runtime-config.js') {
    response.writeHead(200, { ...securityHeaders, 'content-type': 'application/javascript; charset=utf-8', 'cache-control': 'no-store' })
    return response.end(`window.__APP_CONFIG__=${JSON.stringify(platformConfig)};`)
  }
  if (parsed.pathname === '/health') {
    response.writeHead(200, { ...securityHeaders, 'content-type': 'application/json' })
    return response.end('{"status":"UP"}')
  }
  if (parsed.pathname.startsWith('/agent-dist/')) {
    return serve(request, response, agentRoot, false, parsed.pathname.slice('/agent-dist'.length) || '/')
  }
  return request.url.startsWith('/api/') ? proxy(request, response) : serve(request, response)
}).listen(port, '0.0.0.0')

/** 仅代理终端升级请求，保留原始 Host 供后端验证浏览器同源。 */
server.on('upgrade', (request, socket, head) => {
  if (request.url !== '/api/servers/terminal/socket') { socket.destroy(); return }
  const upstream = http.request({
    hostname: backend.hostname,
    port: backend.port || 80,
    path: request.url,
    method: 'GET',
    headers: request.headers
  })
  upstream.setTimeout(15000, () => upstream.destroy())
  upstream.on('upgrade', (response, remote, remoteHead) => {
    upstream.setTimeout(0)
    socket.write(`HTTP/1.1 101 Switching Protocols\r\n${Object.entries(response.headers).map(([name, value]) => `${name}: ${value}\r\n`).join('')}\r\n`)
    if (remoteHead.length) socket.write(remoteHead)
    if (head.length) remote.write(head)
    remote.pipe(socket)
    socket.pipe(remote)
    remote.on('error', () => socket.destroy())
    remote.on('close', () => socket.destroy())
    socket.on('close', () => remote.destroy())
  })
  upstream.on('response', response => { response.resume(); socket.destroy() })
  upstream.on('error', () => socket.destroy())
  socket.on('error', () => upstream.destroy())
  socket.on('close', () => upstream.destroy())
  upstream.end()
})
