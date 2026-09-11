import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import { MAX_PRIVATE_KEY_FILE_SIZE, readPrivateKeyFile } from '../src/utils/serverCredentials.js'

const viewSource = readFileSync(new URL('../src/views/ServersView.vue', import.meta.url), 'utf8')

test('服务器页面支持手工新增 SSH 配置并保留本地模式兼容', () => {
  assert.match(viewSource, /mode: 'SSH'/)
  assert.match(viewSource, /operations:server:create/)
  assert.match(viewSource, /<el-option label="SSH" value="SSH"/)
  assert.match(viewSource, /<el-option label="LOCAL" value="LOCAL"/)
  for (const field of ['host', 'port', 'username', 'authType', 'hostKey', 'privateKey', 'passphrase', 'password']) {
    assert.match(viewSource, new RegExp(`form\\.${field}`), field)
  }
  assert.match(zhCN.servers.securityNotice, /AES-GCM/)
  assert.match(enUS.servers.securityNotice, /AES-GCM/)
})

test('新增服务器不展示或默认提交 Compose 配置', () => {
  assert.doesNotMatch(viewSource, /v-model="form\.workingDir"/)
  assert.doesNotMatch(viewSource, /v-model="form\.composeFile"/)
  assert.match(viewSource, /workingDir: ''/)
  assert.match(viewSource, /composeFile: ''/)
})

test('私钥支持本地文件读取并保留直接粘贴输入', async () => {
  const content = '-----BEGIN OPENSSH PRIVATE KEY-----\nkey\n-----END OPENSSH PRIVATE KEY-----'
  const maximumContent = 'k'.repeat(MAX_PRIVATE_KEY_FILE_SIZE)

  assert.equal(await readPrivateKeyFile({ size: content.length, text: async () => content }), content)
  assert.equal((await readPrivateKeyFile({ size: maximumContent.length, text: async () => maximumContent })).length, MAX_PRIVATE_KEY_FILE_SIZE)
  await assert.rejects(readPrivateKeyFile(null), /PRIVATE_KEY_FILE_EMPTY/)
  await assert.rejects(readPrivateKeyFile({ size: 1, text: async () => ' ' }), /PRIVATE_KEY_FILE_EMPTY/)
  await assert.rejects(readPrivateKeyFile({ size: MAX_PRIVATE_KEY_FILE_SIZE + 1, text: async () => content }), /PRIVATE_KEY_FILE_TOO_LARGE/)
  await assert.rejects(readPrivateKeyFile({ size: 1, text: async () => '密'.repeat(MAX_PRIVATE_KEY_FILE_SIZE) }), /PRIVATE_KEY_FILE_TOO_LARGE/)
  await assert.rejects(readPrivateKeyFile({ size: 1, text: async () => { throw new Error('disk failure') } }), /PRIVATE_KEY_FILE_READ_FAILED/)
  assert.match(viewSource, /type="file"/)
  assert.match(viewSource, /readPrivateKeyFile/)
  assert.match(viewSource, /v-model="form\.privateKey" type="textarea"/)
})

test('资源监控复用服务器测试权限并在弹窗中实时查询', () => {
  assert.match(viewSource, /operations:server:test/)
  assert.match(viewSource, /http\.get\(`\/servers\/\$\{monitorServer\.value\.id\}\/monitor`\)/)
  assert.match(viewSource, /monitorVisible/)
  assert.match(viewSource, /refreshMonitor/)
  assert.match(viewSource, /monitorData\.host\.cpuUsagePercent/)
  assert.match(viewSource, /monitorData\.host\.memoryUsagePercent/)
  assert.match(viewSource, /monitorData\.host\.diskUsagePercent/)
  assert.match(viewSource, /monitorData\.containers/)
  assert.match(zhCN.servers.liveQueryHint, /实时查询/)
  assert.match(enUS.servers.liveQueryHint, /live data/)
})

test('监控弹窗覆盖空容器、部分失败和容器健康状态', () => {
  assert.match(viewSource, /monitorData\.containerError/)
  assert.match(viewSource, /containerStateType/)
  assert.match(viewSource, /containerHealthType/)
  assert.match(viewSource, /containerHealthText/)
  for (const state of ['HEALTHY', 'UNHEALTHY', 'STARTING', 'NONE']) {
    assert.ok(zhCN.servers.health[state], state)
    assert.ok(enUS.servers.health[state], state)
  }
})
