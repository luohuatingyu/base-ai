import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const root = new URL('../../', import.meta.url)

test('节点管理页为 n8n 与 Dify 展示独立容器开关并只在运行后开放市场', async () => {
  const source = await readFile(new URL('frontend/src/views/WorkflowNodesView.vue', root), 'utf8')

  assert.match(source, /http\.get\('\/workflow\/adapters'/)
  assert.match(source, /http\.put\(`\/workflow\/adapters\/\$\{adapter\.source\}`/)
  assert.match(source, /auth\.hasPermission\('workflow:adapter:manage'\)/)
  assert.match(source, /selectedAdapter\?\.status !== 'RUNNING'/)
  assert.match(source, /\['ENABLING', 'STARTING', 'DISABLING'\]/)
  assert.match(source, /stopAdapterPolling\(\)/)
})

test('Compose 默认不启动插件 Worker 且隔离 manager 只挂载所需控制边界', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')

  for (const service of ['n8n-plugin-worker', 'dify-plugin-worker']) {
    const start = compose.indexOf(`  ${service}:`)
    const next = compose.slice(start + 3).search(/\n  [a-z]/)
    const block = compose.slice(start, next < 0 ? compose.length : start + 3 + next)
    assert.match(block, /profiles: \["plugin-adapters"\]/)
    assert.match(block, /restart: "no"/)
  }
  const managerStart = compose.indexOf('\n  adapter-manager:\n') + 1
  const managerEnd = compose.indexOf('\n  outbound-gateway:', managerStart)
  const manager = compose.slice(managerStart, managerEnd)
  assert.match(manager, /\/var\/run\/docker\.sock:\/var\/run\/docker\.sock/)
  assert.match(manager, /\.\/docker-compose\.yml:\/workspace\/docker-compose\.yml:ro/)
  assert.match(manager, /\.\/\.env:\/workspace\/\.env:ro/)
  assert.doesNotMatch(manager, /^\s*-\s+\.:\/workspace:ro$/m)
  assert.match(manager, /cap_drop:\s*\n\s*- ALL/)
  assert.match(manager, /no-new-privileges:true/)
  assert.doesNotMatch(manager, /ports:/)
})

test('adapter-manager 固定来源白名单并拒绝把请求值拼入 Compose 命令', async () => {
  const manager = await readFile(new URL('adapter-manager/main.go', root), 'utf8')

  assert.match(manager, /"N8N":\s+"n8n-plugin-worker"/)
  assert.match(manager, /"DIFY":\s+"dify-plugin-worker"/)
  assert.match(manager, /ConstantTimeCompare/)
  assert.match(manager, /http\.MaxBytesReader/)
  assert.match(manager, /"--no-build", "--no-deps", service/)
})

test('插件准入清单使用独立权限并强制保存后审批', async () => {
  const source = await readFile(new URL('frontend/src/views/WorkflowNodesView.vue', root), 'utf8')
  const zh = await readFile(new URL('frontend/src/locales/zh-CN.js', root), 'utf8')
  const en = await readFile(new URL('frontend/src/locales/en-US.js', root), 'utf8')

  assert.match(source, /auth\.hasPermission\('workflow:plugin:admission'\)/)
  assert.match(source, /http\.get\('\/workflow\/plugin-admissions'\)/)
  assert.match(source, /http\.put\(`\/workflow\/plugin-admissions\/\$\{admissionForm\.pluginId\}`/)
  assert.match(source, /\/review`/)
  assert.match(source, /'NO_DATA'/)
  assert.match(source, /normalizeAdmissionDataTypes/)
  assert.match(source, /PLUGIN_LICENSE_OPTIONS/)
  assert.match(source, /CUSTOM_PLUGIN_LICENSE/)
  assert.match(source, /applyPluginLicenseSelection/)
  assert.match(source, /pluginAdmissionLicenseValid/)
  assert.match(source, /:disabled="admissionLicenseSelection !== CUSTOM_PLUGIN_LICENSE"/)
  for (const locale of [zh, en]) {
    assert.match(locale, /pluginAdmission:/)
    assert.match(locale, /selectLicense:/)
    assert.match(locale, /customLicense:/)
    assert.match(locale, /licenseRequired:/)
    assert.match(locale, /SENSITIVE_PERSONAL_INFORMATION/)
    assert.match(locale, /CREDENTIALS/)
  }
})
