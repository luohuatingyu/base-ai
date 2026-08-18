/** 安全保存并探测 n8n 插件包。 */

import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { copyFile, mkdir, mkdtemp, readFile, rename, rm, symlink, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, normalize, relative, resolve, sep } from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
const SHIM = resolve(dirname(new URL(import.meta.url).pathname), 'abi-shim.cjs')
const HOST_ABI_VERSION = 5

export class PackageError extends Error {}

export class PackageStore {
  /** 从环境读取插件目录和压缩包限制。 */
  constructor(root = process.env.PLUGIN_PACKAGE_ROOT || '/data/packages') {
    this.root = resolve(root)
    this.maximumArchive = Number(process.env.PLUGIN_MAX_PACKAGE_BYTES || 10 * 1024 * 1024)
    this.maximumFiles = Number(process.env.PLUGIN_MAX_PACKAGE_FILES || 2048)
    this.maximumUnpacked = Number(process.env.PLUGIN_MAX_UNPACKED_BYTES || 100 * 1024 * 1024)
    this.installTimeout = Number(process.env.PLUGIN_DEPENDENCY_INSTALL_TIMEOUT_SECONDS || 180) * 1000
  }

  /** 校验、解压并探测一个 npm tgz 插件包。 */
  async install(request) {
    const archive = this.#archive(request.archiveBase64)
    const fingerprint = createHash('sha256').update(archive).digest('hex')
    if (request.fingerprint && String(request.fingerprint).toLowerCase() !== fingerprint) {
      throw new PackageError('ARCHIVE_FINGERPRINT_MISMATCH')
    }
    await mkdir(this.root, { recursive: true })
    const target = join(this.root, fingerprint)
    const metadataFile = join(target, '.base-ai-metadata.json')
    if (existsSync(metadataFile)) {
      const cached = JSON.parse(await readFile(metadataFile, 'utf8'))
      const reasons = new Set((cached.components || []).map(item => item.compatibilityReason))
      if (cached.hostAbiVersion !== HOST_ABI_VERSION
          || reasons.size && [...reasons].every(reason => ['DEPENDENCY_INSTALL_FAILED', 'DEPENDENCY_INSTALL_TIMEOUT'].includes(reason))) {
        await rm(target, { recursive: true, force: true })
      } else return cached
    }
    const temporary = await mkdtemp(join(this.root, '.n8n-plugin-'))
    try {
      const archiveFile = join(temporary, 'package.tgz')
      await writeFile(archiveFile, archive)
      this.#extract(archiveFile, temporary)
      await rm(archiveFile)
      const packageRoot = join(temporary, 'package')
      const metadata = await this.#metadata(packageRoot, request, fingerprint)
      await writeFile(join(temporary, '.base-ai-metadata.json'), JSON.stringify(metadata), 'utf8')
      await rename(temporary, target)
      return metadata
    } catch (error) {
      await rm(temporary, { recursive: true, force: true })
      throw error
    }
  }

  /** 返回已安装插件目录与元数据。 */
  async metadata(fingerprint) {
    if (!/^[a-f0-9]{64}$/.test(String(fingerprint || ''))) throw new PackageError('PACKAGE_NOT_FOUND')
    const target = join(this.root, fingerprint)
    const metadataFile = join(target, '.base-ai-metadata.json')
    if (!existsSync(metadataFile)) throw new PackageError('PACKAGE_NOT_FOUND')
    return { root: join(target, 'package'), metadata: JSON.parse(await readFile(metadataFile, 'utf8')) }
  }

  /** 删除严格指纹对应的未引用缓存包；引用关系由后端数据库在调用前确认。 */
  async remove(fingerprint) {
    if (!/^[a-f0-9]{64}$/.test(String(fingerprint || ''))) throw new PackageError('PACKAGE_NOT_FOUND')
    const target = join(this.root, fingerprint)
    if (relative(this.root, target).startsWith('..')) throw new PackageError('PACKAGE_NOT_FOUND')
    await rm(target, { recursive: true, force: true })
    return { removed: true }
  }

  /** 读取并限制 Base64 压缩包。 */
  #archive(value) {
    if (typeof value !== 'string' || !/^[A-Za-z0-9+/]*={0,2}$/.test(value)) throw new PackageError('ARCHIVE_BASE64_INVALID')
    const archive = Buffer.from(value, 'base64')
    if (!archive.length || archive.length > this.maximumArchive) throw new PackageError('ARCHIVE_SIZE_INVALID')
    return archive
  }

  /** 使用固定参数 tar 校验路径、数量、声明体积后解压。 */
  #extract(archiveFile, target) {
    const listed = spawnSync('tar', ['-tzvf', archiveFile], { encoding: 'utf8', maxBuffer: 4 * 1024 * 1024 })
    if (listed.status !== 0) throw new PackageError('ARCHIVE_FORMAT_INVALID')
    const lines = listed.stdout.trim().split('\n').filter(Boolean)
    if (lines.length > this.maximumFiles) throw new PackageError('ARCHIVE_FILE_LIMIT')
    let total = 0
    for (const line of lines) {
      const parts = line.trim().split(/\s+/)
      const name = parts.at(-1) || ''
      const size = Number(parts[2] || 0)
      const safe = normalize(name).replaceAll('\\', '/')
      if (!safe.startsWith('package/') || safe.includes('../') || safe.startsWith('/')) throw new PackageError('ARCHIVE_PATH_INVALID')
      if (line.startsWith('l') || line.startsWith('h')) throw new PackageError('ARCHIVE_LINK_FORBIDDEN')
      total += Number.isFinite(size) ? size : 0
      if (total > this.maximumUnpacked) throw new PackageError('ARCHIVE_UNPACKED_LIMIT')
    }
    const extracted = spawnSync('tar', ['-xzf', archiveFile, '-C', target, '--no-same-owner', '--no-same-permissions'], { encoding: 'utf8' })
    if (extracted.status !== 0) throw new PackageError('ARCHIVE_FORMAT_INVALID')
  }

  /** 从 package.json 的 n8n 声明加载节点与凭据 Schema。 */
  async #metadata(packageRoot, request, fingerprint) {
    const packageFile = resolve(packageRoot, 'package.json')
    if (!packageFile.startsWith(packageRoot + sep) || !existsSync(packageFile)) throw new PackageError('PACKAGE_JSON_MISSING')
    const manifest = JSON.parse(await readFile(packageFile, 'utf8'))
    const declared = manifest.n8n && typeof manifest.n8n === 'object' ? manifest.n8n : {}
    const nodes = Array.isArray(declared.nodes) ? declared.nodes : []
    const credentials = Array.isArray(declared.credentials) ? declared.credentials : []
    if (!nodes.length && !credentials.length) throw new PackageError('PLUGIN_COMPONENTS_MISSING')
    let dependencyError = ''
    try { await this.#installDependencies(packageRoot, manifest.dependencies) } catch (error) {
      if (!(error instanceof PackageError)) throw error
      dependencyError = error.message
    }
    await this.#installShim(packageRoot)
    const probed = await this.#probe(packageRoot, credentials, nodes)
    const credentialSchemas = new Map()
    for (const entry of probed.filter(item => item.kind === 'credential' && !item.error)) {
      if (entry.name) credentialSchemas.set(entry.name, {
        schema: this.#fields(entry.properties || [], true), authenticate: entry.authenticate || {},
      })
    }
    const components = nodes.map(relative => this.#componentFromProbe(
      probed.find(item => item.kind === 'node' && item.relative === relative), credentialSchemas, dependencyError))
    if (!nodes.length) {
      for (const [name, credential] of credentialSchemas) components.push({
        externalId: name, name, description: '', componentType: 'EXTENSION', schema: [], credentialSchema: credential.schema,
        sourcePath: '', exportName: '', compatibilityStatus: 'PARTIAL', compatibilityReason: 'CREDENTIAL_ONLY_EXTENSION',
      })
    }
    const externalServices = this.#externalServices(components.flatMap(item => item.serviceCandidates || []))
    for (const component of components) delete component.serviceCandidates
    const license = this.#license(manifest.license)
    return {
      source: 'N8N', packageId: String(request.packageId || manifest.name || ''),
      version: String(request.version || manifest.version || ''), fingerprint, runtimeLanguage: 'node',
      hostAbiVersion: HOST_ABI_VERSION, licenseName: license.name, licenseUrl: license.url,
      externalServices, components,
    }
  }

  /** 只接受包清单明确声明的许可证名称与 HTTPS 地址。 */
  #license(raw) {
    const value = Array.isArray(raw) ? raw[0] : raw
    const name = typeof value === 'string' ? value : value && typeof value === 'object' ? value.type || value.name : ''
    const candidate = value && typeof value === 'object' ? value.url : ''
    let url = ''
    try {
      const parsed = new URL(String(candidate || ''))
      if (parsed.protocol === 'https:' && !parsed.username && !parsed.password) url = parsed.toString().slice(0, 500)
    } catch { /* 缺失或非法地址保持为空，等待人工确认。 */ }
    return { name: String(name || '').trim().slice(0, 160), url }
  }

  /** 从节点声明中的固定 HTTPS 字面量提取待人工确认的外部服务域名。 */
  #externalServices(values) {
    const domains = new Set()
    const visit = value => {
      if (typeof value === 'string') {
        for (const matched of value.matchAll(/https:\/\/[^\s"'<>)}\]]+/gi)) {
          try {
            const url = new URL(matched[0])
            if (url.hostname) domains.add(url.hostname.toLowerCase())
          } catch { /* 非完整 URL 不是可靠候选。 */ }
        }
      } else if (Array.isArray(value)) value.forEach(visit)
      else if (value && typeof value === 'object') Object.values(value).forEach(visit)
    }
    values.forEach(visit)
    return [...domains].sort().slice(0, 64).map(domain => ({ name: domain, domain }))
  }

  /** 在插件局部依赖目录安装 Base AI 自研同名 ABI 模块。 */
  async #installShim(packageRoot) {
    const target = join(packageRoot, 'node_modules', 'n8n-workflow')
    await mkdir(target, { recursive: true })
    await copyFile(SHIM, join(target, 'index.cjs'))
    await writeFile(join(target, 'package.json'), JSON.stringify({ name: 'n8n-workflow', version: '0.0.0-base-ai', main: 'index.cjs' }))
    const lodash = join(packageRoot, 'node_modules', 'lodash')
    const lodashSet = join(lodash, 'set.js')
    if (!existsSync(lodashSet)) {
      await mkdir(lodash, { recursive: true })
      await writeFile(lodashSet, `'use strict'
module.exports = function set(target, path, value) {
  if (!target || typeof target !== 'object') return target
  const parts = Array.isArray(path) ? path.map(String) : String(path || '').replace(/\\[([^\\]]+)\\]/g, '.$1').split('.').filter(Boolean)
  if (!parts.length || parts.some(part => ['__proto__', 'prototype', 'constructor'].includes(part))) return target
  let current = target
  for (let index = 0; index < parts.length - 1; index += 1) {
    const part = parts[index]
    if (!current[part] || typeof current[part] !== 'object') current[part] = {}
    current = current[part]
  }
  current[parts.at(-1)] = value
  return target
}
`)
      await writeFile(join(lodash, 'package.json'), JSON.stringify({ name: 'lodash', version: '0.0.0-base-ai' }))
    }
  }

  /** 在独立目录安装插件依赖，排除 n8n 引擎/SDK 并禁用生命周期脚本。 */
  async #installDependencies(packageRoot, declaredDependencies) {
    const dependencies = this.#safeDependencies(declaredDependencies)
    if (!dependencies.length) return
    const dependencyRoot = join(packageRoot, '.base-ai-deps')
    await mkdir(dependencyRoot, { recursive: true })
    await writeFile(join(dependencyRoot, 'package.json'), JSON.stringify({ private: true }))
    const result = spawnSync('npm', [
      'install', '--prefix', dependencyRoot, '--ignore-scripts', '--omit=dev', '--no-audit', '--no-fund',
      '--package-lock=true', '--save-exact', ...dependencies.map(item => `${item.name}@${item.version}`),
    ], {
      encoding: 'utf8', timeout: Math.max(10000, Math.min(this.installTimeout, 600000)),
      env: { PATH: process.env.PATH || '', HOME: '/data/tmp', npm_config_cache: '/data/tmp/npm-cache', LANG: 'C.UTF-8' },
      maxBuffer: 1024 * 1024,
    })
    if (result.error?.code === 'ETIMEDOUT') throw new PackageError('DEPENDENCY_INSTALL_TIMEOUT')
    if (result.status !== 0) throw new PackageError('DEPENDENCY_INSTALL_FAILED')
    for (const dependency of dependencies) {
      const source = join(dependencyRoot, 'node_modules', ...dependency.name.split('/'))
      const destination = join(packageRoot, 'node_modules', ...dependency.name.split('/'))
      await mkdir(dirname(destination), { recursive: true })
      await symlink(relative(dirname(destination), source), destination, 'dir')
    }
  }

  /** 仅接受 npm 注册表包名和精确版本，禁止引擎、SDK、路径、URL 与范围来源。 */
  #safeDependencies(value) {
    if (!value || typeof value !== 'object' || Array.isArray(value)) return []
    const entries = []
    for (const [name, rawVersion] of Object.entries(value)) {
      const normalized = String(name).toLowerCase()
      if (['n8n', 'n8n-core', 'n8n-workflow'].includes(normalized) || normalized.startsWith('@n8n/')) continue
      if (!/^(?:@[a-z0-9][a-z0-9._-]*\/)?[a-z0-9][a-z0-9._-]*$/i.test(name)) {
        throw new PackageError('DEPENDENCY_DECLARATION_INVALID')
      }
      const version = String(rawVersion || '').trim()
      if (!/^\d+\.\d+\.\d+(?:-[0-9A-Za-z.-]+)?$/.test(version)) throw new PackageError('DEPENDENCY_VERSION_NOT_PINNED')
      entries.push({ name, version })
      if (entries.length > 128) throw new PackageError('DEPENDENCY_COUNT_INVALID')
    }
    return entries
  }

  /** 在无内部令牌的短生命周期子进程中探测插件模块。 */
  #probe(packageRoot, credentials, nodes) {
    return new Promise((resolvePromise, rejectPromise) => {
      const child = spawn(process.execPath, [resolve(dirname(new URL(import.meta.url).pathname), 'probe-child.mjs')], {
        env: { PATH: process.env.PATH || '', NODE_ENV: 'production', LANG: 'C.UTF-8' },
        stdio: ['pipe', 'pipe', 'pipe'],
      })
      let stdout = ''; let stderr = ''
      const timer = setTimeout(() => { child.kill('SIGKILL'); rejectPromise(new PackageError('PLUGIN_PROBE_TIMEOUT')) }, this.installTimeout)
      child.stdout.on('data', chunk => { stdout += chunk; if (stdout.length > 4 * 1024 * 1024) child.kill('SIGKILL') })
      child.stderr.on('data', chunk => { stderr += chunk; if (stderr.length > 4096) child.kill('SIGKILL') })
      child.on('error', rejectPromise)
      child.on('close', code => {
        clearTimeout(timer)
        try {
          const result = JSON.parse(stdout)
          if (code !== 0 || !result.success) rejectPromise(new PackageError(result.error || 'PLUGIN_PROBE_FAILED'))
          else resolvePromise(Array.isArray(result.entries) ? result.entries : [])
        } catch { rejectPromise(new PackageError('PLUGIN_PROBE_OUTPUT_INVALID')) }
      })
      child.stdin.end(JSON.stringify({ root: packageRoot, credentials, nodes }))
    })
  }

  /** 根据隔离探测结果规范化节点描述和兼容状态。 */
  #componentFromProbe(probe, credentialSchemas, dependencyError = '') {
    const relative = String(probe?.relative || '')
    if (!probe || probe.error) return {
      externalId: relative, name: relative, description: '', componentType: 'ACTION', schema: [],
      credentialSchema: [], sourcePath: relative, exportName: '', compatibilityStatus: 'UNSUPPORTED',
      compatibilityReason: dependencyError || probe?.error || 'NODE_SOURCE_MISSING',
    }
    const description = probe.description || {}
    const externalId = String(description.name || relative)
    const credentialEntries = (description.credentials || []).map(item => credentialSchemas.get(item.name)).filter(Boolean)
    const credentialSchema = credentialEntries.flatMap(item => item.schema)
    const componentType = this.#typeFromProbe(probe.methods || [], description)
    const executable = !dependencyError && this.#executableFromProbe(probe.methods || [], componentType, description)
    return {
      externalId, name: String(description.displayName || externalId), description: String(description.description || ''),
      componentType, schema: this.#fields(description.properties || []), credentialSchema,
      sourcePath: relative, exportName: String(probe.exportName || ''),
      credentialAuthentications: credentialEntries.map(item => item.authenticate),
      serviceCandidates: [description, ...credentialEntries.map(item => item.authenticate)],
      compatibilityStatus: executable ? 'SUPPORTED' : 'PARTIAL',
      compatibilityReason: executable ? '' : dependencyError || (description.requestDefaults
        ? 'DECLARATIVE_ROUTING_NOT_IMPLEMENTED' : 'NODE_EXECUTION_METHOD_MISSING'),
    }
  }

  /** 根据隔离探测得到的方法集合判断节点类型。 */
  #typeFromProbe(methods, description) {
    const has = name => methods.includes(name)
    const explicit = String(description.baseAiComponentType || '').toUpperCase()
    if (['ACTION', 'TRIGGER', 'MODEL', 'DATASOURCE', 'AGENT_STRATEGY', 'EXTENSION'].includes(explicit)) return explicit
    if (has('trigger') || has('webhook') || has('poll') || description.polling || Array.isArray(description.webhooks)) return 'TRIGGER'
    const outputs = JSON.stringify(description.outputs || []).toLowerCase()
    if (outputs.includes('language') || outputs.includes('embedding') || outputs.includes('model')) return 'MODEL'
    if (outputs.includes('agent')) return 'AGENT_STRATEGY'
    if (outputs.includes('retriever') || outputs.includes('vectorstore')) return 'DATASOURCE'
    return 'ACTION'
  }

  /** 根据隔离探测得到的方法集合判断节点是否可执行。 */
  #executableFromProbe(methods, componentType, description) {
    const has = name => methods.includes(name)
    if (componentType === 'ACTION') return has('execute') || this.#declarative(description)
    if (componentType === 'TRIGGER') return ['trigger', 'webhook', 'poll'].some(has)
    if (['MODEL', 'DATASOURCE', 'AGENT_STRATEGY'].includes(componentType)) return has('supplyData') || has('execute')
    return has('execute')
  }

  /** 判断节点声明是否至少包含一条可安全解释的 HTTP 路由。 */
  #declarative(description) {
    if (!description?.requestDefaults || !Array.isArray(description.properties)) return false
    if (!this.#declarativeValue(description.requestDefaults)) return false
    let routed = false
    const visit = properties => (Array.isArray(properties) ? properties : []).every(property => {
      const routes = [property?.routing, ...(Array.isArray(property?.options)
        ? property.options.map(option => option?.routing) : [])].filter(Boolean)
      for (const routing of routes) {
        if (routing.request) routed = true
        if (!this.#routing(routing)) return false
      }
      const optionChildren = (Array.isArray(property?.options) ? property.options : [])
        .flatMap(option => [...(Array.isArray(option?.values) ? option.values : []),
          ...(Array.isArray(option?.options) ? option.options : [])])
      return (!Array.isArray(property?.options) || visit(property.options))
        && (!Array.isArray(property?.values) || visit(property.values)) && visit(optionChildren)
    })
    return visit(description.properties) && routed
  }

  /** 判断声明式路由只包含宿主可安全解释的字段和函数 hook。 */
  #routing(routing) {
    if (!routing || typeof routing !== 'object') return true
    if (routing.request && !this.#declarativeValue(routing.request)) return false
    if (routing.send) {
      const { preSend, ...send } = routing.send
      if (preSend !== undefined && (!Array.isArray(preSend) || preSend.some(hook => !this.#isSafeHook(hook)))) return false
      if (!this.#declarativeValue(send)) return false
    }
    const postReceive = routing.output?.postReceive
    if (postReceive !== undefined
      && (!Array.isArray(postReceive) || postReceive.some(hook => !this.#isSafeHook(hook)
        && (!['rootProperty', 'setKeyValue'].includes(hook?.type) || !this.#declarativeValue(hook))))) return false
    return true
  }

  /** 识别隔离探测阶段编码的函数钩子，不在宿主进程执行。 */
  #isSafeHook(value) { return typeof value === 'function' || value?.__baseAiFunctionHook === true }

  /** 静态确认路由值不要求执行任意 JavaScript 表达式。 */
  #declarativeValue(value) {
    if (Array.isArray(value)) return value.every(item => this.#declarativeValue(item))
    if (value && typeof value === 'object') return Object.values(value).every(item => this.#declarativeValue(item))
    if (typeof value !== 'string' || !value.includes('{{') && !value.startsWith('=')) return true
    if (/^=[^{}]*$/.test(value)) return true
    const exact = value.match(/^=\{\{\s*(.*)\s*\}\}$/s)
    if (exact) return this.#declarativeExpression(exact[1])
    const expressions = [...value.matchAll(/\{\{\s*(.*?)\s*\}\}/gs)]
    const outside = value.replace(/\{\{\s*.*?\s*\}\}/gs, '')
    if (!expressions.length || outside.includes('{') || outside.includes('}')) return false
    return expressions.every(match => this.#declarativeExpression(match[1]))
  }

  /** 白名单校验声明式表达式的引用、转换函数和运算符。 */
  #declarativeExpression(expression) {
    const remaining = String(expression || '')
      .replace(/\$(?:parameter|credentials|responseItem)(?:\["[^"]+"\]|\.[A-Za-z0-9_.-]+)/g, '')
      .replace(/\$value/g, '')
      .replace(/"(?:\\.|[^"\\])*"|'(?:\\.|[^'\\])*'/g, '')
      .replace(/\/\\\.\\d\{3\}Z\$\//g, '')
      .replace(/\b(?:JSON\.parse|Number|isNaN|Math\.floor)\b/g, '')
      .replace(/\bnew\s+Date\b/g, '')
      .replace(/\.(?:includes|join|split|getTime|toISOString|slice|replace|length)\b/g, '')
      .replace(/\b(?:true|false|null|undefined)\b/g, '')
      .replace(/\{\}/g, '')
      .replace(/[0-9.\s+?:|&!=<>()[\],\/-]/g, '')
    return remaining === ''
  }

  /** 把 n8n 属性定义转换为受控动态字段。 */
  #fields(properties, credential = false) {
    return (Array.isArray(properties) ? properties : []).filter(item => item && typeof item === 'object').map(item => ({
      name: String(item.name || ''), label: String(item.displayName || item.name || ''),
      description: String(item.description || ''), type: String(item.type || 'string'),
      required: Boolean(item.required) || credential && item.required !== false && item.type !== 'hidden',
      default: item.default, options: Array.isArray(item.options) ? item.options : [],
      displayOptions: item.displayOptions || {}, secret: Boolean(item.typeOptions?.password),
    }))
  }
}
