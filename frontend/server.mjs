import http from 'http'
import fs from 'fs'
import path from 'path'
import { fileURLToPath } from 'url'

const root = path.join(path.dirname(fileURLToPath(import.meta.url)), 'dist')
const port = Number(process.env.PORT || 80)
const backend = new URL(process.env.BACKEND_URL || 'http://backend:8080')
const platformConfig = {
  code: process.env.APP_PLATFORM_CODE || 'ai-platform',
  nameEn: process.env.APP_PLATFORM_NAME_EN || 'AI Platform',
  nameZh: process.env.APP_PLATFORM_NAME_ZH || 'AI平台',
  shortName: process.env.APP_PLATFORM_SHORT_NAME || 'AI'
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

/** 返回静态文件，未知前端路由回退到 SPA 首页。 */
function serve(request, response) {
  const pathname = decodeURIComponent(new URL(request.url, 'http://localhost').pathname)
  const candidate = path.normalize(path.join(root, pathname === '/' ? 'index.html' : pathname))
  const safePath = candidate.startsWith(root) && fs.existsSync(candidate) && fs.statSync(candidate).isFile()
    ? candidate : path.join(root, 'index.html')
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
