/** 只允许经显式 HTTP 代理执行的有限 Fetch 兼容传输。 */

import { request as httpRequest } from 'node:http'
import { request as httpsRequest } from 'node:https'
import { connect as tlsConnect } from 'node:tls'

const maximumResponseBytes = Math.max(1024,
  Math.min(Number(process.env.PLUGIN_HTTP_RESPONSE_MAX_BYTES || 10 * 1024 * 1024), 100 * 1024 * 1024))

/** 通过 HTTP_PROXY/HTTPS_PROXY 强制发送请求，禁止任何直连回退。 */
export async function proxyFetch(value, options = {}, proxyOverride = '') {
  const target = new URL(value)
  if (!['http:', 'https:'].includes(target.protocol) || target.username || target.password || target.hash) {
    throw new Error('N8N_OUTBOUND_URL_INVALID')
  }
  const configured = proxyOverride || (target.protocol === 'https:' ? process.env.HTTPS_PROXY : process.env.HTTP_PROXY)
  if (!configured) throw new Error('N8N_OUTBOUND_PROXY_REQUIRED')
  const proxy = new URL(configured)
  if (proxy.protocol !== 'http:' || !proxy.hostname || proxy.pathname !== '/' || proxy.search || proxy.hash) {
    throw new Error('N8N_OUTBOUND_PROXY_INVALID')
  }
  const prepared = await prepare(options)
  return target.protocol === 'https:'
    ? secureRequest(proxy, target, prepared, options.signal)
    : plainRequest(proxy, target, prepared, options.signal)
}

/** 规范请求头和请求体，避免插件覆盖 Host 或代理认证。 */
async function prepare(options) {
  const headers = new Headers(options.headers || {})
  for (const name of ['host', 'proxy-authorization', 'proxy-connection', 'connection']) headers.delete(name)
  let body = options.body
  if (body instanceof FormData) {
    const encoded = new Response(body)
    body = Buffer.from(await encoded.arrayBuffer())
    if (!headers.has('content-type')) headers.set('content-type', encoded.headers.get('content-type'))
  } else if (body instanceof URLSearchParams) {
    body = Buffer.from(body.toString())
  } else if (body !== undefined && body !== null && !Buffer.isBuffer(body)) {
    body = Buffer.from(String(body))
  }
  if (body !== undefined && body !== null) headers.set('content-length', String(body.length))
  return { method: String(options.method || 'GET').toUpperCase(), headers, body,
    timeout: Math.max(1, Math.min(Number(options.timeout || 30000), 120000)) }
}

/** 通过普通 HTTP 正向代理发送绝对 URL 请求。 */
function plainRequest(proxy, target, prepared, signal) {
  const headers = requestHeaders(prepared.headers)
  headers.host = target.host
  headers['proxy-authorization'] = authorization(proxy)
  return execute(httpRequest, {
    hostname: proxy.hostname, port: proxy.port || 80, method: prepared.method,
    path: target.toString(), headers,
  }, prepared, signal)
}

/** 先经代理建立 CONNECT 隧道，再使用原域名执行严格 TLS 握手。 */
function secureRequest(proxy, target, prepared, signal) {
  return new Promise((resolve, reject) => {
    const authority = `${target.hostname}:${target.port || 443}`
    const connect = httpRequest({
      hostname: proxy.hostname, port: proxy.port || 80, method: 'CONNECT', path: authority,
      headers: { host: authority, 'proxy-authorization': authorization(proxy) },
    })
    const abort = () => connect.destroy(new Error('N8N_OUTBOUND_ABORTED'))
    if (signal?.aborted) return abort()
    signal?.addEventListener('abort', abort, { once: true })
    connect.setTimeout(prepared.timeout, () => connect.destroy(new Error('N8N_OUTBOUND_TIMEOUT')))
    connect.once('error', reject)
    connect.once('connect', (response, socket, head) => {
      signal?.removeEventListener('abort', abort)
      if (response.statusCode !== 200) {
        socket.destroy(); reject(new Error(`N8N_OUTBOUND_PROXY_${response.statusCode || 502}`)); return
      }
      if (head.length) socket.unshift(head)
      const secure = tlsConnect({ socket, servername: target.hostname, rejectUnauthorized: true })
      secure.once('error', reject)
      secure.once('secureConnect', () => {
        const headers = requestHeaders(prepared.headers)
        headers.host = target.host
        execute(httpsRequest, {
          hostname: target.hostname, port: target.port || 443, method: prepared.method,
          path: `${target.pathname}${target.search}`, headers, agent: false,
          createConnection: () => secure,
        }, prepared, signal).then(resolve, reject)
      })
    })
    connect.end()
  })
}

/** 执行一次有限请求，并将响应完整限制在配置上限以内。 */
function execute(factory, requestOptions, prepared, signal) {
  return new Promise((resolve, reject) => {
    const request = factory(requestOptions, response => {
      const chunks = []; let size = 0
      response.on('data', chunk => {
        size += chunk.length
        if (size > maximumResponseBytes) {
          response.destroy(new Error('N8N_OUTBOUND_RESPONSE_TOO_LARGE')); return
        }
        chunks.push(chunk)
      })
      response.once('error', reject)
      response.once('end', () => resolve(new ProxyResponse(response.statusCode || 502, response.headers,
        Buffer.concat(chunks))))
    })
    const abort = () => request.destroy(new Error('N8N_OUTBOUND_ABORTED'))
    if (signal?.aborted) return abort()
    signal?.addEventListener('abort', abort, { once: true })
    request.setTimeout(prepared.timeout, () => request.destroy(new Error('N8N_OUTBOUND_TIMEOUT')))
    request.once('error', reject)
    request.once('close', () => signal?.removeEventListener('abort', abort))
    if (prepared.body !== undefined && prepared.body !== null) request.write(prepared.body)
    request.end()
  })
}

/** 生成仅发给代理的 Basic 认证值。 */
function authorization(proxy) {
  const username = decodeURIComponent(proxy.username)
  const password = decodeURIComponent(proxy.password)
  if (!username && !password) throw new Error('N8N_OUTBOUND_PROXY_AUTH_REQUIRED')
  return `Basic ${Buffer.from(`${username}:${password}`).toString('base64')}`
}

/** 把 Web Headers 转换为 Node 请求头对象。 */
function requestHeaders(headers) {
  return Object.fromEntries(headers.entries())
}

class ProxyResponse {
  /** 保存有限响应并提供插件常用的 Fetch Response 接口。 */
  constructor(status, headers, body) {
    this.status = status
    this.ok = status >= 200 && status < 300
    this.headers = new Headers(Object.entries(headers).flatMap(([name, value]) =>
      Array.isArray(value) ? value.map(item => [name, item]) : value === undefined ? [] : [[name, value]]))
    this._body = body
  }

  /** 返回响应文本。 */
  async text() { return this._body.toString('utf8') }

  /** 返回独立的 ArrayBuffer。 */
  async arrayBuffer() {
    return this._body.buffer.slice(this._body.byteOffset, this._body.byteOffset + this._body.byteLength)
  }
}
