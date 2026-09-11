import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  buildDiagnoseCommand,
  buildInstallCommand,
  buildSetServerCommand,
  detectSelfSignedDeployment,
  isPrivateOrigin,
  shellQuote
} from '../src/utils/deviceAgentInstallCommand.js'
import {
  buildDetectedWdaConfigPayload,
  loadExistingWdaConfig
} from '../src/utils/deviceAgentWdaConfig.js'
import { createRegistryStatusPoller } from '../src/utils/deviceAgentRegistryPoller.js'

const agentView = readFileSync(new URL('../src/views/DeviceAgentsView.vue', import.meta.url), 'utf8')
const router = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')

test('安装命令安全转义参数并支持自签名证书与 npm 镜像', () => {
  const command = buildInstallCommand({
    origin: 'https://console.example.com',
    backendUrl: 'https://10.0.0.8/base/',
    pairingCode: "PAIR'CODE",
    selfSigned: true,
    npmRegistry: 'https://registry.example.com/npm'
  })

  assert.match(command, /curl -fsSL -k/)
  assert.match(command, /--backend-url 'https:\/\/10\.0\.0\.8\/base'/)
  assert.match(command, /--pairing-code 'PAIR'\\''CODE'/)
  assert.match(command, /--force-pair/)
  assert.match(command, /--ca-file 'https:\/\/10\.0\.0\.8\/base\/agent-dist\/root-ca\.crt' --insecure/)
  assert.match(command, /--npm-registry 'https:\/\/registry\.example\.com\/npm'/)
  assert.equal(buildInstallCommand({ origin: 'https://example.com', pairingCode: '' }), '')
  assert.equal(shellQuote('plain'), "'plain'")
})

test('自签名探测仅针对 HTTPS 并把网络失败安全折叠为 false', async () => {
  const calls = []
  assert.equal(await detectSelfSignedDeployment('http://10.0.0.8'), false)
  assert.equal(await detectSelfSignedDeployment('not-a-url'), false)
  assert.equal(await detectSelfSignedDeployment('https://10.0.0.8/base', {
    fetchImpl: async (url, options) => {
      calls.push([url, options.method])
      return { ok: true }
    }
  }), true)
  assert.equal(await detectSelfSignedDeployment('https://10.0.0.8', {
    fetchImpl: async () => { throw new Error('offline') }
  }), false)
  assert.deepEqual(calls, [['https://10.0.0.8/base/agent-dist/root-ca.crt', 'HEAD']])
})

test('私网识别、本机改址和诊断命令保持通用设备 Agent 路径', () => {
  assert.equal(isPrivateOrigin('https://localhost'), true)
  assert.equal(isPrivateOrigin('https://192.168.1.5'), true)
  assert.equal(isPrivateOrigin('https://172.31.0.2'), true)
  assert.equal(isPrivateOrigin('https://example.com'), false)
  assert.equal(isPrivateOrigin('invalid'), false)
  assert.equal(buildSetServerCommand({ backendUrl: '' }), '')
  assert.match(buildSetServerCommand({
    backendUrl: 'https://10.0.0.8/',
    selfSigned: true
  }), /device_agent\.main set-server[\s\S]*--ca-file/)
  // 改写地址只发生在独立进程，常驻 Agent 仍持有旧地址，必须 kickstart 重启才生效
  assert.match(buildSetServerCommand({ backendUrl: 'https://10.0.0.8/' }),
    /launchctl kickstart -k "gui\/\$\(id -u\)\/com\.baseai\.device-agent"/)
  assert.match(buildDiagnoseCommand(), /com\.baseai\.device-agent/)
})

test('WDA 自动配置保留既有安全选择和启动参数', () => {
  const payload = buildDetectedWdaConfigPayload({
    teamId: 'ABCDEFGHIJ',
    signingIdentity: 'Apple Development'
  }, {
    launchMode: 'URL',
    wdaUrl: 'http://127.0.0.1:8100',
    baseWdaLocalPort: 8200,
    signingConfig: {
      allowProvisioningDeviceRegistration: true,
      updatedWdaBundleId: 'com.example.WebDriverAgentRunner'
    }
  })

  assert.deepEqual(payload, {
    signingConfig: {
      xcodeOrgId: 'ABCDEFGHIJ',
      xcodeSigningId: 'Apple Development',
      allowProvisioningDeviceRegistration: true,
      updatedWdaBundleId: 'com.example.WebDriverAgentRunner'
    },
    launchMode: 'URL',
    wdaUrl: 'http://127.0.0.1:8100',
    appiumServerUrl: 'http://127.0.0.1:4723',
    baseWdaLocalPort: 8200
  })
})

test('WDA 配置读取仅把 404 解释为尚未配置', async () => {
  assert.deepEqual(await loadExistingWdaConfig({
    get: async () => ({ data: { launchMode: 'XCODEBUILD' } })
  }, 'ios-agent-one'), { launchMode: 'XCODEBUILD' })
  assert.equal(await loadExistingWdaConfig({
    get: async () => { throw Object.assign(new Error('not found'), { response: { status: 404 } }) }
  }, 'ios-agent-one'), null)
  await assert.rejects(() => loadExistingWdaConfig({
    get: async () => { throw new Error('offline') }
  }, 'ios-agent-one'), /offline/)
})

test('管理页和路由包含完整通用 Agent 入口且不含企业微信业务', () => {
  assert.match(router, /automation\/device-agents\/config-guide/)
  assert.match(router, /automation\/device-agents\/onboarding/)
  assert.match(agentView, /automation:device-agent:\$\{action\}/)
  assert.match(agentView, /pairingPagination/)
  assert.match(agentView, /operationSpeed/)
  assert.doesNotMatch(agentView, /wecom|accountCode|TASK_EXECUTION/i)
})

test('Registry 弹窗轮询状态且关闭后停止，不堆叠刷新请求', async () => {
  let intervalCallback
  let intervalStarts = 0
  let clearedTimer = null
  let refreshCalls = 0
  let finishRefresh
  const poller = createRegistryStatusPoller({
    refresh: () => {
      refreshCalls += 1
      return new Promise((resolve) => { finishRefresh = resolve })
    },
    setIntervalFn: (callback, intervalMs) => {
      intervalCallback = callback
      intervalStarts += 1
      assert.equal(intervalMs, 5000)
      return 17
    },
    clearIntervalFn: (timer) => { clearedTimer = timer }
  })

  poller.start()
  poller.start()
  assert.equal(intervalStarts, 1)

  const firstRefresh = intervalCallback()
  await intervalCallback()
  assert.equal(refreshCalls, 1)
  finishRefresh()
  await firstRefresh

  poller.stop()
  poller.stop()
  assert.equal(clearedTimer, 17)
  assert.match(agentView, /refresh:\s*\(\)\s*=>\s*loadRegistry\(\{ silent: true \}\)/)
  assert.match(agentView, /if \(!silent\) showHttpError\(error, 'deviceAgents\.registry\.loadError'\)/)
  assert.match(agentView, /@closed="stopRegistryPolling"/)
  assert.match(agentView, /if \(registryDialogVisible\.value\) registryStatusPoller\.start\(\)/)
})

test('Registry 后台刷新失败后允许下一轮继续恢复', async () => {
  let intervalCallback
  let refreshCalls = 0
  const poller = createRegistryStatusPoller({
    refresh: async () => {
      refreshCalls += 1
      if (refreshCalls === 1) throw new Error('temporary failure')
    },
    setIntervalFn: (callback) => {
      intervalCallback = callback
      return 23
    }
  })

  poller.start()
  await intervalCallback()
  await intervalCallback()

  assert.equal(refreshCalls, 2)
})
