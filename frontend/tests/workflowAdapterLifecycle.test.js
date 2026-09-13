import test from 'node:test'
import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'

const root = new URL('../../', import.meta.url)

test('节点管理页为 n8n 与 Dify 展示独立容器开关并只在运行后开放市场', async () => {
  const source = await readFile(new URL('frontend/src/views/WorkflowNodesView.vue', root), 'utf8')

  assert.match(source, /http\.get\('\/workflow\/adapters'/)
  assert.match(source, /http\.put\(`\/workflow\/adapters\/\$\{adapter\.source\}`/)
  assert.match(source, /auth\.hasPermission\('automation:workflow:adapter:manage'\)/)
  assert.match(source, /selectedAdapter\?\.status !== 'RUNNING'/)
  assert.match(source, /\['ENABLING', 'STARTING', 'DISABLING'\]/)
  assert.match(source, /stopAdapterPolling\(\)/)
})

test('合并控制平面保留鉴权、沙箱参数和 Worker 页面启停依赖', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const block = name => compose.split('\n  ' + name + ':\n')[1].split(/\n {2}[a-z][a-z-]+:\n/)[0]
  const manager = block('adapter-manager')
  for (const name of ['deployment-agent', 'adapter-manager', 'outbound-gateway', 'dify-plugin-worker', 'n8n-plugin-worker']) {
    assert.doesNotMatch(block(name), /profiles:/)
  }
  assert.match(manager, /target: broker/)
  assert.match(manager, /ADAPTER_MANAGER_MODE: combined/)
  assert.match(manager, /ADAPTER_MANAGER_INTERNAL_TOKEN:/)
  assert.match(manager, /ADAPTER_DOCKER_SOCKET[^\n]*:\/var\/run\/docker.sock/)
  assert.match(manager, /ADAPTER_DOCKER_SOCKET_GID/)
  assert.match(manager, /tmpfs:/)
  assert.match(manager, /DIFY_PLUGIN_WORKER_INTERNAL_TOKEN:/)
  assert.match(manager, /N8N_PLUGIN_WORKER_INTERNAL_TOKEN:/)
  assert.match(manager, /PLUGIN_MAX_PACKAGE_BYTES:/)
  assert.match(manager, /PLUGIN_SANDBOX_EGRESS_SIGNING_KEY:/)
  assert.match(manager, /cap_drop:\s*\n\s*- ALL/)
  assert.match(manager, /no-new-privileges:true/)
  assert.doesNotMatch(manager, /ports:|MYSQL_PASSWORD|APP_TOKEN_SECRET|REDIS_PASSWORD/)
  for (const source of ['dify-plugin-worker', 'n8n-plugin-worker']) {
    assert.match(block(source), /adapter-manager:\s*\n\s*condition: service_healthy/)
    assert.match(block(source), /outbound-gateway:\s*\n\s*condition: service_healthy/)
    assert.doesNotMatch(block(source), /docker.sock/)
  }
  for (const service of ['adapter-docker-broker', 'adapter-supervisor']) {
    assert.match(block(service), /profiles: \["legacy-plugin-adapters"\]/)
  }
})

test('Broker 为每个插件创建独占卷、一次性容器和最小出站令牌', async () => {
  const manager = await readFile(new URL('adapter-manager/main.go', root), 'utf8')
  const gateway = await readFile(new URL('outbound-gateway/main.go', root), 'utf8')

  assert.match(manager, /volumeName\(fingerprint string\)/)
  assert.match(manager, /--cap-drop", "ALL"/)
  assert.match(manager, /--security-opt",\s*"no-new-privileges:true"/)
  assert.match(manager, /--mount", "type=volume,src=/)
  assert.match(manager, /--network", network/)
  assert.match(manager, /payloadCommand\.AllowedDomains = nil/)
  assert.match(gateway, /decodeSandboxToken/)
  assert.match(gateway, /allowedFor\(policy outboundPolicy/)
})

test('adapter-manager 通过 Unix Socket 向 Supervisor 转发固定类型命令', async () => {
  const manager = await readFile(new URL('adapter-manager/main.go', root), 'utf8')

  assert.match(manager, /"N8N":\s+"n8n-plugin-worker"/)
  assert.match(manager, /"DIFY":\s+"dify-plugin-worker"/)
  assert.match(manager, /hmac\.Equal/)
  assert.match(manager, /X-Internal-Nonce/)
  assert.match(manager, /usedNonces/)
  assert.match(manager, /http\.MaxBytesReader/)
  assert.match(manager, /DialContext\(ctx, "unix", socketPath\)/)
  assert.match(manager, /type dockerBrokerController struct/)
  assert.match(manager, /type supervisorController struct/)
  assert.match(manager, /type managerController struct/)
  assert.match(manager, /ensureRootlessDocker/)
  assert.match(manager, /option == "name=rootless"/)
  assert.match(manager, /imageRevisionPattern/)
  assert.match(manager, /"--no-build", "--no-deps", service/)
  assert.doesNotMatch(manager, /COMPOSE_ENV_FILE/)
})

test('插件准入清单使用独立权限并强制保存后审批', async () => {
  const source = await readFile(new URL('frontend/src/views/WorkflowNodesView.vue', root), 'utf8')
  const zh = await readFile(new URL('frontend/src/locales/zh-CN.js', root), 'utf8')
  const en = await readFile(new URL('frontend/src/locales/en-US.js', root), 'utf8')

  assert.match(source, /auth\.hasPermission\('automation:workflow:plugin:admission'\)/)
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
