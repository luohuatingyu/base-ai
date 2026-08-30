/** 在短生命周期 Node 子进程中执行一个 n8n 插件组件。 */

import { createRequire } from 'node:module'
import { resolve, sep } from 'node:path'
import { proxyFetch } from './proxy-fetch.mjs'

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
    if (typeof value === 'function') { try { return versioned(new value()) } catch { continue } }
    if (value && typeof value === 'object') return versioned(value)
  }
  throw new Error('NODE_COMPONENT_CLASS_MISSING')
}

/** 展开 VersionedNodeType 并固定使用声明的默认版本。 */
function versioned(instance) {
  if (!instance || typeof instance !== 'object') return instance
  if (typeof instance.getNodeType === 'function') return instance.getNodeType() || instance
  const versions = instance.nodeVersions || (instance.name === 'VersionedNodeType' ? instance.args?.[0] : undefined)
  const base = instance.baseDescription || (instance.name === 'VersionedNodeType' ? instance.args?.[1] : undefined) || {}
  if (!versions || typeof versions !== 'object') return instance
  return versions[String(base.defaultVersion)] || versions[base.defaultVersion]
    || Object.entries(versions).sort(([left], [right]) => Number(right) - Number(left))[0]?.[1] || instance
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
function interpolate(value, parameters, credentials, currentValue, responseItem) {
  if (Array.isArray(value)) return value.map(item => interpolate(item, parameters, credentials, currentValue, responseItem))
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value)
    .map(([key, item]) => [key, interpolate(item, parameters, credentials, currentValue, responseItem)]))
  if (typeof value !== 'string') return value
  const exact = value.match(/^=\{\{\s*(.*?)\s*\}\}$/s)
  if (exact) return evaluateExpression(exact[1], { parameters, credentials, currentValue, responseItem })
  const rendered = value.replace(/\{\{\s*(.*?)\s*\}\}/gs,
    (_, expression) => String(evaluateExpression(expression, { parameters, credentials, currentValue, responseItem }) ?? ''))
  const normalized = rendered.startsWith('=') && !rendered.startsWith('={{') ? rendered.slice(1) : rendered
  if (normalized.includes('{{') || normalized.includes('}}') || normalized.startsWith('=')) throw new Error('N8N_EXPRESSION_UNSUPPORTED')
  return normalized
}

/** 解释受限的引用、比较、逻辑、三元、拼接和 JSON.parse 表达式。 */
function evaluateExpression(raw, environment) {
  const expression = raw.trim()
  const question = expression.indexOf('?')
  if (question >= 0) {
    const colon = expression.lastIndexOf(':')
    if (colon <= question) throw new Error('N8N_EXPRESSION_UNSUPPORTED')
    return condition(expression.slice(0, question), environment)
      ? operand(expression.slice(question + 1, colon), environment)
      : operand(expression.slice(colon + 1), environment)
  }
  if (expression.includes('||')) {
    for (const part of expression.split('||')) {
      const value = operand(part, environment)
      if (value) return value
    }
    return undefined
  }
  if (expression.includes('+')) return expression.split('+')
    .map(part => String(operand(part, environment) ?? '')).join('')
  return operand(expression, environment)
}

/** 计算三元表达式中受限的布尔条件。 */
function condition(raw, environment) {
  const expression = unwrap(raw)
  if (expression.includes('||')) return expression.split('||').some(part => condition(part, environment))
  if (expression.includes('&&')) return expression.split('&&').every(part => condition(part, environment))
  const includes = expression.match(/^(.+)\.includes\((.+)\)$/s)
  if (includes) return String(operand(includes[1], environment) ?? '').includes(String(operand(includes[2], environment)))
  const comparison = expression.match(/^(.+?)\s*(===|!==|>=|<=|>|<)\s*(.+)$/s)
  if (comparison) {
    const left = operand(comparison[1], environment); const right = operand(comparison[3], environment)
    return comparison[2] === '===' ? left === right : comparison[2] === '!==' ? left !== right
      : comparison[2] === '>=' ? left >= right : comparison[2] === '<=' ? left <= right
        : comparison[2] === '>' ? left > right : left < right
  }
  const notNumber = expression.match(/^isNaN\(Number\((.+)\)\)$/s)
  if (notNumber) return Number.isNaN(Number(operand(notNumber[1], environment)))
  return Boolean(operand(expression, environment))
}

/** 读取不执行代码的表达式操作数。 */
function operand(raw, environment) {
  const token = unwrap(raw)
  if (token === 'undefined') return undefined
  if (token === 'null') return null
  if (token === 'true') return true
  if (token === 'false') return false
  if (token === '{}') return {}
  if (/^-?\d+(?:\.\d+)?$/.test(token)) return Number(token)
  if (/^"[^"]*"$/.test(token)) return JSON.parse(token)
  if (/^'[^']*'$/.test(token)) return token.slice(1, -1)
  const parsed = token.match(/^JSON\.parse\((.+)\)$/s)
  if (parsed) {
    const value = operand(parsed[1], environment)
    if (typeof value !== 'string') return value
    try { return JSON.parse(value) } catch { throw new Error('N8N_EXPRESSION_JSON_INVALID') }
  }
  const number = token.match(/^Number\((.+)\)$/s)
  if (number) return Number(operand(number[1], environment))
  const collection = token.match(/^(.+)\.(join|split)\(("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*')\)$/s)
  if (collection) {
    const value = operand(collection[1], environment)
    const separator = collection[3][0] === '"' ? JSON.parse(collection[3]) : collection[3].slice(1, -1)
    if (collection[2] === 'join') return Array.isArray(value) ? value.join(separator) : ''
    return typeof value === 'string' ? value.split(separator) : []
  }
  const epoch = token.match(/^Math\.floor\(new Date\((.+)\)\.getTime\(\)\s*\/\s*1000\)$/s)
  if (epoch) return Math.floor(date(operand(epoch[1], environment)).getTime() / 1000)
  const slicedDate = token.match(/^new Date\((.+)\)\.toISOString\(\)\.slice\(0,\s*(-?\d+)\)(?:\s*\+\s*("(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'))?$/s)
  if (slicedDate) {
    const suffix = slicedDate[3] ? (slicedDate[3][0] === '"' ? JSON.parse(slicedDate[3]) : slicedDate[3].slice(1, -1)) : ''
    return date(operand(slicedDate[1], environment)).toISOString().slice(0, Number(slicedDate[2])) + suffix
  }
  const normalizedDate = token.match(/^new Date\((.+)\)\.toISOString\(\)\.replace\(\/\\\.\\d\{3\}Z\$\/,\s*("Z"|'Z')\)$/s)
  if (normalizedDate) return date(operand(normalizedDate[1], environment)).toISOString().replace(/\.\d{3}Z$/, 'Z')
  if (token === '$value') return environment.currentValue
  if (token === '$value.length') return environment.currentValue?.length
  let reference = token; let length = false
  if (reference.endsWith('.length')) { reference = reference.slice(0, -7); length = true }
  const matched = reference.match(/^\$(parameter|credentials|responseItem)(?:\["([^"]+)"\]|\.([A-Za-z0-9_.-]+))$/)
  if (!matched) throw new Error('N8N_EXPRESSION_UNSUPPORTED')
  const root = matched[1] === 'parameter' ? environment.parameters
    : matched[1] === 'credentials' ? environment.credentials : environment.responseItem
  const value = parameter(root || {}, matched[2] || matched[3], undefined)
  return length ? value?.length : value
}

/** 去掉完整包裹表达式的圆括号。 */
function unwrap(raw) {
  let value = String(raw || '').trim()
  while (value.startsWith('(') && value.endsWith(')')) {
    let depth = 0; let complete = true; let quote = ''
    for (let index = 0; index < value.length; index += 1) {
      const character = value[index]
      if (quote) { if (character === quote && value[index - 1] !== '\\') quote = ''; continue }
      if (character === '"' || character === "'") { quote = character; continue }
      if (character === '(') depth += 1
      if (character === ')') depth -= 1
      if (depth === 0 && index < value.length - 1) { complete = false; break }
    }
    if (!complete) break
    value = value.slice(1, -1).trim()
  }
  return value
}

/** 构造有效日期，非法输入返回明确兼容错误。 */
function date(value) {
  const result = new Date(value)
  if (Number.isNaN(result.getTime())) throw new Error('N8N_EXPRESSION_DATE_INVALID')
  return result
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
function applyProperties(properties, parameters, credentials, options, hooks, parent) {
  for (const property of Array.isArray(properties) ? properties : []) {
    if (!displayed(property, parameters)) continue
    const value = parent ? parent[property.name] : parameter(parameters, property.name, property.default)
    const selected = Array.isArray(property.options)
      ? property.options.find(option => option.value === value && displayed(option, parameters)) : undefined
    applyRouting(selected?.routing, parameters, credentials, options, hooks, value)
    applyRouting(property.routing, parameters, credentials, options, hooks, value)
    if (value && typeof value === 'object' && Array.isArray(property.options)) {
      for (const option of property.options) {
        const nested = option?.name && Object.prototype.hasOwnProperty.call(value, option.name) ? value[option.name] : value
        applyProperties(option?.values || option?.options, parameters, credentials, options, hooks, nested)
      }
    }
    if (value && typeof value === 'object' && Array.isArray(property.values)) {
      applyProperties(property.values, parameters, credentials, options, hooks, value)
    }
  }
}

/** 合并单条 routing 声明并收集受控生命周期 hook。 */
function applyRouting(routing, parameters, credentials, options, hooks, value) {
  if (!routing || typeof routing !== 'object') return
  if (routing.request) mergeRequest(options, interpolate(routing.request, parameters, credentials, value))
  if (routing.send) {
    const sent = routing.send.value === undefined ? value
      : interpolate(routing.send.value, parameters, credentials, value)
    sendValue(options, routing.send, sent)
    collectHooks(hooks.preSend, routing.send.preSend)
  }
  collectPostReceivers(hooks.postReceive, routing.output?.postReceive)
}

/** 合并多个字段产生的请求片段，避免后一个查询或请求体覆盖前一个。 */
function mergeRequest(options, request) {
  if (!request || typeof request !== 'object' || Array.isArray(request)) throw new Error('N8N_ROUTING_REQUEST_INVALID')
  const { qs, query, headers, body, ...rest } = request
  Object.assign(options, rest)
  if (qs || query) options.qs = { ...(options.qs || {}), ...(qs || query) }
  if (headers) options.headers = { ...(options.headers || {}), ...headers }
  if (body && typeof body === 'object' && !Array.isArray(body)) options.body = { ...(options.body || {}), ...body }
  else if (body !== undefined) options.body = body
}

/** 只接受插件包实际导出的函数 hook。 */
function collectHooks(target, value) {
  if (value === undefined) return
  if (!Array.isArray(value) || value.some(item => typeof item !== 'function')) throw new Error('N8N_ROUTING_HOOK_INVALID')
  target.push(...value)
}

/** 接受函数 hook 以及 n8n 内置的 rootProperty/setKeyValue 输出声明。 */
function collectPostReceivers(target, value) {
  if (value === undefined) return
  if (!Array.isArray(value) || value.some(item => typeof item !== 'function'
    && !['rootProperty', 'setKeyValue'].includes(item?.type))) throw new Error('N8N_ROUTING_HOOK_INVALID')
  target.push(...value)
}

/** 解释 n8n 声明式 HTTP 节点的常用 request/send/authenticate 子集。 */
async function invokeDeclarative(instance, request, context) {
  const description = instance.description || {}
  const parameters = request.parameters && typeof request.parameters === 'object' ? request.parameters : {}
  const credentials = request.credentials && typeof request.credentials === 'object' ? request.credentials : {}
  const options = interpolate(structuredClone(description.requestDefaults || {}), parameters, credentials)
  const hooks = { preSend: [], postReceive: [] }
  applyProperties(description.properties, parameters, credentials, options, hooks)
  for (const authentication of request.credentialAuthentications || []) {
    const properties = interpolate(authentication?.properties || {}, parameters, credentials)
    options.headers = { ...(options.headers || {}), ...(properties.headers || {}) }
    options.qs = { ...(options.qs || {}), ...(properties.qs || properties.query || {}) }
  }
  for (const hook of hooks.preSend) {
    const updated = await hook.call(context, options)
    if (updated !== undefined) {
      if (!updated || typeof updated !== 'object' || Array.isArray(updated)) throw new Error('N8N_PRESEND_OUTPUT_INVALID')
      Object.assign(options, updated)
    }
  }
  const base = String(options.baseURL || options.baseUrl || '')
  const path = String(options.url || '')
  const url = new URL(path, base.endsWith('/') ? base : base + '/')
  for (const [name, value] of Object.entries(options.qs || {})) {
    if (Array.isArray(value)) value.forEach(item => url.searchParams.append(name, String(item)))
    else url.searchParams.set(name, String(value))
  }
  options.url = url.toString(); delete options.baseURL; delete options.baseUrl; delete options.qs
  const response = await context.helpers.httpRequest(options)
  let items = Array.isArray(response)
    ? response.map(item => ({ json: item && typeof item === 'object' ? item : { data: item } }))
    : [{ json: response && typeof response === 'object' && !Buffer.isBuffer(response) ? response : { data: response } }]
  for (const hook of hooks.postReceive) {
    const updated = typeof hook === 'function' ? await hook.call(context, items, response)
      : applyPostReceiveDirective(hook, items, parameters, credentials)
    if (updated !== undefined) items = updated
    if (!Array.isArray(items)) throw new Error('N8N_POSTRECEIVE_OUTPUT_INVALID')
  }
  return [items]
}

/** 执行 n8n 内置的根属性展开和键值筛选输出声明。 */
function applyPostReceiveDirective(directive, items, parameters, credentials) {
  if (directive.type === 'rootProperty') {
    const propertyName = String(directive.properties?.property || '')
    return items.flatMap(item => {
      const value = parameter(item.json || {}, propertyName, [])
      return (Array.isArray(value) ? value : [value]).map(entry => ({ json: entry && typeof entry === 'object'
        ? entry : { data: entry } }))
    })
  }
  if (directive.type === 'setKeyValue') return items.map(item => {
    const enabled = directive.enabled === undefined
      ? true : Boolean(interpolate(directive.enabled, parameters, credentials, undefined, item.json))
    if (!enabled) return item
    return { ...item, json: interpolate(directive.properties || {}, parameters, credentials, undefined, item.json) }
  })
  throw new Error('N8N_ROUTING_HOOK_INVALID')
}

/** 构造常用 IExecuteFunctions 兼容上下文。 */
function executionContext(request) {
  const parameters = request.parameters && typeof request.parameters === 'object' ? request.parameters : {}
  const credentials = request.credentials && typeof request.credentials === 'object' ? request.credentials : {}
  const inputs = Array.isArray(request.input) ? request.input : [request.input ?? {}]
  const items = inputs.map(value => value && (value.json || value.binary)
    ? { json: value.json || {}, ...(value.binary ? { binary: value.binary } : {}) } : { json: value })
  const helpers = {
    /** 通过标准 fetch 执行插件 HTTP 请求。 */
    async httpRequest(options) {
      const url = new URL(options.url || options.uri)
      for (const [name, value] of Object.entries(options.qs || options.query || {})) {
        if (value === undefined || value === null) continue
        if (Array.isArray(value)) value.forEach(item => url.searchParams.append(name, String(item)))
        else url.searchParams.set(name, String(value))
      }
      const headers = { ...(options.headers || {}) }
      let body = options.body
      if (typeof FormData !== 'undefined' && body instanceof FormData) {
        for (const name of Object.keys(headers)) {
          if (name.toLowerCase() === 'content-type') delete headers[name]
        }
      } else if (options.form && typeof options.form === 'object') {
        body = new URLSearchParams(Object.entries(options.form).map(([key, value]) => [key, String(value)]))
        if (!Object.keys(headers).some(key => key.toLowerCase() === 'content-type')) headers['content-type'] = 'application/x-www-form-urlencoded'
      } else if (body !== undefined && options.json !== false && typeof body !== 'string' && !Buffer.isBuffer(body)) {
        body = JSON.stringify(body)
        if (!Object.keys(headers).some(key => key.toLowerCase() === 'content-type')) headers['content-type'] = 'application/json'
      }
      const response = await proxyFetch(url, {
        method: options.method || 'GET', headers, body,
        signal: AbortSignal.timeout(Math.min(Number(options.timeout || 30000), 120000)),
      })
      if (!response.ok && !options.ignoreHttpStatusErrors) throw new Error(`HTTP_${response.status}`)
      if (String(options.encoding || '').toLowerCase() === 'arraybuffer') {
        const binary = Buffer.from(await response.arrayBuffer())
        return options.returnFullResponse
          ? { statusCode: response.status, headers: Object.fromEntries(response.headers), body: binary } : binary
      }
      const text = await response.text()
      if (options.returnFullResponse) {
        let parsed = text
        if (options.json !== false) { try { parsed = JSON.parse(text) } catch {} }
        return { statusCode: response.status, headers: Object.fromEntries(response.headers), body: parsed }
      }
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
      if (typeof itemIndex === 'string') { propertyName = itemIndex; itemIndex = 0 }
      if (!Number.isInteger(itemIndex)) itemIndex = 0
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
