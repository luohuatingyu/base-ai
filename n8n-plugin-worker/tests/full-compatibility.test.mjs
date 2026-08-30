/** 验证真实市场包所需的版本包装、声明式表达式和最小兼容依赖。 */

import assert from 'node:assert/strict'
import { createServer } from 'node:http'
import { mkdtemp, mkdir, readFile, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { PackageStore } from '../app/package-store.mjs'

/** 把测试包写成 npm tgz，并返回可安装的归档。 */
async function packageFixture(files, manifest) {
  const root = await mkdtemp(join(tmpdir(), 'base-ai-n8n-full-compatibility-'))
  const packageRoot = join(root, 'package')
  await mkdir(packageRoot, { recursive: true })
  for (const [relative, content] of Object.entries(files)) {
    const target = join(packageRoot, relative)
    await mkdir(join(target, '..'), { recursive: true })
    await writeFile(target, content)
  }
  await writeFile(join(packageRoot, 'package.json'), JSON.stringify(manifest))
  const archive = join(root, 'fixture.tgz')
  const packed = spawnSync('tar', ['-czf', archive, '-C', root, 'package'])
  assert.equal(packed.status, 0, packed.stderr?.toString())
  return { root, archive }
}

/** 在真实调用子进程中执行已安装的组件。 */
async function invokeChild(request, environment = {}) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../app/invoke-child.mjs', import.meta.url))], {
      stdio: ['pipe', 'pipe', 'pipe'], env: { ...process.env, ...environment },
    })
    let stdout = ''; let stderr = ''
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => { stderr += chunk })
    child.on('error', reject)
    child.on('close', status => resolve({ status, stdout, stderr }))
    child.stdin.end(JSON.stringify(request))
  })
}

/** 安装测试包并返回组件及持久化调用信息。 */
async function installFixture(fixture, storeRoot) {
  const store = new PackageStore(storeRoot)
  const bytes = await readFile(fixture.archive)
  const metadata = await store.install({ archiveBase64: bytes.toString('base64') })
  const installed = await store.metadata(metadata.fingerprint)
  return { metadata, installed }
}

test('版本化节点选择默认实现并完成真实调用', async () => {
  const fixture = await packageFixture({
    'nodes/Versioned.node.cjs': `
const { VersionedNodeType } = require('n8n-workflow')
class VersionOne {
  constructor(base) { this.description = { ...base, name: 'versioned', version: 1, properties: [] } }
  async execute() { return [[{ json: { version: 1 } }]] }
}
class VersionTwo {
  constructor(base) { this.description = { ...base, name: 'versioned', version: 2, properties: [] } }
  async execute() { return [[{ json: { version: 2 } }]] }
}
class Versioned extends VersionedNodeType {
  constructor() {
    const base = { displayName: 'Versioned', name: 'versioned', defaultVersion: 2 }
    super({ 1: new VersionOne(base), 2: new VersionTwo(base) }, base)
  }
}
module.exports = { Versioned }
`,
  }, { name: 'n8n-nodes-versioned-fixture', version: '1.0.0', n8n: { nodes: ['nodes/Versioned.node.cjs'] } })
  const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    const { metadata, installed } = await installFixture(fixture, storeRoot)
    const component = metadata.components[0]
    assert.equal(component.compatibilityStatus, 'SUPPORTED', component.compatibilityReason)
    assert.equal(component.externalId, 'versioned')
    const invoked = await invokeChild({ root: installed.root, sourcePath: component.sourcePath,
      exportName: component.exportName, componentId: component.externalId, operation: 'invoke',
      parameters: {}, credentials: {}, input: {}, context: {} })
    assert.equal(invoked.status, 0, invoked.stdout + invoked.stderr)
    assert.equal(JSON.parse(invoked.stdout).output[0][0].json.version, 2)
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
    await rm(storeRoot, { recursive: true, force: true })
  }
})

test('声明式节点安全执行数组数字日期与混合模板表达式', async () => {
  const server = createServer((request, response) => {
    const url = new URL(request.url, 'http://127.0.0.1')
    response.setHeader('content-type', 'application/json')
    response.end(JSON.stringify({ path: url.pathname, query: Object.fromEntries(
      [...url.searchParams.keys()].map(name => [name, url.searchParams.getAll(name)])) }))
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  const fixture = await packageFixture({
    'nodes/Declarative.node.cjs': `
class Declarative {
  constructor() {
    this.description = {
      name: 'declarativeFull', displayName: 'Declarative Full',
      requestDefaults: { baseURL: '={{$parameter.baseUrl}}', method: 'GET' },
      properties: [
        { name: 'operation', type: 'options', default: 'point', options: [{ name: 'Point', value: 'point',
          routing: { request: { url: '=/collections/{{$parameter.collection}}/points/{{isNaN(Number($parameter.id)) ? $parameter.id : Number($parameter.id)}}' } } }] },
        { name: 'tags', type: 'string', default: [], routing: { request: { qs: { tags: '={{$value.join(",")}}' } } } },
        { name: 'words', type: 'string', default: '', routing: { request: { qs: { words: '={{$value ? $value.split(",") : undefined}}' } } } },
        { name: 'date', type: 'string', default: '', routing: { request: { qs: { date: '={{$value ? new Date($value).toISOString().slice(0, 10) : ""}}' } } } },
        { name: 'timestamp', type: 'string', default: '', routing: { request: { qs: { timestamp: '={{$value ? Math.floor(new Date($value).getTime() / 1000) : undefined}}' } } } },
        { name: 'filterJson', type: 'string', default: '', routing: { request: { qs: '={{$value ? JSON.parse($value) : {}}}' } } },
      ],
    }
  }
}
module.exports = { Declarative }
`,
  }, { name: 'n8n-nodes-expression-fixture', version: '1.0.0', n8n: { nodes: ['nodes/Declarative.node.cjs'] } })
  const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    const { metadata, installed } = await installFixture(fixture, storeRoot)
    const component = metadata.components[0]
    assert.equal(component.compatibilityStatus, 'SUPPORTED', component.compatibilityReason)
    const invoked = await invokeChild({ root: installed.root, sourcePath: component.sourcePath,
      exportName: component.exportName, componentId: component.externalId, operation: 'invoke',
      parameters: { baseUrl: `http://127.0.0.1:${server.address().port}`, operation: 'point', collection: 'demo', id: '42',
        tags: ['one', 'two'], words: 'alpha,beta', date: '2026-08-11T15:30:00.000Z',
        timestamp: '2026-08-11T00:00:00.000Z', filterJson: '{"limit":10}' },
      credentials: {}, input: {}, context: {} }, {
      HTTP_PROXY: `http://test-token@127.0.0.1:${server.address().port}`,
    })
    assert.equal(invoked.status, 0, invoked.stdout + invoked.stderr)
    const output = JSON.parse(invoked.stdout).output[0][0].json
    assert.equal(output.path, '/collections/demo/points/42')
    assert.deepEqual(output.query.tags, ['one,two'])
    assert.deepEqual(output.query.words, ['alpha', 'beta'])
    assert.deepEqual(output.query.date, ['2026-08-11'])
    assert.deepEqual(output.query.timestamp, [String(Date.parse('2026-08-11T00:00:00.000Z') / 1000)])
    assert.deepEqual(output.query.limit, ['10'])
  } finally {
    await new Promise(resolve => server.close(resolve))
    await rm(fixture.root, { recursive: true, force: true })
    await rm(storeRoot, { recursive: true, force: true })
  }
})

test('未声明 lodash 依赖的节点使用防原型污染的本地 set 兼容层', async () => {
  const fixture = await packageFixture({
    'nodes/Lodash.node.cjs': `
const set = require('lodash/set')
class LodashNode {
  constructor() { this.description = { name: 'lodashNode', displayName: 'Lodash Node', properties: [] } }
  async execute() {
    const result = {}
    set(result, 'context.itemIndex', 7)
    set(result, '__proto__.polluted', true)
    return [[{ json: result }]]
  }
}
module.exports = { LodashNode }
`,
  }, { name: 'n8n-nodes-lodash-fixture', version: '1.0.0', n8n: { nodes: ['nodes/Lodash.node.cjs'] } })
  const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    const { metadata, installed } = await installFixture(fixture, storeRoot)
    const component = metadata.components[0]
    assert.equal(component.compatibilityStatus, 'SUPPORTED', component.compatibilityReason)
    const invoked = await invokeChild({ root: installed.root, sourcePath: component.sourcePath,
      exportName: component.exportName, componentId: component.externalId, operation: 'invoke',
      parameters: {}, credentials: {}, input: {}, context: {} })
    assert.equal(invoked.status, 0, invoked.stdout + invoked.stderr)
    const output = JSON.parse(invoked.stdout).output[0][0].json
    assert.equal(output.context.itemIndex, 7)
    assert.equal(output.polluted, undefined)
  } finally {
    await rm(fixture.root, { recursive: true, force: true })
    await rm(storeRoot, { recursive: true, force: true })
  }
})
