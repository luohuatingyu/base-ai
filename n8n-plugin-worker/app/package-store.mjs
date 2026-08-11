/** 安全保存并探测 n8n 插件包。 */

import { createHash } from 'node:crypto'
import { existsSync } from 'node:fs'
import { copyFile, mkdir, mkdtemp, readFile, rename, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { dirname, join, normalize, resolve, sep } from 'node:path'
import { spawnSync } from 'node:child_process'
import { createRequire } from 'node:module'

const require = createRequire(import.meta.url)
const SHIM = resolve(dirname(new URL(import.meta.url).pathname), 'abi-shim.cjs')

export class PackageError extends Error {}

export class PackageStore {
  /** 从环境读取插件目录和压缩包限制。 */
  constructor(root = process.env.PLUGIN_PACKAGE_ROOT || '/data/packages') {
    this.root = resolve(root)
    this.maximumArchive = Number(process.env.PLUGIN_MAX_PACKAGE_BYTES || 10 * 1024 * 1024)
    this.maximumFiles = Number(process.env.PLUGIN_MAX_PACKAGE_FILES || 2048)
    this.maximumUnpacked = Number(process.env.PLUGIN_MAX_UNPACKED_BYTES || 100 * 1024 * 1024)
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
    if (existsSync(metadataFile)) return JSON.parse(await readFile(metadataFile, 'utf8'))
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
    await this.#installShim(packageRoot)
    const credentialSchemas = new Map()
    for (const relative of credentials) {
      const loaded = await this.#load(packageRoot, relative)
      if (loaded.error) continue
      const instance = this.#instance(loaded.module)
      if (instance?.name) credentialSchemas.set(instance.name, this.#fields(instance.properties || []))
    }
    const components = []
    for (const relative of nodes) components.push(await this.#component(packageRoot, relative, credentialSchemas))
    if (!nodes.length) {
      for (const [name, schema] of credentialSchemas) components.push({
        externalId: name, name, description: '', componentType: 'EXTENSION', schema: [], credentialSchema: schema,
        sourcePath: '', exportName: '', compatibilityStatus: 'PARTIAL', compatibilityReason: 'CREDENTIAL_ONLY_EXTENSION',
      })
    }
    return {
      source: 'N8N', packageId: String(request.packageId || manifest.name || ''),
      version: String(request.version || manifest.version || ''), fingerprint, runtimeLanguage: 'node', components,
    }
  }

  /** 在插件局部依赖目录安装 Base AI 自研同名 ABI 模块。 */
  async #installShim(packageRoot) {
    const target = join(packageRoot, 'node_modules', 'n8n-workflow')
    await mkdir(target, { recursive: true })
    await copyFile(SHIM, join(target, 'index.cjs'))
    await writeFile(join(target, 'package.json'), JSON.stringify({ name: 'n8n-workflow', version: '0.0.0-base-ai', main: 'index.cjs' }))
  }

  /** 加载受限包内模块并收敛缺失依赖错误。 */
  async #load(packageRoot, relative) {
    const source = resolve(packageRoot, String(relative || ''))
    if (!source.startsWith(packageRoot + sep) || !existsSync(source)) return { error: 'NODE_SOURCE_MISSING' }
    try {
      delete require.cache[source]
      return { module: require(source), source }
    } catch (error) {
      return { error: String(error?.code === 'MODULE_NOT_FOUND' ? 'DEPENDENCY_OR_ABI_MISSING' : error?.message || error).slice(0, 300), source }
    }
  }

  /** 选择模块导出的第一个节点或凭据实例。 */
  #instance(module) {
    for (const value of Object.values(module || {})) {
      if (typeof value === 'function') {
        try { return new value() } catch { continue }
      }
      if (value && typeof value === 'object') return value
    }
    return null
  }

  /** 规范化节点描述和兼容状态。 */
  async #component(packageRoot, relative, credentialSchemas) {
    const loaded = await this.#load(packageRoot, relative)
    if (loaded.error) return {
      externalId: String(relative), name: String(relative), description: '', componentType: 'ACTION', schema: [],
      credentialSchema: [], sourcePath: String(relative), exportName: '', compatibilityStatus: 'UNSUPPORTED',
      compatibilityReason: loaded.error,
    }
    const instance = this.#instance(loaded.module)
    const description = instance?.description || {}
    const externalId = String(description.name || relative)
    const credentialSchema = (description.credentials || []).flatMap(item => credentialSchemas.get(item.name) || [])
    const componentType = this.#type(instance, description)
    const executable = this.#executable(instance, componentType, description)
    return {
      externalId, name: String(description.displayName || externalId), description: String(description.description || ''),
      componentType, schema: this.#fields(description.properties || []), credentialSchema,
      sourcePath: String(relative), exportName: this.#exportName(loaded.module, instance),
      compatibilityStatus: executable ? 'SUPPORTED' : 'PARTIAL',
      compatibilityReason: executable ? '' : description.requestDefaults ? 'DECLARATIVE_ROUTING_NOT_IMPLEMENTED' : 'NODE_EXECUTION_METHOD_MISSING',
    }
  }

  /** 根据声明和实现方法归类插件组件。 */
  #type(instance, description) {
    const explicit = String(description.baseAiComponentType || '').toUpperCase()
    if (['ACTION', 'TRIGGER', 'MODEL', 'DATASOURCE', 'AGENT_STRATEGY', 'EXTENSION'].includes(explicit)) return explicit
    if (typeof instance?.trigger === 'function' || typeof instance?.webhook === 'function' || typeof instance?.poll === 'function'
        || description.polling || Array.isArray(description.webhooks)) return 'TRIGGER'
    const outputs = JSON.stringify(description.outputs || []).toLowerCase()
    if (outputs.includes('language') || outputs.includes('embedding') || outputs.includes('model')) return 'MODEL'
    if (outputs.includes('agent')) return 'AGENT_STRATEGY'
    if (outputs.includes('retriever') || outputs.includes('vectorstore')) return 'DATASOURCE'
    return 'ACTION'
  }

  /** 判断当前宿主是否具备组件需要的入口方法。 */
  #executable(instance, componentType, description) {
    if (componentType === 'ACTION') return typeof instance?.execute === 'function' || Boolean(description.requestDefaults)
    if (componentType === 'TRIGGER') return ['trigger', 'webhook', 'poll'].some(name => typeof instance?.[name] === 'function')
    if (['MODEL', 'DATASOURCE', 'AGENT_STRATEGY'].includes(componentType)) return typeof instance?.supplyData === 'function' || typeof instance?.execute === 'function'
    return typeof instance?.execute === 'function'
  }

  /** 查找实例对应的导出名称。 */
  #exportName(module, instance) {
    return Object.entries(module || {}).find(([, value]) => typeof value === 'function' && instance instanceof value)?.[0] || ''
  }

  /** 把 n8n 属性定义转换为受控动态字段。 */
  #fields(properties) {
    return (Array.isArray(properties) ? properties : []).filter(item => item && typeof item === 'object').map(item => ({
      name: String(item.name || ''), label: String(item.displayName || item.name || ''),
      description: String(item.description || ''), type: String(item.type || 'string'),
      required: Boolean(item.required), default: item.default, options: Array.isArray(item.options) ? item.options : [],
      displayOptions: item.displayOptions || {}, secret: Boolean(item.typeOptions?.password),
    }))
  }
}
