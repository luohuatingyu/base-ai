import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { test } from 'node:test'

const view = readFileSync(new URL('../src/views/DeviceAgentsView.vue', import.meta.url), 'utf8')
const router = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const navigation = readFileSync(new URL('../src/utils/navigation.js', import.meta.url), 'utf8')
const zhLocale = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const compose = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8')
const caddyDockerfile = readFileSync(new URL('../../caddy/Dockerfile', import.meta.url), 'utf8')
const caddyfile = readFileSync(new URL('../../Caddyfile', import.meta.url), 'utf8')
const agentProject = readFileSync(new URL('../../device-agent/pyproject.toml', import.meta.url), 'utf8')

test('device Agent route is protected by its dedicated permission', () => {
  assert.match(router, /path:\s*['"]automation\/device-agents['"]/)
  assert.match(router, /permission:\s*['"]operations:device-agent:list['"]/)
  assert.match(navigation, /operations:device-agent:list/)
})

test('device Agent page exposes complete generic automation management actions', () => {
  assert.match(view, /\/automation\/device-agents/)
  assert.match(view, /DIAGNOSTICS/)
  assert.match(view, /DETECT_DEVICE/)
  assert.match(view, /SETUP_WDA/)
  assert.match(view, /START_WDA/)
  assert.match(view, /registry\/actions/)
  assert.match(view, /APPIUM_WDA_AUTOMATION/)
  assert.match(view, /UPGRADE/)
  assert.match(view, /Number\(value\) >= 1024/)
  assert.match(view, /Number\(value\) <= 65535/)
  assert.match(zhLocale, /原始 UDID 始终只保留在目标 Mac/)
})

test('device Agent page contains no business automation endpoints', () => {
  assert.doesNotMatch(view, /\/accounts|\/friends|\/tasks\/execute|wecom/i)
})

test('device Agent distribution is self-contained with pinned automation runtimes', () => {
  assert.match(compose, /agent-src:\s*\.\/device-agent/)
  assert.match(caddyDockerfile, /aarch64-apple-darwin-install_only/)
  assert.match(caddyDockerfile, /x86_64-apple-darwin-install_only/)
  assert.match(caddyfile, /handle_path \/agent-dist\/\*/)
  assert.match(agentProject, /requires-python = ">=3\.12,<3\.13"/)
  assert.match(caddyDockerfile, /AGENT_NODE_VERSION=22\.22\.0/)
  assert.match(caddyDockerfile, /AGENT_APPIUM_SPEC=appium@3\.7\.0/)
  assert.match(caddyDockerfile, /AGENT_XCUITEST_SPEC=xcuitest@12\.11\.1/)
})
