import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, writeFileSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const root = new URL('../../', import.meta.url)
const caddyEntrypoint = fileURLToPath(new URL('caddy/caddy-entrypoint.sh', root))
const hostIpTracker = fileURLToPath(new URL('scripts/base-ai.sh', root))

/** 执行 Caddy 入口诊断模式，返回不包含证书路径的解析结果。 */
function resolveIngress(overrides) {
  return execFileSync('/bin/sh', [caddyEntrypoint, '--resolve-ingress'], {
    encoding: 'utf8',
    env: {
      ...process.env,
      APP_DOMAIN: '',
      TLS_CERT_FILE: '',
      TLS_KEY_FILE: '',
      CADDY_EXTERNAL_HTTP_PORT: '81',
      CADDY_EXTERNAL_HTTPS_PORT: '444',
      ...overrides,
    },
  })
}

/** 执行预期失败的入口配置，验证不会静默降级。 */
function rejectIngress(overrides) {
  return spawnSync('/bin/sh', [caddyEntrypoint, '--resolve-ingress'], {
    encoding: 'utf8',
    env: {
      ...process.env,
      APP_DOMAIN: '',
      TLS_CERT_FILE: '',
      TLS_KEY_FILE: '',
      CADDY_EXTERNAL_HTTP_PORT: '81',
      CADDY_EXTERNAL_HTTPS_PORT: '444',
      ...overrides,
    },
  })
}

/** 使用临时宿主机地址文件执行断言，并在完成后清理测试数据。 */
function withDiscoveredHosts(contents, assertion) {
  const directory = mkdtempSync(join(tmpdir(), 'base-ai-hosts-'))
  const hostsFile = join(directory, 'host-ips')
  writeFileSync(hostsFile, contents)
  try {
    return assertion(hostsFile)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

/** 写入可执行的命令替身，用于隔离宿主机网络检测。 */
function writeCommand(directory, name, source) {
  const path = join(directory, name)
  writeFileSync(path, `#!/bin/sh\n${source}\n`)
  chmodSync(path, 0o755)
}

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

test('仅 Caddy 暴露 HTTP 和 HTTPS 端口并持久化内部 CA', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')

  assert.doesNotMatch(serviceBlock(compose, 'backend', 'python-worker'), /\n\s+ports:/)
  assert.doesNotMatch(serviceBlock(compose, 'frontend', 'caddy'), /\n\s+ports:/)
  assert.match(serviceBlock(compose, 'caddy', null), /HTTP_PORT:-81/)
  assert.match(serviceBlock(compose, 'caddy', null), /HTTPS_PORT:-444/)
  assert.match(serviceBlock(compose, 'caddy', null), /caddy-data:\/data/)
  assert.match(serviceBlock(compose, 'caddy', null), /caddy-config:\/config/)
  assert.match(serviceBlock(compose, 'caddy', null), /\.runtime:\/run\/base-ai:ro/)
  assert.match(serviceBlock(compose, 'caddy', null), /CADDY_DISCOVERED_HOSTS_FILE:\s*\/run\/base-ai\/host-ips/)
  assert.match(serviceBlock(compose, 'caddy', null), /tls-placeholder\.pem.*fullchain\.pem:ro/)
  assert.match(serviceBlock(compose, 'caddy', null), /tls-placeholder\.pem.*privkey\.pem:ro/)
  assert.match(serviceBlock(compose, 'backend', 'python-worker'), /APP_SESSION_COOKIE_SECURE: \$\{APP_SESSION_COOKIE_SECURE:-true\}/)
  assert.match(compose, /APP_TRUSTED_PROXY_CIDRS:.*CADDY_INTERNAL_IP/)
  assert.match(compose, /caddy:2\.11\.4-alpine/)
})

test('Caddy 将允许的 HTTP Host 跳转到 HTTPS 并保留安全响应头', async () => {
  const caddyfile = await readFile(new URL('Caddyfile', root), 'utf8')
  const bootstrap = await readFile(new URL('caddy/Caddyfile.bootstrap', root), 'utf8')

  assert.match(caddyfile, /:80/)
  assert.match(caddyfile, /CADDY_TLS_DIRECTIVE/)
  assert.match(caddyfile, /CADDY_DEFAULT_SNI_OPTION/)
  assert.match(caddyfile, /CADDY_HSTS_HEADER/)
  assert.match(caddyfile, /intermediate_lifetime 397d/)
  assert.match(bootstrap, /intermediate_lifetime 397d/)
  assert.match(caddyfile, /Content-Security-Policy/)
  assert.match(caddyfile, /X-Content-Type-Options/)
  assert.match(caddyfile, /X-Frame-Options/)
  assert.match(caddyfile, /@allowedHost host \{\$CADDY_ALLOWED_HOSTS\}/)
  assert.match(caddyfile, /redir https:\/\/\{host\}\{\$CADDY_HTTPS_PORT_SUFFIX\}\{uri\} 308/)
  assert.match(caddyfile, /@api path \/api\/\*/)
  assert.match(caddyfile, /reverse_proxy backend:8080/)
  assert.match(caddyfile, /reverse_proxy frontend:8080/)
})

test('Caddy IP 模式始终覆盖 localhost 和 IPv4 回环地址', () => {
  const config = resolveIngress({})

  assert.match(config, /^mode=ip$/m)
  assert.match(config, /^hosts=localhost 127\.0\.0\.1$/m)
  assert.match(config, /^https_sites=https:\/\/localhost, https:\/\/127\.0\.0\.1$/m)
  assert.match(config, /^default_sni=localhost$/m)
})

test('Caddy 入口在域名证书与自动发现 IPv4 模式间严格切换', () => {
  const ipConfig = withDiscoveredHosts('203.0.113.10\n192.168.1.10\n', hostsFile => resolveIngress({
    CADDY_DISCOVERED_HOSTS_FILE: hostsFile,
  }))
  assert.match(ipConfig, /^mode=ip$/m)
  assert.match(ipConfig, /^hosts=localhost 127\.0\.0\.1 203\.0\.113\.10 192\.168\.1\.10$/m)
  assert.match(ipConfig, /^https_sites=https:\/\/localhost, https:\/\/127\.0\.0\.1, https:\/\/203\.0\.113\.10, https:\/\/192\.168\.1\.10$/m)
  assert.match(ipConfig, /^https_port_suffix=:444$/m)
  assert.match(ipConfig, /^default_sni=localhost$/m)
  assert.match(ipConfig, /^hsts=disabled$/m)

  const domainConfig = withDiscoveredHosts('192.168.1.10\n', hostsFile => resolveIngress({
    CADDY_DISCOVERED_HOSTS_FILE: hostsFile,
    APP_DOMAIN: 'ai.example.com',
    TLS_CERT_FILE: '/srv/tls/fullchain.pem',
    TLS_KEY_FILE: '/srv/tls/privkey.pem',
    CADDY_EXTERNAL_HTTP_PORT: '80',
    CADDY_EXTERNAL_HTTPS_PORT: '443',
  }))
  assert.match(domainConfig, /^mode=domain$/m)
  assert.match(domainConfig, /^hosts=ai\.example\.com$/m)
  assert.match(domainConfig, /^https_sites=https:\/\/ai\.example\.com$/m)
  assert.match(domainConfig, /^https_port_suffix=$/m)
  assert.match(domainConfig, /^default_sni=$/m)
  assert.match(domainConfig, /^hsts=enabled$/m)
})

test('宿主机跟踪器自动识别默认网卡 IPv4 并避免写入虚拟隧道', () => {
  const directory = mkdtempSync(join(tmpdir(), 'base-ai-network-'))
  const commands = join(directory, 'bin')
  const runtime = join(directory, 'runtime')
  mkdirSync(commands)
  try {
    writeCommand(commands, 'uname', 'printf "Darwin\\n"')
    writeCommand(commands, 'route', 'printf "   interface: en7\\n"')
    writeCommand(commands, 'ifconfig', `cat <<'EOF'\ninet 192.168.0.44 netmask 0xffffff00 broadcast 192.168.0.255\ninet 198.18.0.1 netmask 0xffffff00\nEOF`)
    const environment = {
      ...process.env,
      BASE_AI_RUNTIME_DIR: runtime,
      PATH: `${commands}:/usr/bin:/bin:/usr/sbin:/sbin`,
    }
    const output = execFileSync('/bin/sh', [hostIpTracker, 'detect'], {
      encoding: 'utf8',
      env: environment,
    })
    assert.equal(output, '192.168.0.44\n')

    execFileSync('/bin/sh', [hostIpTracker, 'refresh'], { env: environment })
    assert.equal(readFileSync(join(runtime, 'host-ips'), 'utf8'), '192.168.0.44\n')
    writeCommand(commands, 'ifconfig', 'printf "inet 192.168.0.45 netmask 0xffffff00\\n"')
    execFileSync('/bin/sh', [hostIpTracker, 'refresh'], { env: environment })
    assert.equal(readFileSync(join(runtime, 'host-ips'), 'utf8'), '192.168.0.45\n')
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
})

test('Caddy 入口拒绝不完整证书、非法发现地址和越界端口', () => {
  for (const [overrides, message] of [
    [{ APP_DOMAIN: 'ai.example.com' }, 'must be configured together'],
    [{ CADDY_EXTERNAL_HTTPS_PORT: '65536' }, 'must be between 1 and 65535'],
  ]) {
    const result = rejectIngress(overrides)
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, new RegExp(message))
  }

  for (const address of ['192.168.1.256', '192.168.001.10', 'host.example']) {
    const result = withDiscoveredHosts(`${address}\n`, hostsFile => rejectIngress({
      CADDY_DISCOVERED_HOSTS_FILE: hostsFile,
    }))
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /discovered host address must be a valid IPv4 address/)
  }
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
  const caddyEntrypointSource = await readFile(new URL('caddy/caddy-entrypoint.sh', root), 'utf8')
  const ipCertificateHelper = await readFile(new URL('caddy/ip-cert-helper.go', root), 'utf8')

  for (const dockerfile of [backendDockerfile, workerDockerfile, frontendDockerfile, caddyDockerfile]) {
    assert.match(dockerfile, /^USER\s+\S+/m)
  }
  assert.match(caddyDockerfile, /^ENTRYPOINT \["\/usr\/local\/bin\/base-ai-caddy-entrypoint"\]$/m)
  assert.match(caddyDockerfile, /^CMD \["run", "--config", "\/etc\/caddy\/Caddyfile", "--adapter", "caddyfile"\]$/m)
  assert.match(caddyDockerfile, /base-ai-ip-cert/)
  assert.match(caddyEntrypointSource, /caddy reload --force.*--address 127\.0\.0\.1:2019/)
  assert.match(ipCertificateHelper, /ExtKeyUsageServerAuth/)
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
