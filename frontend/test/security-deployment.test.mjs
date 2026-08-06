import assert from 'node:assert/strict'
import { execFileSync, spawnSync } from 'node:child_process'
import { mkdirSync, mkdtempSync, rmSync, writeFileSync } from 'node:fs'
import { readFile } from 'node:fs/promises'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const root = new URL('../../', import.meta.url)
const caddyEntrypoint = fileURLToPath(new URL('caddy/caddy-entrypoint.sh', root))

/** 执行 Caddy 入口诊断模式，返回不包含证书路径的解析结果。 */
function resolveIngress(overrides) {
  return execFileSync('/bin/sh', [caddyEntrypoint, '--resolve-ingress'], {
    encoding: 'utf8',
    env: {
      ...process.env,
      APP_HTTPS_SITES_FILE: '',
      TLS_CERTS_DIR: '',
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
      APP_HTTPS_SITES_FILE: '',
      TLS_CERTS_DIR: '',
      CADDY_EXTERNAL_HTTP_PORT: '81',
      CADDY_EXTERNAL_HTTPS_PORT: '444',
      ...overrides,
    },
  })
}

/** 使用临时已学习地址文件执行断言，并在完成后清理测试数据。 */
function withLearnedHosts(contents, assertion) {
  const directory = mkdtempSync(join(tmpdir(), 'base-ai-learned-hosts-'))
  const hostsFile = join(directory, 'learned-hosts')
  writeFileSync(hostsFile, contents)
  try {
    return assertion(hostsFile)
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
}

/** 使用隔离的站点 YAML、TLS 根目录和解析器替身验证入口脚本消费规范化域名。 */
function withHTTPSSitesResolver(resolvedDomains, assertion) {
  const directory = mkdtempSync(join(tmpdir(), 'base-ai-https-sites-'))
  const sitesFile = join(directory, 'https-sites.yml')
  const tlsRoot = join(directory, 'tls')
  const resolver = join(directory, 'resolve-sites.sh')
  writeFileSync(sitesFile, 'sites: []\n')
  mkdirSync(tlsRoot)
  // 入口脚本只要求目录存在，证书内容由 Go 单元和运行态测试验证。
  writeFileSync(resolver, `#!/bin/sh
if [ "\${CADDY_TEST_RESOLVER_FAIL:-}" = 1 ]; then
  echo "invalid HTTPS sites configuration" >&2
  exit 1
fi
printf '%s\\n' "\${CADDY_TEST_RESOLVED_DOMAINS:-}"
`, { mode: 0o700 })
  try {
    return assertion({ sitesFile, tlsRoot, resolver })
  } finally {
    rmSync(directory, { recursive: true, force: true })
  }
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
  assert.match(envExample, /^APP_HTTPS_SITES_FILE=$/m)
  assert.match(envExample, /^TLS_CERTS_DIR=$/m)
  assert.doesNotMatch(envExample, /^APP_DOMAIN(_FILE)?=$/m)
  assert.doesNotMatch(envExample, /^TLS_(CERT|KEY)_FILE=$/m)
  assert.doesNotMatch(compose, /^\s+APP_DOMAIN(_FILE)?:/m)
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
  assert.doesNotMatch(serviceBlock(compose, 'caddy', null), /\.runtime|CADDY_DISCOVERED_HOSTS_FILE|HOST_IP_CHECK_INTERVAL_SECONDS/)
  assert.match(serviceBlock(compose, 'caddy', null), /CADDY_LEARNED_HOSTS_FILE:\s*\/data\/base-ai-tls\/learned-hosts/)
  assert.match(serviceBlock(compose, 'caddy', null), /IP_CERT_MAX_LEARNED_HOSTS:-32/)
  assert.match(serviceBlock(compose, 'caddy', null), /IP_CERT_MIN_ISSUE_INTERVAL_SECONDS:-5/)
  assert.match(serviceBlock(compose, 'caddy', null), /APP_HTTPS_SITES_FILE:-\.\/caddy\/https-sites-placeholder\.yml.*\/etc\/caddy\/https-sites\.yml:ro/)
  assert.match(serviceBlock(compose, 'caddy', null), /TLS_CERTS_DIR:-\.\/caddy\/tls-placeholder.*\/etc\/caddy\/tls:ro/)
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
  assert.match(caddyfile, /\{\$CADDY_HTTP_FALLBACK\}/)
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

test('Caddy 入口在域名证书与请求学习 IPv4 模式间严格切换', () => {
  const ipConfig = withLearnedHosts('203.0.113.10\n192.168.1.10\n', hostsFile => resolveIngress({
    CADDY_LEARNED_HOSTS_FILE: hostsFile,
  }))
  assert.match(ipConfig, /^mode=ip$/m)
  assert.match(ipConfig, /^hosts=localhost 127\.0\.0\.1 203\.0\.113\.10 192\.168\.1\.10$/m)
  assert.match(ipConfig, /^https_sites=https:\/\/localhost, https:\/\/127\.0\.0\.1, https:\/\/203\.0\.113\.10, https:\/\/192\.168\.1\.10$/m)
  assert.match(ipConfig, /^https_port_suffix=:444$/m)
  assert.match(ipConfig, /^default_sni=localhost$/m)
  assert.match(ipConfig, /^hsts=disabled$/m)

  const domainConfig = withHTTPSSitesResolver('ai.example.com api.example.com console.example.net', ({ sitesFile, tlsRoot, resolver }) => (
    withLearnedHosts('192.168.1.10\n', hostsFile => resolveIngress({
      CADDY_LEARNED_HOSTS_FILE: hostsFile,
      APP_HTTPS_SITES_FILE: '/srv/config/https-sites.yml',
      TLS_CERTS_DIR: '/srv/tls',
      CADDY_HTTPS_SITES_FILE: sitesFile,
      CADDY_TLS_ROOT: tlsRoot,
      CADDY_INGRESS_HELPER: resolver,
      CADDY_TEST_RESOLVED_DOMAINS: 'ai.example.com api.example.com console.example.net',
      CADDY_EXTERNAL_HTTP_PORT: '80',
      CADDY_EXTERNAL_HTTPS_PORT: '443',
    }))
  ))
  assert.match(domainConfig, /^mode=domain$/m)
  assert.match(domainConfig, /^hosts=ai\.example\.com api\.example\.com console\.example\.net$/m)
  assert.match(domainConfig, /^https_sites=https:\/\/ai\.example\.com, https:\/\/api\.example\.com, https:\/\/console\.example\.net$/m)
  assert.match(domainConfig, /^https_port_suffix=$/m)
  assert.match(domainConfig, /^default_sni=$/m)
  assert.match(domainConfig, /^hsts=enabled$/m)
})

test('IP 学习完全位于 Caddy 容器且不调用宿主机网络命令', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const entrypoint = await readFile(new URL('caddy/caddy-entrypoint.sh', root), 'utf8')
  const helper = await readFile(new URL('caddy/ip-cert-helper.go', root), 'utf8')

  assert.doesNotMatch(`${compose}\n${entrypoint}`, /uname|ifconfig|route -n|ip -4|host-ips|\.runtime/)
  assert.match(entrypoint, /--serve/)
  assert.match(entrypoint, /--mode "\$CADDY_INGRESS_MODE"/)
  assert.match(helper, /127\.0\.0\.1:2020/)
  assert.match(helper, /StatusPermanentRedirect/)
  assert.match(helper, /StatusTooManyRequests/)
})

test('Caddy 入口拒绝不完整证书、非法学习地址和越界端口', () => {
  for (const [overrides, message] of [
    [{ APP_HTTPS_SITES_FILE: '/srv/config/https-sites.yml' }, 'must be configured together'],
    [{ CADDY_EXTERNAL_HTTPS_PORT: '65536' }, 'must be between 1 and 65535'],
  ]) {
    const result = rejectIngress(overrides)
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, new RegExp(message))
  }

  for (const address of ['192.168.1.256', '192.168.001.10', 'host.example']) {
    const result = withLearnedHosts(`${address}\n`, hostsFile => rejectIngress({
      CADDY_LEARNED_HOSTS_FILE: hostsFile,
    }))
    assert.notEqual(result.status, 0)
    assert.match(result.stderr, /learned host address must be a valid IPv4 address/)
  }
})

test('Caddy 入口拒绝缺失、空白或解析失败的 HTTPS 站点 YAML', () => {
  const missing = rejectIngress({
    APP_HTTPS_SITES_FILE: '/srv/config/https-sites.yml',
    TLS_CERTS_DIR: '/srv/tls',
    CADDY_HTTPS_SITES_FILE: '/missing/https-sites.yml',
    CADDY_TLS_ROOT: '/tmp',
  })
  assert.notEqual(missing.status, 0)
  assert.match(missing.stderr, /readable regular YAML file/)

  for (const [resolvedDomains, fail, message] of [
    ['', false, 'at least one domain'],
    ['ai.example.com', true, 'invalid HTTPS sites configuration'],
  ]) {
    withHTTPSSitesResolver(resolvedDomains, ({ sitesFile, tlsRoot, resolver }) => {
      const result = rejectIngress({
        APP_HTTPS_SITES_FILE: '/srv/config/https-sites.yml',
        TLS_CERTS_DIR: '/srv/tls',
        CADDY_HTTPS_SITES_FILE: sitesFile,
        CADDY_TLS_ROOT: tlsRoot,
        CADDY_INGRESS_HELPER: resolver,
        CADDY_TEST_RESOLVED_DOMAINS: resolvedDomains,
        CADDY_TEST_RESOLVER_FAIL: fail ? '1' : '',
      })
      assert.notEqual(result.status, 0)
      assert.match(result.stderr, new RegExp(message))
    })
  }
})

test('Caddy 在公网入口拒绝内部服务接口', async () => {
  const caddyfile = await readFile(new URL('Caddyfile', root), 'utf8')

  assert.match(caddyfile, /@internal\s+path\s+\/api\/internal\s+\/api\/internal\/\*/)
  assert.match(caddyfile, /handle\s+@internal\s*\{\s*respond\s+"Not Found"\s+404\s*\}/s)
  assert.ok(caddyfile.indexOf('handle @internal') < caddyfile.indexOf('handle @api'))
})

test('Caddy 构建使用可配置的 Go 模块代理', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const caddyDockerfile = await readFile(new URL('caddy/Dockerfile', root), 'utf8')
  const environmentExample = await readFile(new URL('.env.example', root), 'utf8')

  assert.match(caddyDockerfile, /^ARG GOPROXY=https:\/\/goproxy\.cn,direct$/m)
  assert.match(caddyDockerfile, /^ENV GOPROXY=\$\{GOPROXY\}$/m)
  assert.match(compose, /^\s+GOPROXY: \$\{GOPROXY:-https:\/\/goproxy\.cn,direct\}$/m)
  assert.match(environmentExample, /^GOPROXY=https:\/\/goproxy\.cn,direct$/m)
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
  assert.match(caddyEntrypointSource, /base-ai-ip-cert.*\\?\s*--serve/s)
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
