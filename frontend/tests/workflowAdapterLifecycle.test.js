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

test('Compose 默认不启动插件适配器，且启用时仅隔离 Broker 持有 rootless Docker 权限', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const adapterCompose = await readFile(new URL('adapter-manager/adapter-compose.yml', root), 'utf8')

  for (const service of ['n8n-plugin-worker', 'dify-plugin-worker']) {
    const start = compose.indexOf(`  ${service}:`)
    const next = compose.slice(start + 3).search(/\n {2}[a-z]/)
    const block = compose.slice(start, next < 0 ? compose.length : start + 3 + next)
    assert.match(block, /profiles: \["plugin-adapters"\]/)
    assert.match(block, /restart: "no"/)
    assert.doesNotMatch(block, /\/data\/packages/)
    assert.doesNotMatch(block, /OUTBOUND_GATEWAY_TOKEN|HTTP_PROXY|HTTPS_PROXY/)
    assert.match(block, /sandbox-control:\/run\/(?:n8n|dify)-sandbox:ro/)
  }
  const managerStart = compose.indexOf('\n  adapter-manager:\n') + 1
  const managerEnd = compose.indexOf('\n  outbound-gateway:', managerStart)
  const manager = compose.slice(managerStart, managerEnd)
  assert.match(manager, /profiles: \["plugin-adapters"\]/)
  assert.doesNotMatch(manager, /\/var\/run\/docker\.sock/)
  assert.doesNotMatch(manager, /\/workspace\/\.env/)
  assert.doesNotMatch(manager, /\/workspace\/docker-compose\.yml/)
  assert.match(manager, /adapter-control:\/run\/adapter-control:ro/)
  assert.match(manager, /cap_drop:\s*\n\s*- ALL/)
  assert.match(manager, /no-new-privileges:true/)
  assert.doesNotMatch(manager, /ports:/)

  const supervisorStart = compose.indexOf('\n  adapter-supervisor:\n') + 1
  const supervisorEnd = compose.indexOf('\n  adapter-manager:', supervisorStart)
  const supervisor = compose.slice(supervisorStart, supervisorEnd)
  assert.match(supervisor, /profiles: \["plugin-adapters"\]/)
  assert.doesNotMatch(supervisor, /\/var\/run\/docker\.sock/)
  assert.doesNotMatch(supervisor, /\/workspace\/docker-compose\.yml/)
  assert.doesNotMatch(supervisor, /\/workspace\/\.env/)
  assert.match(supervisor, /adapter-control:\/run\/adapter-control/)
  assert.match(supervisor, /adapter-broker-control:\/run\/adapter-broker:ro/)
  assert.match(supervisor, /network_mode: none/)
  assert.doesNotMatch(supervisor, /^\s*networks:/m)

  const brokerStart = compose.indexOf('\n  adapter-docker-broker:\n') + 1
  const brokerEnd = compose.indexOf('\n  adapter-supervisor:', brokerStart)
  const broker = compose.slice(brokerStart, brokerEnd)
  assert.match(broker, /profiles: \["plugin-adapters"\]/)
  assert.match(broker, /ADAPTER_DOCKER_SOCKET[^\n]*:\/var\/run\/docker\.sock/)
  assert.match(broker, /ADAPTER_DOCKER_SOCKET_GID[^\n]*/)
  assert.match(broker, /\.\/adapter-manager\/adapter-compose\.yml:\/workspace\/adapter-compose\.yml:ro/)
  assert.doesNotMatch(broker, /\.\/docker-compose\.yml|\.\/\.env|COMPOSE_ENV_FILE/)
  assert.match(broker, /adapter-broker-control:\/run\/adapter-broker/)
  assert.match(broker, /dify-sandbox-control:\/run\/dify-sandbox/)
  assert.match(broker, /n8n-sandbox-control:\/run\/n8n-sandbox/)
  assert.match(broker, /PLUGIN_SANDBOX_EGRESS_SIGNING_KEY/)
  assert.match(broker, /network_mode: none/)
  assert.match(broker, /read_only: true/)
  assert.match(broker, /APP_IMAGE_REVISION/)
  assert.match(adapterCompose, /# 此文件仅描述由 Docker Broker 启停的两个插件 Worker。/)
  assert.match(adapterCompose, /external: true/)
  assert.doesNotMatch(adapterCompose, /MYSQL_PASSWORD|APP_TOKEN_SECRET|REDIS_PASSWORD/)

  const backendStart = compose.indexOf('\n  backend:\n') + 1
  const backendEnd = compose.indexOf('\n  python-worker:', backendStart)
  const backend = compose.slice(backendStart, backendEnd)
  assert.doesNotMatch(backend, /adapter-manager:\s*\n\s*condition:|outbound-gateway:\s*\n\s*condition:/)

  const gatewayStart = compose.indexOf('\n  outbound-gateway:\n') + 1
  const gatewayEnd = compose.indexOf('\n  dify-plugin-worker:', gatewayStart)
  assert.match(compose.slice(gatewayStart, gatewayEnd), /profiles: \["plugin-adapters"\]/)
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
