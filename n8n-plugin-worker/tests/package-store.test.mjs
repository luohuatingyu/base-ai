/** 验证自研 n8n ABI 宿主的声明解析和安全边界。 */

import assert from 'node:assert/strict'
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { spawnSync } from 'node:child_process'
import test from 'node:test'
import { PackageError, PackageStore } from '../app/package-store.mjs'

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
  await writeFile(join(packageRoot, 'package.json'), JSON.stringify({ name: 'n8n-nodes-base-ai-fixture', version: '1.0.0', n8n: { nodes } }))
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
