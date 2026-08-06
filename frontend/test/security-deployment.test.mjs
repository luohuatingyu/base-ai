import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

const root = new URL('../../', import.meta.url)

/** 截取 Compose 中单个服务配置，避免跨服务字段造成误判。 */
function serviceBlock(compose, name, nextName) {
  const start = compose.indexOf(`  ${name}:`)
  const end = nextName ? compose.indexOf(`\n  ${nextName}:`, start) : compose.indexOf('\nnetworks:', start)
  return compose.slice(start, end)
}

/** 验证平台简称经 Compose 规范化后统一用于运行时容器名称。 */
test('容器名称使用平台简称的小写项目名前缀', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const envExample = await readFile(new URL('.env.example', root), 'utf8')

  assert.match(compose, /^name: \$\{APP_PLATFORM_SHORT_NAME:\?Set APP_PLATFORM_SHORT_NAME\}$/m)
  assert.doesNotMatch(envExample, /^COMPOSE_PROJECT_NAME=/m)
  for (const [service, nextService] of [
    ['backend', 'python-worker'],
    ['python-worker', 'frontend'],
    ['frontend', 'caddy'],
    ['caddy', null],
  ]) {
    assert.match(
      serviceBlock(compose, service, nextService),
      new RegExp(`container_name: \\$\\{COMPOSE_PROJECT_NAME:\\?Set COMPOSE_PROJECT_NAME\\}-${service}`),
    )
  }
})

test('仅 Caddy 暴露 HTTP 和 HTTPS 端口', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')

  assert.doesNotMatch(serviceBlock(compose, 'backend', 'python-worker'), /\n\s+ports:/)
  assert.doesNotMatch(serviceBlock(compose, 'frontend', 'caddy'), /\n\s+ports:/)
  assert.match(serviceBlock(compose, 'caddy', null), /HTTP_PORT/)
  assert.match(serviceBlock(compose, 'caddy', null), /HTTPS_PORT/)
  assert.match(compose, /APP_TRUSTED_PROXY_CIDRS:.*CADDY_INTERNAL_IP/)
  assert.match(compose, /caddy:2\.11\.4-alpine/)
})

test('Caddy 为控制台和开放 API 提供 TLS 与安全响应头', async () => {
  const caddyfile = await readFile(new URL('Caddyfile', root), 'utf8')

  assert.match(caddyfile, /tls internal/)
  assert.match(caddyfile, /Strict-Transport-Security/)
  assert.match(caddyfile, /Content-Security-Policy/)
  assert.match(caddyfile, /X-Content-Type-Options/)
  assert.match(caddyfile, /X-Frame-Options/)
  assert.match(caddyfile, /auto_https disable_redirects/)
  assert.match(caddyfile, /redir \{\$CADDY_HTTPS_ORIGIN:https:\/\/localhost\}\{uri\} 308/)
  assert.match(caddyfile, /@api path \/api\/\*/)
  assert.match(caddyfile, /reverse_proxy backend:8080/)
  assert.match(caddyfile, /reverse_proxy frontend:8080/)
})

test('Caddy 在公网入口拒绝内部服务接口', async () => {
  const caddyfile = await readFile(new URL('Caddyfile', root), 'utf8')

  assert.match(caddyfile, /@internal\s+path\s+\/api\/internal\s+\/api\/internal\/\*/)
  assert.match(caddyfile, /handle\s+@internal\s*\{\s*respond\s+"Not Found"\s+404\s*\}/s)
  assert.ok(caddyfile.indexOf('handle @internal') < caddyfile.indexOf('handle @api'))
})

test('全部运行时镜像使用非 root 用户和最小 Linux 权限', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const backendDockerfile = await readFile(new URL('backend/Dockerfile', root), 'utf8')
  const workerDockerfile = await readFile(new URL('python-worker/Dockerfile', root), 'utf8')
  const frontendDockerfile = await readFile(new URL('frontend/Dockerfile', root), 'utf8')
  const caddyDockerfile = await readFile(new URL('caddy/Dockerfile', root), 'utf8')

  for (const dockerfile of [backendDockerfile, workerDockerfile, frontendDockerfile, caddyDockerfile]) {
    assert.match(dockerfile, /^USER\s+\S+/m)
  }
  for (const [service, nextService] of [
    ['backend', 'python-worker'],
    ['python-worker', 'frontend'],
    ['frontend', 'caddy'],
    ['caddy', null],
  ]) {
    const block = serviceBlock(compose, service, nextService)
    assert.match(block, /cap_drop:\s*\n\s+- ALL/)
    assert.match(block, /no-new-privileges:true/)
  }
  assert.match(serviceBlock(compose, 'caddy', null), /cap_add:\s*\n\s+- NET_BIND_SERVICE/)
})

test('供应链扫描固定 Action 提交并覆盖源码与容器镜像', async () => {
  const workflow = await readFile(new URL('.github/workflows/security-scan.yml', root), 'utf8')
  const dependabot = await readFile(new URL('.github/dependabot.yml', root), 'utf8')

  assert.doesNotMatch(workflow, /uses:\s+[^\s]+@v\d/)
  assert.match(workflow, /scanners: vuln,misconfig,secret/)
  assert.match(workflow, /scan-type: image/)
  assert.match(workflow, /severity: HIGH,CRITICAL/)
  for (const ecosystem of ['maven', 'npm', 'pip', 'docker', 'github-actions']) {
    assert.match(dependabot, new RegExp(`package-ecosystem: ${ecosystem}`))
  }
})
