/** n8n 插件兼容 Worker 的受鉴权 HTTP 入口。 */

import { createServer } from 'node:http'
import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PackageError, PackageStore } from './package-store.mjs'

const root = dirname(fileURLToPath(import.meta.url))
const store = new PackageStore()
const token = process.env.PLUGIN_WORKER_INTERNAL_TOKEN || ''
const maximum = Number(process.env.PLUGIN_WORKER_MAX_REQUEST_BYTES || 15 * 1024 * 1024)
const timeout = Math.min(Number(process.env.PLUGIN_INVOCATION_TIMEOUT_SECONDS || 60), 300) * 1000
const inspectTimeout = Math.min(Number(process.env.PLUGIN_DEPENDENCY_INSTALL_TIMEOUT_SECONDS || 180) + 60, 660) * 1000

/** 写入有限 JSON 响应。 */
function respond(response, status, value) {
  const body = Buffer.from(JSON.stringify(value))
  response.writeHead(status, { 'content-type': 'application/json; charset=utf-8', 'content-length': body.length })
  response.end(body)
}

/** 读取并限制 JSON 请求体。 */
async function body(request) {
  const declared = Number(request.headers['content-length'] || 0)
  if (declared <= 0 || declared > maximum) throw new PackageError('REQUEST_SIZE_INVALID')
  const chunks = []; let size = 0
  for await (const chunk of request) {
    size += chunk.length
    if (size > maximum) throw new PackageError('REQUEST_SIZE_INVALID')
    chunks.push(chunk)
  }
  const value = JSON.parse(Buffer.concat(chunks).toString('utf8'))
  if (!value || typeof value !== 'object' || Array.isArray(value)) throw new PackageError('REQUEST_JSON_INVALID')
  return value
}

/** 在不继承内部令牌的子进程中调用插件。 */
async function invoke(request) {
  const installed = await store.metadata(String(request.fingerprint || ''))
  const component = installed.metadata.components.find(item => item.externalId === request.componentId)
  if (!component || component.compatibilityStatus === 'UNSUPPORTED') throw new PackageError('PLUGIN_COMPONENT_UNSUPPORTED')
  const payload = JSON.stringify({ ...request, root: installed.root, sourcePath: component.sourcePath,
    exportName: component.exportName, credentialAuthentications: component.credentialAuthentications || [] })
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [resolve(root, 'invoke-child.mjs')], {
      env: {
        PATH: process.env.PATH || '', NODE_ENV: 'production', LANG: 'C.UTF-8',
        HTTP_PROXY: process.env.HTTP_PROXY || '', HTTPS_PROXY: process.env.HTTPS_PROXY || '',
        NO_PROXY: process.env.NO_PROXY || '',
      }, stdio: ['pipe', 'pipe', 'pipe'],
    })
    let stdout = ''; let stderr = ''
    const timer = setTimeout(() => { child.kill('SIGKILL'); rejectPromise(new PackageError('PLUGIN_INVOCATION_TIMEOUT')) }, timeout)
    child.stdout.on('data', chunk => { stdout += chunk; if (stdout.length > maximum) child.kill('SIGKILL') })
    child.stderr.on('data', chunk => { stderr += chunk; if (stderr.length > 4096) child.kill('SIGKILL') })
    child.on('error', rejectPromise)
    child.on('close', code => {
      clearTimeout(timer)
      try {
        const result = JSON.parse(stdout)
        if (code !== 0 || !result.success) rejectPromise(new PackageError(result.error || 'PLUGIN_INVOCATION_FAILED'))
        else resolvePromise(result)
      } catch { rejectPromise(new PackageError('PLUGIN_OUTPUT_INVALID')) }
    })
    child.stdin.end(payload)
  })
}

/** 在不继承内部令牌的短生命周期进程中解压、安装依赖并探测插件。 */
async function inspect(request) {
  return childRequest('inspect-child.mjs', request, inspectTimeout)
}

/** 执行只通过标准输入输出交换有限 JSON 的隔离子进程。 */
function childRequest(script, request, maximumTime) {
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [resolve(root, script)], {
      env: { PATH: process.env.PATH || '', NODE_ENV: 'production', LANG: 'C.UTF-8', HOME: '/data/tmp',
        npm_config_cache: '/data/tmp/npm-cache' }, stdio: ['pipe', 'pipe', 'pipe'],
    })
    let stdout = ''; let stderr = ''
    const timer = setTimeout(() => { child.kill('SIGKILL'); rejectPromise(new PackageError('PLUGIN_INSPECTION_TIMEOUT')) }, maximumTime)
    child.stdout.on('data', chunk => { stdout += chunk; if (stdout.length > maximum) child.kill('SIGKILL') })
    child.stderr.on('data', chunk => { stderr += chunk; if (stderr.length > 4096) child.kill('SIGKILL') })
    child.on('error', rejectPromise)
    child.on('close', code => {
      clearTimeout(timer)
      try {
        const result = JSON.parse(stdout)
        if (code !== 0 || result.error) rejectPromise(new PackageError(result.error || 'PLUGIN_INSPECTION_FAILED'))
        else resolvePromise(result)
      } catch { rejectPromise(new PackageError('PLUGIN_OUTPUT_INVALID')) }
    })
    child.stdin.end(JSON.stringify(request))
  })
}

if (token.length < 24) throw new Error('PLUGIN_WORKER_INTERNAL_TOKEN 至少需要 24 个字符')

createServer(async (request, response) => {
  try {
    if (request.method === 'GET' && request.url === '/health') {
      respond(response, 200, { status: 'UP', runtime: 'base-ai-node-abi', node: '24' }); return
    }
    if (request.headers['x-internal-token'] !== token) { respond(response, 401, { error: 'UNAUTHORIZED' }); return }
    const value = await body(request)
    if (request.method === 'POST' && request.url === '/packages/inspect') respond(response, 200, await inspect(value))
    else if (request.method === 'POST' && request.url === '/packages/remove') respond(response, 200, await store.remove(value.fingerprint))
    else if (request.method === 'POST' && request.url === '/invocations') respond(response, 200, await invoke(value))
    else respond(response, 404, { error: 'NOT_FOUND' })
  } catch (error) {
    const status = error instanceof PackageError ? (error.message.includes('TIMEOUT') ? 504 : 400) : 500
    respond(response, status, { error: status === 500 ? 'PLUGIN_WORKER_FAILURE' : String(error.message).slice(0, 500) })
  }
}).listen(Number(process.env.PORT || 8102), '0.0.0.0')
