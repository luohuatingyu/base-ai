import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'
import { MAX_PRIVATE_KEY_FILE_SIZE, readPrivateKeyFile } from '../src/utils/serverCredentials.js'

const viewSource = readFileSync(new URL('../src/views/ServersView.vue', import.meta.url), 'utf8')

// 验证操作系统识别结果对应图标，并用文本插值安全展示完整系统信息。
test('服务器列表展示系统图标、版本、内核、架构和探测时间', () => {
  const source = viewSource.match(/function systemIcon\(identity\) \{[\s\S]*?\n\}/)[0]
  const icon = runInNewContext(`(${source})`)
  for (const [identity, expected] of [
    [{ family: 'Linux', id: 'alinux' }, 'aliyun'],
    [{ family: 'Linux', id: 'ubuntu' }, 'ubuntu'],
    [{ family: 'Linux', id: 'centos' }, 'linux'],
    [{ family: 'Linux', id: '<script>' }, 'linux'],
    [{ family: 'Darwin' }, 'macos'],
    [{ family: 'Windows_NT' }, 'windows'],
    [{ family: 'Unknown' }, 'unknown'],
    [null, 'unknown'],
  ]) assert.equal(icon(identity), expected)
  for (const field of ['name', 'version', 'kernel', 'architecture', 'detectedAt']) {
    assert.ok(viewSource.includes(`scope.row.systemInfo.${field}`))
  }
  assert.doesNotMatch(viewSource, /v-html/)
  for (const key of ['operatingSystem', 'osNotDetected', 'osKernel', 'osArchitecture', 'osDetectedAt']) {
    assert.ok(zhCN.servers[key])
    assert.ok(enUS.servers[key])
  }
})

// 验证安全错误码在两种语言中均有具体故障解释。
test('连接与监控错误映射保留可操作的故障原因', () => {
  const source = viewSource.match(/function monitorErrorText\(value\) \{[\s\S]*?\n\}/)[0]
  for (const locale of [zhCN, enUS]) {
    const translate = runInNewContext(`(${source})`, { t: key => locale.servers[key.split('.')[1]] })
    for (const code of ['SSH_LOCAL_USER_MISSING', 'SSH_AUTHENTICATION_FAILED', 'SSH_CONNECTION_TIMEOUT', 'SSH_CONNECTION_REFUSED', 'SSH_HOST_UNRESOLVED', 'SSH_NETWORK_UNREACHABLE', 'SSH_PRIVATE_KEY_INVALID', 'SSH_COMMAND_FAILED', 'MONITOR_OS_UNSUPPORTED', 'MONITOR_OUTPUT_INVALID', 'server.agentUnavailable', 'server.agentUnauthorized']) {
      const message = translate(code)
      assert.ok(message && message !== code, code)
    }
  }
})

test('服务器页面支持手工新增 SSH 配置并保留本地模式兼容', () => {
  assert.match(viewSource, /mode: 'SSH'/)
  assert.match(viewSource, /operations:server:create/)
  assert.match(viewSource, /form\.mode === 'SSH'/)
  assert.match(viewSource, /form\.mode === 'LOCAL'/)
  assert.match(viewSource, /@click="form\.mode = 'SSH'"/)
  assert.match(viewSource, /@click="form\.mode = 'LOCAL'"/)
  for (const field of ['host', 'port', 'username', 'authType', 'privateKey', 'passphrase', 'password']) {
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
  assert.match(viewSource, /v-model="form\.privateKey"[\s\S]*?type="textarea"/)
})

test('服务器相关弹窗使用卡片分区并保留认证条件分支', () => {
  for (const className of ['server-editor-dialog', 'server-monitor-dialog']) {
    assert.match(viewSource, new RegExp('class="' + className + '"'), className)
  }
  for (const sectionKey of ['basicSection', 'connectionSection', 'securitySection']) {
    assert.match(viewSource, new RegExp('servers\\.' + sectionKey), sectionKey)
    assert.ok(zhCN.servers[sectionKey])
    assert.ok(enUS.servers[sectionKey])
  }
  assert.match(viewSource, /v-if="form\.mode === 'SSH'"/)
  assert.match(viewSource, /v-if="\['KEY', 'KEY_PASSWORD'\]\.includes\(form\.authType\)"/)
  assert.match(viewSource, /v-if="\['PASSWORD', 'KEY_PASSWORD'\]\.includes\(form\.authType\)"/)
  assert.match(viewSource, /@click="changeAuthType\('KEY'\)"/)
  assert.match(viewSource, /@click="changeAuthType\('PASSWORD'\)"/)
  assert.match(viewSource, /@click="changeAuthType\('KEY_PASSWORD'\)"/)
  assert.doesNotMatch(viewSource, /v-model="form\.hostKey"/)
  assert.match(zhCN.servers.passphraseHelp, /不是服务器登录密码/)
  assert.match(enUS.servers.passphraseHelp, /not the server login password/)
})

// 验证真实表单函数在三种认证、编辑保留和缺失凭据时的业务结果。
test('服务器认证表单不要求指纹且组合登录要求两项凭据', () => {
  const source = viewSource.match(/function validateForm\(\) \{[\s\S]*?\n\}/)[0]
  const scenarios = [
    { authType: 'KEY', privateKey: 'PRIVATE', password: '', valid: true },
    { authType: 'PASSWORD', privateKey: '', password: 'secret', valid: true },
    { authType: 'KEY_PASSWORD', privateKey: 'PRIVATE', password: 'secret', valid: true },
    { authType: 'KEY_PASSWORD', privateKey: '', password: 'secret', valid: false },
    { authType: 'KEY_PASSWORD', privateKey: 'PRIVATE', password: '  ', valid: false },
    { id: 1, authType: 'KEY_PASSWORD', privateKey: '', password: '', valid: true },
    { id: 1, original: 'KEY', authType: 'KEY_PASSWORD', privateKey: '', password: 'secret', valid: false },
  ]
  for (const scenario of scenarios) {
    const form = { id: 1, name: 'server', mode: 'SSH', host: 'localhost', port: 22, username: 'deploy', hostKey: '', ...scenario }
    const validate = runInNewContext(`(${source})`, { form, originalAuthType: { value: scenario.original || (scenario.id ? scenario.authType : 'NONE') } })
    assert.equal(validate(), scenario.valid, JSON.stringify(scenario))
  }
})

// 验证新服务器强制选择有效凭据，停用或认证类型不匹配的引用不可提交。
test('服务器选择凭据并自动复用账号', () => {
  const source = viewSource.match(/function validateForm\(\) \{[\s\S]*?\n\}/)[0]
  for (const [credentialId, options, expected] of [[null, [], false], [1, [{ id: 1 }], true], [2, [{ id: 1 }], false]]) {
    const form = { id: null, credentialId, name: 'server', mode: 'SSH', host: 'host', username: 'deploy', port: 22 }
    const validate = runInNewContext(`(${source})`, { form, selectableCredentials: { value: options } })
    assert.equal(validate(), expected)
  }
  const selectSource = viewSource.match(/function selectCredential\(id\) \{[\s\S]*?\n\}/)[0]
  const form = { username: 'old', privateKey: 'old-key', password: 'old-password', passphrase: 'old-phrase' }
  const select = runInNewContext(`(${selectSource})`, { form, credentials: { value: [{ id: 7, username: 'deploy' }] } })
  select(7)
  assert.deepEqual(form, { username: 'deploy', privateKey: '', password: '', passphrase: '' })
  for (const locale of [zhCN, enUS]) assert.ok(locale.serverCredentials.title)
})

test('独立凭据支持全部混搭且已有账密无需填写账号', () => {
  const source = viewSource.match(/function validateForm\(\) \{[\s\S]*?\n\}/)[0]
  for (const authType of ['KEY', 'PASSWORD', 'KEY_PASSWORD']) {
    for (const keyReference of [false, true]) {
      for (const accountReference of [false, true]) {
        const form = { id: null, name: 'server', mode: 'SSH', host: 'host', port: 22, authType,
          username: accountReference && authType !== 'KEY' ? '' : 'manual',
          keyCredentialId: keyReference && authType !== 'PASSWORD' ? 1 : null,
          passwordCredentialId: accountReference && authType !== 'KEY' ? 2 : null,
          privateKey: keyReference ? '' : 'private', password: accountReference ? '' : 'secret' }
        const availableCredentials = type => [{ id: type === 'KEY' ? 1 : 2 }]
        const validate = runInNewContext(`(${source})`, { form, availableCredentials })
        assert.equal(validate(), true, JSON.stringify(form))
        if (form.keyCredentialId || form.passwordCredentialId) {
          const invalid = runInNewContext(`(${source})`, { form, availableCredentials: () => [] })
          assert.equal(invalid(), false)
        }
      }
    }
  }
  assert.match(viewSource, /v-if="form\.authType === 'KEY'" :label="t\('servers.username'\)"/)
  assert.match(viewSource, /<template v-if="!form.passwordCredentialId">/)
  assert.doesNotMatch(viewSource, /v-if="form.id && form.authType === 'KEY_PASSWORD'"/)
})

test('切换来源仅清理对应秘密且账密带入账号', () => {
  const source = viewSource.match(/function changeCredential\(type\) \{[\s\S]*?\n\}/)[0]
  const form = { username: '', passwordCredentialId: 2, privateKey: 'key', password: 'password', passphrase: 'phrase' }
  const change = runInNewContext(`(${source})`, { form, privateKeyFileName: { value: 'key.pem' },
    availableCredentials: () => [{ id: 2, username: 'deploy' }] })
  change('PASSWORD')
  assert.equal(form.username, 'deploy')
  assert.equal(form.password, '')
  assert.equal(form.privateKey, 'key')
  change('KEY')
  assert.equal(form.privateKey, '')
  assert.equal(form.passphrase, '')
  assert.equal(form.username, 'deploy')
})

test('服务器页面移除部署与历史并保留连接和监控操作', () => {
  assert.doesNotMatch(viewSource, /servers\.(deploy|history)|deployVisible|historyVisible|deployForm|deploymentRows/)
  assert.doesNotMatch(viewSource, /operations:server:(deploy|rollback|logs)/)
  assert.match(viewSource, /@click="test\(scope.row\)"/)
  assert.match(viewSource, /@click="monitor\(scope.row\)"/)
  assert.match(viewSource, /@click="open\(scope.row\)"/)
})

test('连接测试阻止重复请求并在成功与异常后释放加载状态', async () => {
  for (const outcome of ['success', 'failure', 'exception']) {
    const calls = []
    const messages = []
    const testingIds = { value: new Set() }
    let complete
    const response = new Promise(resolve => { complete = resolve })
    const context = {
      testingIds,
      http: { post: async url => {
        calls.push(url)
        await response
        if (outcome === 'exception') throw new Error('timeout')
        return { data: outcome === 'success' ? { status: 'SUCCEEDED' } : { status: 'FAILED', error: 'server.agentNotConfigured' } }
      } },
      ElMessage: { success: value => messages.push(value), warning: value => messages.push(value) },
      monitorErrorText: value => `localized:${value}`,
      t: value => value,
      load: async () => calls.push('reload'),
      showHttpError: error => messages.push(error.message),
    }
    const source = viewSource.match(/async function test\(row\) \{[\s\S]*?\n\}/)[0]
    const invoke = runInNewContext(`(${source})`, context)
    const pending = invoke({ id: 42 })
    assert.equal(testingIds.value.has(42), true)
    await invoke({ id: 42 })
    assert.deepEqual(calls, ['/servers/42/test'])
    complete()
    await pending
    assert.equal(testingIds.value.size, 0)
    assert.deepEqual(calls, outcome === 'exception' ? ['/servers/42/test'] : ['/servers/42/test', 'reload'])
    assert.deepEqual(messages, [outcome === 'success' ? 'SUCCEEDED' : outcome === 'failure' ? 'localized:server.agentNotConfigured' : 'timeout'])
  }
})

test('资源监控复用服务器测试权限并在弹窗中实时查询', () => {
  assert.match(viewSource, /operations:server:test/)
  assert.match(viewSource, /http\.get\(`\/servers\/\$\{monitorServer\.value\.id\}\/monitor`\)/)
  assert.match(viewSource, /monitorVisible/)
  assert.match(viewSource, /refreshMonitor/)
  assert.match(viewSource, /monitorData\.host\.cpuUsagePercent/)
  assert.match(viewSource, /monitorData\.host\.memoryUsagePercent/)
  assert.match(viewSource, /monitorData\.host\.diskUsagePercent/)
  assert.match(zhCN.servers.liveQueryHint, /实时查询/)
  assert.match(enUS.servers.liveQueryHint, /live data/)
})

test('监控弹窗仅展示主机指标并保留采集失败提示', () => {
  assert.doesNotMatch(viewSource, /monitorData\.containers|monitorData\.containerError|containerHealth|containerStateType|servers\.containers/)
  assert.match(viewSource, /monitorData\.host\.uptimeSeconds/)
  assert.match(viewSource, /servers\.monitorFailed/)
})
