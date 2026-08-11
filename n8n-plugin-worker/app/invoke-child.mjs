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

/** 判断字段的展示条件是否符合当前参数。 */
function displayed(item, parameters) {
  const show = item?.displayOptions?.show || {}
  const hide = item?.displayOptions?.hide || {}
  const matched = (rules, expected) => Object.entries(rules).every(([name, choices]) =>
    (Array.isArray(choices) ? choices : [choices]).includes(parameter(parameters, name, undefined)) === expected)
  return matched(show, true) && matched(hide, false)
}

/** 解析常见 n8n 参数和凭据插值，拒绝执行任意 JavaScript 表达式。 */
function interpolate(value, parameters, credentials, currentValue) {
  if (Array.isArray(value)) return value.map(item => interpolate(item, parameters, credentials, currentValue))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .map(([key, item]) => [key, interpolate(item, parameters, credentials, currentValue)]))
  if (typeof value !== 'string') return value
  const exact = value.match(/^=\{\{\s*\$(credentials|parameter)(?:\["([^"]+)"\]|\.([A-Za-z0-9_.-]+))\s*\}\}$/)
  if (exact) return parameter(exact[1] === 'credentials' ? credentials : parameters, exact[2] || exact[3], '')
  if (/^=\{\{\s*["']([^"']*)["']\s*\+\s*\$credentials\.([A-Za-z0-9_.-]+)\s*\}\}$/.test(value)) {
    const match = value.match(/^=\{\{\s*["']([^"']*)["']\s*\+\s*\$credentials\.([A-Za-z0-9_.-]+)\s*\}\}$/)
    return match[1] + parameter(credentials, match[2], '')
  }
  return value.replace(/\{\{\s*\$parameter(?:\["([^"]+)"\]|\.([A-Za-z0-9_.-]+))\s*\}\}/g,
    (_, bracket, dotted) => String(parameter(parameters, bracket || dotted, '')))
    .replace(/\{\{\s*\$value\s*\}\}/g, String(currentValue ?? ''))
}

/** 把路由字段写入 HTTP 请求的 body/query/header/path。 */
function sendValue(options, send, value) {
  if (!send || value === undefined || value === null || value === '') return
  const type = String(send.type || 'body').toLowerCase()
  const property = String(send.property || '')
  if (!property) return
  const target = type === 'query' || type === 'qs' ? (options.qs ||= {})
    : type === 'header' || type === 'headers' ? (options.headers ||= {}) : (options.body ||= {})
  target[property] = value
}

/** 递归应用顶层与 collection 属性的声明式路由。 */
function applyProperties(properties, parameters, credentials, options, parent) {
  for (const property of Array.isArray(properties) ? properties : []) {
    if (!displayed(property, parameters)) continue
    const value = parent ? parent[property.name] : parameter(parameters, property.name, property.default)
    const selected = Array.isArray(property.options)
      ? property.options.find(option => option.value === value && displayed(option, parameters)) : undefined
    if (selected?.routing?.request) Object.assign(options, interpolate(selected.routing.request, parameters, credentials, value))
    if (property.routing?.request) Object.assign(options, interpolate(property.routing.request, parameters, credentials, value))
    if (property.routing?.send) {
      const sent = property.routing.send.value === undefined ? value
        : interpolate(property.routing.send.value, parameters, credentials, value)
      sendValue(options, property.routing.send, sent)
    }
    if (value && typeof value === 'object' && Array.isArray(property.options)) {
      applyProperties(property.options, parameters, credentials, options, value)
    }
  }
}

/** 解释 n8n 声明式 HTTP 节点的常用 request/send/authenticate 子集。 */
async function invokeDeclarative(instance, request, context) {
  const description = instance.description || {}
  const parameters = request.parameters && typeof request.parameters === 'object' ? request.parameters : {}
  const credentials = request.credentials && typeof request.credentials === 'object' ? request.credentials : {}
  const options = interpolate(structuredClone(description.requestDefaults || {}), parameters, credentials)
  applyProperties(description.properties, parameters, credentials, options)
  for (const authentication of request.credentialAuthentications || []) {
    const properties = interpolate(authentication?.properties || {}, parameters, credentials)
    options.headers = { ...(options.headers || {}), ...(properties.headers || {}) }
    options.qs = { ...(options.qs || {}), ...(properties.qs || properties.query || {}) }
  }
  const base = String(options.baseURL || options.baseUrl || '')
  const path = String(options.url || '')
  const url = new URL(path, base.endsWith('/') ? base : base + '/')
  for (const [name, value] of Object.entries(options.qs || {})) url.searchParams.set(name, String(value))
  options.url = url.toString(); delete options.baseURL; delete options.baseUrl; delete options.qs
  const response = await context.helpers.httpRequest(options)
  return [[{ json: response && typeof response === 'object' ? response : { data: response } }]]
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
      const url = new URL(options.url || options.uri)
      for (const [name, value] of Object.entries(options.qs || options.query || {})) {
        if (value !== undefined && value !== null) url.searchParams.set(name, String(value))
      }
      const headers = { ...(options.headers || {}) }
      let body = options.body
      if (options.form && typeof options.form === 'object') {
        body = new URLSearchParams(Object.entries(options.form).map(([key, value]) => [key, String(value)]))
        if (!Object.keys(headers).some(key => key.toLowerCase() === 'content-type')) headers['content-type'] = 'application/x-www-form-urlencoded'
      } else if (body !== undefined && options.json !== false && typeof body !== 'string' && !Buffer.isBuffer(body)) {
        body = JSON.stringify(body)
        if (!Object.keys(headers).some(key => key.toLowerCase() === 'content-type')) headers['content-type'] = 'application/json'
      }
      const response = await fetch(url, {
        method: options.method || 'GET', headers, body,
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
    /** 应用探测阶段提取的凭据认证声明后执行请求。 */
    async httpRequestWithAuthentication(name, options) {
      const authenticated = structuredClone(options || {})
      for (const authentication of request.credentialAuthentications || []) {
        const properties = interpolate(authentication?.properties || {}, parameters, credentials)
        authenticated.headers = { ...(authenticated.headers || {}), ...(properties.headers || {}) }
        authenticated.qs = { ...(authenticated.qs || {}), ...(properties.qs || properties.query || {}) }
      }
      return this.httpRequest(authenticated)
    },
    /** 兼容旧版带凭据请求帮助方法。 */
    async requestWithAuthentication(name, options) { return this.httpRequestWithAuthentication(name, options) },
    /** 把任意值规范化为 n8n 数据项。 */
    returnJsonArray(value) { return (Array.isArray(value) ? value : [value]).map(item => ({ json: item?.json || item })) },
    /** 保留输入项配对信息。 */
    constructExecutionMetaData(value) { return value },
    /** 把文本转换为二进制描述。 */
    async prepareBinaryData(data, fileName = 'file.bin', mimeType = 'application/octet-stream') {
      const buffer = Buffer.isBuffer(data) ? data : Buffer.from(data)
      return { data: buffer.toString('base64'), fileName, mimeType, fileSize: buffer.length }
    },
    /** 从 Base64 二进制描述恢复 Buffer。 */
    async getBinaryDataBuffer(itemIndex, propertyName) {
      const value = items[itemIndex]?.binary?.[propertyName]?.data
      if (typeof value !== 'string') throw new Error('N8N_BINARY_DATA_MISSING')
      return Buffer.from(value, 'base64')
    },
  }
  const workflowStaticData = {}
  const known = {
    getInputData: () => items,
    getNodeParameter: (name, index = 0, fallback) => parameter(parameters, name, fallback),
    getCredentials: async () => credentials,
    getNode: () => ({ name: request.nodeName || request.componentId, type: request.componentId, typeVersion: 1, parameters }),
    getWorkflow: () => ({ id: request.context?.workflowId || '', name: request.context?.workflowName || 'Base AI' }),
    getMode: () => 'production', getTimezone: () => request.context?.timezone || 'Asia/Shanghai',
    getExecutionId: () => request.context?.runId || '', continueOnFail: () => false,
    getWorkflowStaticData: () => workflowStaticData,
    getContext: () => request.context || {},
    getInputSourceData: () => ({ previousNode: request.context?.previousNode || '' }),
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
    if (instance.description?.requestDefaults) return invokeDeclarative(instance, request, context)
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
