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

test('仅 Caddy 暴露 HTTP 和 HTTPS 端口', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')

  assert.doesNotMatch(serviceBlock(compose, 'backend', 'python-worker'), /\n\s+ports:/)
  assert.doesNotMatch(serviceBlock(compose, 'frontend', 'caddy'), /\n\s+ports:/)
  assert.match(serviceBlock(compose, 'caddy', null), /HTTP_PORT/)
  assert.match(serviceBlock(compose, 'caddy', null), /HTTPS_PORT/)
  assert.match(compose, /APP_TRUSTED_PROXY_CIDRS:.*CADDY_INTERNAL_IP/)
  assert.match(compose, /caddy:2\.11\.2-alpine/)
})

test('Caddy 为控制台和开放 API 提供 TLS 与安全响应头', async () => {
  const caddyfile = await readFile(new URL('Caddyfile', root), 'utf8')

  assert.match(caddyfile, /tls internal/)
  assert.match(caddyfile, /Strict-Transport-Security/)
  assert.match(caddyfile, /Content-Security-Policy/)
  assert.match(caddyfile, /X-Content-Type-Options/)
  assert.match(caddyfile, /X-Frame-Options/)
  assert.match(caddyfile, /@api path \/api\/\*/)
  assert.match(caddyfile, /reverse_proxy backend:8080/)
})
