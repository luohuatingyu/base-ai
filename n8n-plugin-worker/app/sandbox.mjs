/** 在一次性 Docker 容器中安装、探测或调用单个 n8n 插件。 */

import { spawn } from 'node:child_process'
import { dirname, resolve } from 'node:path'
import { fileURLToPath } from 'node:url'
import { PackageError, PackageStore } from './package-store.mjs'

const root = dirname(fileURLToPath(import.meta.url))
const maximum = Math.max(1024, Math.min(Number(process.env.PLUGIN_WORKER_MAX_RESPONSE_BYTES || 16 * 1024 * 1024),
  16 * 1024 * 1024))

/** 读取 Broker 传入的有限 JSON。 */
async function readInput() {
  let value = ''; let size = 0
  for await (const chunk of process.stdin) {
    size += chunk.length
    if (size > 16 * 1024 * 1024) throw new PackageError('REQUEST_SIZE_INVALID')
    value += chunk
  }
  const parsed = JSON.parse(value)
  if (!parsed || typeof parsed !== 'object' || Array.isArray(parsed)) throw new PackageError('REQUEST_JSON_INVALID')
  return parsed
}

/** 从当前指纹独占卷读取组件并在无长期凭据的子进程中调用。 */
async function invoke(store, request) {
  const installed = await store.metadata(String(request.fingerprint || ''))
  const component = installed.metadata.components.find(item => item.externalId === request.componentId)
  if (!component || component.compatibilityStatus === 'UNSUPPORTED') throw new PackageError('PLUGIN_COMPONENT_UNSUPPORTED')
  const payload = JSON.stringify({ ...request, root: installed.root, sourcePath: component.sourcePath,
    exportName: component.exportName, credentialAuthentications: component.credentialAuthentications || [] })
  const timeout = Math.max(1000, Math.min(Number(process.env.PLUGIN_INVOCATION_TIMEOUT_SECONDS || 60) * 1000, 300000))
  return new Promise((resolvePromise, rejectPromise) => {
    const child = spawn(process.execPath, [resolve(root, 'invoke-child.mjs')], { env: {
      PATH: process.env.PATH || '', NODE_ENV: 'production', LANG: 'C.UTF-8',
      HTTP_PROXY: process.env.HTTP_PROXY || '', HTTPS_PROXY: process.env.HTTPS_PROXY || '',
      NO_PROXY: process.env.NO_PROXY || '', PLUGIN_HTTP_RESPONSE_MAX_BYTES: process.env.PLUGIN_HTTP_RESPONSE_MAX_BYTES || '',
    }, stdio: ['pipe', 'pipe', 'pipe'] })
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

try {
  const operation = process.argv[2]
  if (!['inspect', 'invoke'].includes(operation)) throw new PackageError('PLUGIN_SANDBOX_OPERATION_INVALID')
  const request = await readInput()
  const store = new PackageStore()
  const result = operation === 'inspect' ? await store.install(request) : await invoke(store, request)
  process.stdout.write(JSON.stringify(result))
} catch (error) {
  const code = error instanceof PackageError && /^[A-Z0-9_]{1,80}$/.test(error.message)
    ? error.message : 'PLUGIN_SANDBOX_FAILURE'
  process.stdout.write(JSON.stringify({ error: code }))
  process.exitCode = 1
}
