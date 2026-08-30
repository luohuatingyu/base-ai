import http from 'http'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), 'dist')
const port = Number(process.env.PORT || 80)
const backend = new URL(process.env.BACKEND_URL || 'http://backend:8080')
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
  '.svg': 'image/svg+xml', '.png': 'image/png', '.ico': 'image/x-icon'
}

/** 将同源 API 请求代理到 Java 后端。 */
function proxy(request, response) {
  const upstream = http.request({
    hostname: backend.hostname,
    port: backend.port || 80,
    path: request.url,
    method: request.method,
    headers: { ...request.headers, host: backend.host }
  }, (upstreamResponse) => {
    response.writeHead(upstreamResponse.statusCode || 502, upstreamResponse.headers)
    upstreamResponse.pipe(response)
  })
  upstream.on('error', () => {
    response.writeHead(502, { 'content-type': 'application/json; charset=utf-8' })
    response.end(JSON.stringify({ message: '后端服务不可用' }))
  })
  request.pipe(upstream)
}

/** 解析静态路径并拒绝畸形编码和越出构建目录的路径。 */
function resolveStaticPath(requestUrl) {
  let pathname
  try {
    pathname = decodeURIComponent(new URL(requestUrl, 'http://localhost').pathname)
  } catch {
    return { status: 400 }
  }
  const relative = (pathname === '/' ? 'index.html' : pathname).replace(/^[/\\]+/, '')
  const candidate = path.resolve(root, relative)
  if (candidate !== root && !candidate.startsWith(`${root}${path.sep}`)) return { status: 400 }
  try {
    if (fs.statSync(candidate).isFile()) return { status: 200, file: candidate }
  } catch { /* 不存在或竞争删除的静态文件统一回退到 SPA 首页。 */ }
  return { status: 200, file: path.join(root, 'index.html') }
}

/** 返回静态文件，未知前端路由回退到 SPA 首页。 */
function serve(request, response) {
  const resolved = resolveStaticPath(request.url)
  if (!resolved.file) {
    response.writeHead(resolved.status, { 'content-type': 'text/plain; charset=utf-8' })
    return response.end('Bad Request')
  }
  const safePath = resolved.file
  response.writeHead(200, { 'content-type': contentTypes[path.extname(safePath)] || 'application/octet-stream' })
  fs.createReadStream(safePath).pipe(response)
}

http.createServer((request, response) => {
  if (request.url === '/runtime-config.js') {
    response.writeHead(200, { 'content-type': 'application/javascript; charset=utf-8', 'cache-control': 'no-store' })
    return response.end(`window.__APP_CONFIG__=${JSON.stringify(platformConfig)};`)
  }
  if (request.url === '/health') {
    response.writeHead(200, { 'content-type': 'application/json' })
    return response.end('{"status":"UP"}')
  }
  return request.url.startsWith('/api/') ? proxy(request, response) : serve(request, response)
}).listen(port, '0.0.0.0')
