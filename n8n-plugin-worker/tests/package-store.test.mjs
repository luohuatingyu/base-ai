/** 验证自研 n8n ABI 宿主的声明解析和安全边界。 */

import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawn, spawnSync } from 'node:child_process'
import { createServer } from 'node:http'
import { fileURLToPath } from 'node:url'
import test from 'node:test'
import { PackageError, PackageStore } from '../app/package-store.mjs'

/** 异步执行调用子进程，使测试 HTTP 服务仍可处理请求。 */
async function invokeChild(request) {
  return new Promise((resolve, reject) => {
    const child = spawn(process.execPath, [fileURLToPath(new URL('../app/invoke-child.mjs', import.meta.url))], {
      stdio: ['pipe', 'pipe', 'pipe'],
    })
    let stdout = ''; let stderr = ''
    child.stdout.on('data', chunk => { stdout += chunk })
    child.stderr.on('data', chunk => { stderr += chunk })
    child.on('error', reject)
    child.on('close', status => resolve({ status, stdout, stderr }))
    child.stdin.end(JSON.stringify(request))
  })
}

/** 构造不包含第三方代码的正式契约测试包。 */
async function fixture() {
  const root = await mkdtemp(join(tmpdir(), 'base-ai-n8n-fixture-'))
  const packageRoot = join(root, 'package')
  await mkdir(join(packageRoot, 'nodes'), { recursive: true })
  const types = ['ACTION', 'TRIGGER', 'MODEL', 'DATASOURCE', 'AGENT_STRATEGY', 'EXTENSION']
  const nodes = []
  for (const type of types) {
    const file = `nodes/${type}.node.cjs`; nodes.push(file)
    const method = type === 'TRIGGER' ? 'trigger' : ['MODEL', 'DATASOURCE', 'AGENT_STRATEGY'].includes(type) ? 'supplyData' : 'execute'
    await writeFile(join(packageRoot, file), `const { NodeConnectionType }=require('n8n-workflow'); class Fixture { constructor(){this.description={name:'${type.toLowerCase()}',displayName:'${type}',baseAiComponentType:'${type}',inputs:[NodeConnectionType.Main],outputs:[NodeConnectionType.Main],properties:[{name:'value',displayName:'Value',type:'string',required:true}]}} async ${method}(){return [[{json:{ok:true}}]]}} module.exports={Fixture}`)
  }
  await writeFile(join(packageRoot, 'package.json'), JSON.stringify({ name: 'n8n-nodes-base-ai-fixture', version: '1.0.0',
    license: { type: 'MIT', url: 'https://licenses.example.com/mit' }, n8n: { nodes } }))
  const archive = join(root, 'fixture.tgz')
  const packed = spawnSync('tar', ['-czf', archive, '-C', root, 'package'])
  assert.equal(packed.status, 0)
  return { root, archive }
}

test('探测全部组件类型且不安装 n8n SDK', async () => {
  const item = await fixture(); const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    const store = new PackageStore(storeRoot)
    const bytes = await (await import('node:fs/promises')).readFile(item.archive)
    const result = await store.install({ packageId: 'fixture', version: '1', archiveBase64: bytes.toString('base64') })
    assert.deepEqual(new Set(result.components.map(component => component.componentType)),
      new Set(['ACTION', 'TRIGGER', 'MODEL', 'DATASOURCE', 'AGENT_STRATEGY', 'EXTENSION']))
    assert.ok(result.components.every(component => component.compatibilityStatus === 'SUPPORTED'))
    assert.equal(result.licenseName, 'MIT')
    assert.equal(result.licenseUrl, 'https://licenses.example.com/mit')
    const installed = await store.metadata(result.fingerprint)
    for (const component of result.components) {
      const operation = component.componentType === 'TRIGGER' ? 'subscribe' : 'invoke'
      const invoked = spawnSync(process.execPath, [fileURLToPath(new URL('../app/invoke-child.mjs', import.meta.url))], {
        input: JSON.stringify({ root: installed.root, sourcePath: component.sourcePath, exportName: component.exportName,
          componentId: component.externalId, operation, parameters: {}, credentials: {}, input: {}, context: {} }),
        encoding: 'utf8',
      })
      assert.equal(invoked.status, 0, invoked.stdout)
      assert.equal(JSON.parse(invoked.stdout).success, true)
    }
  } finally { await rm(item.root, { recursive: true, force: true }); await rm(storeRoot, { recursive: true, force: true }) }
})

test('拒绝摘要不匹配和路径穿越', async () => {
  const item = await fixture(); const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    const store = new PackageStore(storeRoot)
    const bytes = await (await import('node:fs/promises')).readFile(item.archive)
    await assert.rejects(() => store.install({ archiveBase64: bytes.toString('base64'), fingerprint: '0'.repeat(64) }),
      error => error instanceof PackageError && error.message === 'ARCHIVE_FINGERPRINT_MISMATCH')
    const badRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-bad-'))
    const badArchive = join(badRoot, 'bad.tgz')
    await writeFile(join(badRoot, 'escape'), 'x')
    const packed = spawnSync('tar', ['-czf', badArchive, '--transform=s|escape|package/../escape|', '-C', badRoot, 'escape'])
    if (packed.status === 0) {
      const bad = await (await import('node:fs/promises')).readFile(badArchive)
      await assert.rejects(() => store.install({ archiveBase64: bad.toString('base64') }), /ARCHIVE_PATH_INVALID/)
    }
    await rm(badRoot, { recursive: true, force: true })
  } finally { await rm(item.root, { recursive: true, force: true }); await rm(storeRoot, { recursive: true, force: true }) }
})

test('声明式节点不得在尚未执行其路由时误报完全兼容', async () => {
  const root = await mkdtemp(join(tmpdir(), 'base-ai-n8n-declarative-'))
  const packageRoot = join(root, 'package'); const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    await mkdir(join(packageRoot, 'nodes'), { recursive: true })
    await writeFile(join(packageRoot, 'nodes/Declarative.node.cjs'),
      "class Declarative { constructor(){ this.description={name:'declarative',displayName:'Declarative',requestDefaults:{baseURL:'https://example.com'},properties:[]} } } module.exports={Declarative}")
    await writeFile(join(packageRoot, 'package.json'), JSON.stringify({
      name: 'n8n-nodes-declarative', version: '1.0.0', dependencies: { 'n8n-workflow': '^1.0.0' },
      n8n: { nodes: ['nodes/Declarative.node.cjs'] },
    }))
    const packed = spawnSync('tar', ['-czf', join(root, 'fixture.tgz'), '-C', root, 'package'])
    assert.equal(packed.status, 0)
    const bytes = await (await import('node:fs/promises')).readFile(join(root, 'fixture.tgz'))
    const result = await new PackageStore(storeRoot).install({ archiveBase64: bytes.toString('base64') })
    assert.equal(result.components[0].compatibilityStatus, 'PARTIAL')
    assert.equal(result.components[0].compatibilityReason, 'DECLARATIVE_ROUTING_NOT_IMPLEMENTED')
    assert.deepEqual(result.externalServices, [{ name: 'example.com', domain: 'example.com' }])
  } finally { await rm(root, { recursive: true, force: true }); await rm(storeRoot, { recursive: true, force: true }) }
})

test('声明式节点安全执行 preSend 与 postReceive 路由', async () => {
  const root = await mkdtemp(join(tmpdir(), 'base-ai-n8n-routing-'))
  const packageRoot = join(root, 'package'); const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  const server = createServer((request, response) => {
    let body = ''
    request.on('data', chunk => { body += chunk })
    request.on('end', () => {
      if (request.url === '/binary') {
        response.setHeader('content-type', 'application/octet-stream')
        response.end(Buffer.from([0, 255, 1]))
        return
      }
      response.setHeader('content-type', 'application/json')
      response.end(JSON.stringify({ method: request.method, url: request.url, header: request.headers['x-hook'],
        body: JSON.parse(body || '{}') }))
    })
  })
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve))
  try {
    await mkdir(join(packageRoot, 'nodes'), { recursive: true })
    await writeFile(join(packageRoot, 'nodes/Declarative.node.cjs'), `
class Declarative {
  constructor() {
    this.description = {
      name: 'declarative', displayName: 'Declarative', requestDefaults: { baseURL: '={{$parameter.baseUrl}}' },
      properties: [
        { name: 'operation', type: 'options', default: 'create', options: [{ name: 'Create', value: 'create', routing: {
          request: { method: 'POST', url: '/items' },
          send: { preSend: [async function(options) { options.headers = { ...(options.headers || {}), 'x-hook': 'before' };
            options.body = { ...(options.body || {}), prepared: true }; return options }] },
          output: { postReceive: [async function(items) { return items.map(item => ({ json: { ...item.json, after: true } })) },
            { type: 'setKeyValue', enabled: '={{$parameter["simplify"]}}', properties: {
              method: '={{$responseItem.method}}', url: '={{$responseItem.url}}', header: '={{$responseItem.header}}',
              body: '={{$responseItem.body}}', after: '={{$responseItem.after}}' } }] },
        }}, { name: 'Binary', value: 'binary', routing: {
          request: { method: 'POST', url: '/binary', returnFullResponse: true, encoding: 'arraybuffer',
            headers: { 'Content-Type': 'multipart/form-data' } },
          send: { preSend: [async function(options) { const data = await this.helpers.getBinaryDataBuffer('file');
            const form = new FormData(); form.append('file', new Blob([data])); options.body = form; return options }] },
          output: { postReceive: [async function(items, responseData) { const binary = await this.helpers.prepareBinaryData(
            responseData.body, 'result.bin', 'application/octet-stream'); return [{ json: {}, binary: { data: binary } }] }] },
        }}] },
        { name: 'query', type: 'string', default: '', routing: { send: { type: 'query', property: 'q' } } },
        { name: 'value', type: 'string', default: '', routing: { send: { type: 'body', property: 'value' } } },
        { name: 'optional', type: 'string', default: '', routing: { send: { type: 'body', property: 'optional',
          value: '={{$parameter["optional"] && $parameter["optional"] !== "" ? JSON.parse($parameter["optional"]) : undefined}}' } } },
        { name: 'simplify', type: 'boolean', default: false },
      ],
    }
  }
}
module.exports = { Declarative }
`)
    await writeFile(join(packageRoot, 'package.json'), JSON.stringify({
      name: 'n8n-nodes-declarative-hooks', version: '1.0.0', n8n: { nodes: ['nodes/Declarative.node.cjs'] },
    }))
    const packed = spawnSync('tar', ['-czf', join(root, 'fixture.tgz'), '-C', root, 'package'])
    assert.equal(packed.status, 0)
    const bytes = await (await import('node:fs/promises')).readFile(join(root, 'fixture.tgz'))
    const store = new PackageStore(storeRoot)
    const result = await store.install({ archiveBase64: bytes.toString('base64') })
    assert.equal(result.hostAbiVersion, 5)
    assert.equal(result.components[0].compatibilityStatus, 'SUPPORTED')
    const installed = await store.metadata(result.fingerprint)
    const invoked = await invokeChild({
      root: installed.root, sourcePath: result.components[0].sourcePath, exportName: 'Declarative',
      componentId: 'declarative', operation: 'invoke',
      parameters: { baseUrl: `http://127.0.0.1:${server.address().port}`, operation: 'create', query: 'hello',
        value: 'x', optional: '{"x":1}', simplify: true },
      credentials: {}, input: {}, context: {},
    })
    assert.equal(invoked.status, 0, invoked.stdout + invoked.stderr)
    const output = JSON.parse(invoked.stdout).output[0][0].json
    assert.equal(output.method, 'POST')
    assert.equal(output.url, '/items?q=hello')
    assert.equal(output.header, 'before')
    assert.deepEqual(output.body, { prepared: true, value: 'x', optional: { x: 1 } })
    assert.equal(output.after, true)
    const binary = await invokeChild({
      root: installed.root, sourcePath: result.components[0].sourcePath, exportName: 'Declarative',
      componentId: 'declarative', operation: 'invoke',
      parameters: { baseUrl: `http://127.0.0.1:${server.address().port}`, operation: 'binary' }, credentials: {},
      input: { json: {}, binary: { file: { data: Buffer.from('input').toString('base64') } } }, context: {},
    })
    assert.equal(binary.status, 0, binary.stdout + binary.stderr)
    const binaryOutput = JSON.parse(binary.stdout).output[0][0].binary.data
    assert.equal(binaryOutput.data, Buffer.from([0, 255, 1]).toString('base64'))
    assert.equal(binaryOutput.fileName, 'result.bin')
  } finally {
    await new Promise(resolve => server.close(resolve))
    await rm(root, { recursive: true, force: true }); await rm(storeRoot, { recursive: true, force: true })
  }
})

test('声明式节点拒绝任意表达式和非函数 hook', async () => {
  const root = await mkdtemp(join(tmpdir(), 'base-ai-n8n-unsafe-routing-'))
  const packageRoot = join(root, 'package'); const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    await mkdir(join(packageRoot, 'nodes'), { recursive: true })
    await writeFile(join(packageRoot, 'nodes/Unsafe.node.cjs'), `
class Unsafe {
  constructor() {
    this.description = { name: 'unsafe', displayName: 'Unsafe', requestDefaults: { baseURL: 'https://example.com' },
      properties: [{ name: 'operation', type: 'options', default: 'run', options: [{ name: 'Run', value: 'run',
        routing: { request: { method: 'POST', url: '={{ global.process.exit() }}' },
          send: { preSend: ['not-a-function'] } } }] }] }
  }
}
module.exports = { Unsafe }
`)
    await writeFile(join(packageRoot, 'package.json'), JSON.stringify({
      name: 'n8n-nodes-unsafe-routing', version: '1.0.0', n8n: { nodes: ['nodes/Unsafe.node.cjs'] },
    }))
    const packed = spawnSync('tar', ['-czf', join(root, 'fixture.tgz'), '-C', root, 'package'])
    assert.equal(packed.status, 0)
    const bytes = await (await import('node:fs/promises')).readFile(join(root, 'fixture.tgz'))
    const result = await new PackageStore(storeRoot).install({ archiveBase64: bytes.toString('base64') })
    assert.equal(result.components[0].compatibilityStatus, 'PARTIAL')
    assert.equal(result.components[0].compatibilityReason, 'DECLARATIVE_ROUTING_NOT_IMPLEMENTED')
  } finally { await rm(root, { recursive: true, force: true }); await rm(storeRoot, { recursive: true, force: true }) }
})

test('仅允许使用严格指纹删除缓存包', async () => {
  const item = await fixture(); const storeRoot = await mkdtemp(join(tmpdir(), 'base-ai-n8n-store-'))
  try {
    const store = new PackageStore(storeRoot)
    const bytes = await (await import('node:fs/promises')).readFile(item.archive)
    const result = await store.install({ archiveBase64: bytes.toString('base64') })
    await assert.rejects(() => store.remove('../escape'), /PACKAGE_NOT_FOUND/)
    assert.deepEqual(await store.remove(result.fingerprint), { removed: true })
    await assert.rejects(() => store.metadata(result.fingerprint), /PACKAGE_NOT_FOUND/)
  } finally { await rm(item.root, { recursive: true, force: true }); await rm(storeRoot, { recursive: true, force: true }) }
})
