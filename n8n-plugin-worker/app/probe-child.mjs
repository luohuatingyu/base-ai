/** 在不继承内部令牌的短生命周期子进程中加载 n8n 插件元数据。 */

import { createRequire } from 'node:module'
import { resolve, sep } from 'node:path'

const require = createRequire(import.meta.url)

/** 读取完整标准输入。 */
async function readInput() {
  let value = ''
  for await (const chunk of process.stdin) value += chunk
  return JSON.parse(value)
}

/** 展开 VersionedNodeType，并选择声明的默认版本。 */
function versioned(instance) {
  if (!instance || typeof instance !== 'object') return instance
  if (typeof instance.getNodeType === 'function') return instance.getNodeType() || instance
  const versions = instance.nodeVersions || (instance.name === 'VersionedNodeType' ? instance.args?.[0] : undefined)
  const base = instance.baseDescription || (instance.name === 'VersionedNodeType' ? instance.args?.[1] : undefined) || {}
  if (!versions || typeof versions !== 'object') return instance
  return versions[String(base.defaultVersion)] || versions[base.defaultVersion]
    || Object.entries(versions).sort(([left], [right]) => Number(right) - Number(left))[0]?.[1] || instance
}

/** 选择模块导出的第一个可构造实例并返回稳定描述。 */
function inspect(root, relative, kind) {
  const source = resolve(root, String(relative || ''))
  if (!source.startsWith(root + sep)) return { relative, kind, error: 'NODE_SOURCE_MISSING' }
  try {
    const module = require(source)
    let instance = null
    let exportName = ''
    for (const [name, value] of Object.entries(module || {})) {
      if (typeof value === 'function') {
        try { instance = versioned(new value()); exportName = name; break } catch { /* 尝试下一个导出。 */ }
      } else if (value && typeof value === 'object') {
        instance = versioned(value); exportName = ''; break
      }
    }
    if (!instance || typeof instance !== 'object') return { relative, kind, error: 'NODE_COMPONENT_CLASS_MISSING' }
    const methods = ['execute', 'trigger', 'webhook', 'poll', 'supplyData']
      .filter(name => typeof instance[name] === 'function')
    return {
      relative, kind, exportName,
      name: String(instance.name || ''),
      properties: jsonSafe(Array.isArray(instance.properties) ? instance.properties : []),
      authenticate: jsonSafe(instance.authenticate || {}),
      description: jsonSafe(instance.description || {}),
      methods,
    }
  } catch (error) {
    return { relative, kind, error: String(error?.code === 'MODULE_NOT_FOUND' ? 'DEPENDENCY_OR_ABI_MISSING' : error?.message || error).slice(0, 300) }
  }
}

/** 将函数钩子编码为不可执行标记，供宿主只做兼容性判断。 */
function jsonSafe(value) {
  if (typeof value === 'function') return { __baseAiFunctionHook: true }
  if (Array.isArray(value)) return value.map(jsonSafe)
  if (value && typeof value === 'object') return Object.fromEntries(Object.entries(value).map(([key, item]) => [key, jsonSafe(item)]))
  return value
}

try {
  const request = await readInput()
  const root = resolve(String(request.root || ''))
  const credentials = Array.isArray(request.credentials) ? request.credentials : []
  const nodes = Array.isArray(request.nodes) ? request.nodes : []
  const entries = [
    ...credentials.map(relative => inspect(root, relative, 'credential')),
    ...nodes.map(relative => inspect(root, relative, 'node')),
  ]
  process.stdout.write(JSON.stringify({ success: true, entries }))
} catch (error) {
  process.stdout.write(JSON.stringify({ success: false, error: String(error?.message || error).slice(0, 300) }))
  process.exitCode = 1
}
