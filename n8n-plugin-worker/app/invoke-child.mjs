/** 在短生命周期 Node 子进程中执行一个 n8n 插件组件。 */

import { createRequire } from 'node:module'
import { resolve, sep } from 'node:path'

const require = createRequire(import.meta.url)

/** 读取完整标准输入。 */
async function readInput() {
  let value = ''
  for await (const chunk of process.stdin) value += chunk
  return JSON.parse(value)
}

/** 选择指定导出或第一个可构造组件。 */
function component(module, exportName) {
  const values = exportName && module[exportName] ? [module[exportName]] : Object.values(module || {})
  for (const value of values) {
    if (typeof value === 'function') { try { return new value() } catch { continue } }
    if (value && typeof value === 'object') return value
  }
  throw new Error('NODE_COMPONENT_CLASS_MISSING')
}

/** 按点路径读取节点参数。 */
function parameter(parameters, name, fallback) {
  const parts = String(name || '').split('.')
  let value = parameters
  for (const part of parts) value = value && Object.prototype.hasOwnProperty.call(value, part) ? value[part] : undefined
  return value === undefined ? fallback : value
}

/** 构造常用 IExecuteFunctions 兼容上下文。 */
function executionContext(request) {
  const parameters = request.parameters && typeof request.parameters === 'object' ? request.parameters : {}
  const credentials = request.credentials && typeof request.credentials === 'object' ? request.credentials : {}
  const inputs = Array.isArray(request.input) ? request.input : [request.input ?? {}]
  const items = inputs.map(value => value && value.json ? value : { json: value })
  const helpers = {
    /** 通过标准 fetch 执行插件 HTTP 请求。 */
    async httpRequest(options) {
      const response = await fetch(options.url || options.uri, {
        method: options.method || 'GET', headers: options.headers || {},
        body: options.body === undefined ? undefined : options.json === false ? options.body : JSON.stringify(options.body),
        signal: AbortSignal.timeout(Math.min(Number(options.timeout || 30000), 120000)),
      })
      const text = await response.text()
      if (!response.ok && !options.ignoreHttpStatusErrors) throw new Error(`HTTP_${response.status}`)
      if (options.returnFullResponse) return { statusCode: response.status, headers: Object.fromEntries(response.headers), body: options.json === false ? text : JSON.parse(text) }
      if (options.json === false) return text
      try { return JSON.parse(text) } catch { return text }
    },
    /** 兼容历史 request 帮助方法。 */
    async request(options) { return this.httpRequest(options) },
    /** 把任意值规范化为 n8n 数据项。 */
    returnJsonArray(value) { return (Array.isArray(value) ? value : [value]).map(item => ({ json: item?.json || item })) },
    /** 保留输入项配对信息。 */
    constructExecutionMetaData(value) { return value },
    /** 把文本转换为二进制描述。 */
    async prepareBinaryData(data, fileName = 'file.bin', mimeType = 'application/octet-stream') {
      const buffer = Buffer.isBuffer(data) ? data : Buffer.from(data)
      return { data: buffer.toString('base64'), fileName, mimeType, fileSize: buffer.length }
    },
  }
  const known = {
    getInputData: () => items,
    getNodeParameter: (name, index = 0, fallback) => parameter(parameters, name, fallback),
    getCredentials: async () => credentials,
    getNode: () => ({ name: request.nodeName || request.componentId, type: request.componentId, typeVersion: 1, parameters }),
    getWorkflow: () => ({ id: request.context?.workflowId || '', name: request.context?.workflowName || 'Base AI' }),
    getMode: () => 'production', getTimezone: () => request.context?.timezone || 'Asia/Shanghai',
    getExecutionId: () => request.context?.runId || '', continueOnFail: () => false,
    getRestApiUrl: () => '', getInstanceBaseUrl: () => '', helpers,
    prepareOutputData: async value => value,
    sendMessageToUI: () => undefined, addExecutionHints: () => undefined,
  }
  return new Proxy(known, {
    /** 未实现的 ABI 在真正调用时返回明确错误。 */
    get(target, property) {
      if (property in target) return target[property]
      return () => { throw new Error(`N8N_ABI_MISSING:${String(property)}`) }
    }
  })
}

/** 调用与组件类型和操作相符的入口。 */
async function invoke(instance, request) {
  const context = executionContext(request)
  const operation = String(request.operation || 'invoke')
  if (operation === 'invoke') {
    if (typeof instance.execute === 'function') return instance.execute.call(context)
    if (typeof instance.supplyData === 'function') return instance.supplyData.call(context, 0)
  }
  if (operation === 'subscribe' && typeof instance.trigger === 'function') return instance.trigger.call(context)
  if (operation === 'dispatch_event' && typeof instance.webhook === 'function') return instance.webhook.call(context)
  if (operation === 'refresh' && typeof instance.poll === 'function') return instance.poll.call(context)
  if (operation === 'oauth_authorize' && typeof instance.generateAuthorizationUrl === 'function') return instance.generateAuthorizationUrl.call(context)
  if (operation === 'oauth_exchange' && typeof instance.exchangeAuthorizationCode === 'function') return instance.exchangeAuthorizationCode.call(context)
  throw new Error('NODE_ABI_METHOD_MISSING')
}

try {
  const request = await readInput()
  const root = resolve(request.root)
  const source = resolve(root, request.sourcePath)
  if (!source.startsWith(root + sep)) throw new Error('NODE_SOURCE_INVALID')
  const module = require(source)
  const output = await invoke(component(module, request.exportName), request)
  process.stdout.write(JSON.stringify({ success: true, output }))
} catch (error) {
  process.stdout.write(JSON.stringify({ success: false, error: String(error?.message || error).slice(0, 500) }))
  process.exitCode = 1
}
