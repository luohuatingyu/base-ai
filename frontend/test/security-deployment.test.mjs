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

test('容器名称使用平台简称的小写项目名前缀', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const envExample = await readFile(new URL('.env.example', root), 'utf8')

  assert.match(compose, /^name: \${APP_PLATFORM_SHORT_NAME:\?Set APP_PLATFORM_SHORT_NAME}$/m)
  assert.doesNotMatch(envExample, /^COMPOSE_PROJECT_NAME=/m)
  assert.match(envExample, /^HTTP_PORT=80$/m)
  assert.match(envExample, /^APP_SESSION_COOKIE_SECURE=false$/m)
  for (const [service, nextService] of [
    ['backend', 'python-worker'],
    ['python-worker', 'frontend'],
    ['frontend', null],
  ]) {
    assert.match(
      serviceBlock(compose, service, nextService),
      new RegExp(`container_name: \\$\\{COMPOSE_PROJECT_NAME:\\?Set COMPOSE_PROJECT_NAME\\}-${service}`),
    )
  }
})

test('Frontend 接管 HTTP 入口并不保留 Caddy 服务', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const frontend = serviceBlock(compose, 'frontend', null)
  const backend = serviceBlock(compose, 'backend', 'python-worker')

  assert.doesNotMatch(backend, /\n\s+ports:/)
  assert.match(frontend, /- "\${HTTP_PORT:-80}:8080"/)
  assert.match(frontend, /test: \["CMD", "wget", "-qO-", "http:\/\/127\.0\.0\.1:8080\/health"\]/)
  assert.doesNotMatch(compose, /^\s{2}caddy:/m)
  assert.doesNotMatch(backend, /API_TRIGGER_CADDY_CA_FILE|caddy-public-ca/)
  assert.match(compose, /APP_TRUSTED_PROXY_CIDRS: \$\{APP_TRUSTED_PROXY_CIDRS:-\$\{APP_NETWORK_SUBNET:-172\.30\.0\.0\/24\}\}/)
})

test('Frontend 入口保留安全响应头、API 代理和 Agent 静态资源', async () => {
  const server = await readFile(new URL('../server.mjs', import.meta.url), 'utf8')

  assert.match(server, /content-security-policy/)
  assert.match(server, /x-content-type-options/)
  assert.match(server, /x-frame-options/)
  assert.match(server, /request\.url\.startsWith\('\/api\/'\)/)
  assert.match(server, /agent-dist/)
  assert.match(server, /APP_MAX_REQUEST_BYTES/)
})

test('Frontend 入口拒绝公网访问内部接口', async () => {
  const server = await readFile(new URL('../server.mjs', import.meta.url), 'utf8')

  assert.match(server, /parsed\.pathname === '\/api\/internal'/)
  assert.match(server, /parsed\.pathname\.startsWith\('\/api\/internal\/'\)/)
  assert.match(server, /sendResponse\(response, 404/)
})

test('Frontend 构建使用可配置的 Alpine 镜像和设备 Agent 运行时', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const frontendDockerfile = await readFile(new URL('frontend/Dockerfile', root), 'utf8')
  const environmentExample = await readFile(new URL('.env.example', root), 'utf8')

  assert.match(frontendDockerfile, /^ARG ALPINE_MIRROR=https:\/\/mirrors\.tuna\.tsinghua\.edu\.cn\/alpine$/m)
  assert.match(frontendDockerfile, /aarch64-apple-darwin-install_only/)
  assert.match(frontendDockerfile, /x86_64-apple-darwin-install_only/)
  assert.match(compose, /^\s+ALPINE_MIRROR: \$\{ALPINE_MIRROR:-https:\/\/dl-cdn\.alpinelinux\.org\/alpine\}$/m)
  assert.match(compose, /agent-src: \.\/device-agent/)
  assert.match(environmentExample, /^GOPROXY=https:\/\/goproxy\.cn,direct$/m)
  assert.match(environmentExample, /^ALPINE_MIRROR=https:\/\/mirrors\.tuna\.tsinghua\.edu\.cn\/alpine$/m)
})

test('运行时镜像采用受控系统更新，Dify Worker 不保留不确定的 apt 升级', async () => {
  const workerDockerfile = await readFile(new URL('python-worker/Dockerfile', root), 'utf8')
  const difyWorkerDockerfile = await readFile(new URL('dify-plugin-worker/Dockerfile', root), 'utf8')
  const difyRequirements = await readFile(new URL('dify-plugin-worker/requirements.txt', root), 'utf8')
  const frontendDockerfile = await readFile(new URL('frontend/Dockerfile', root), 'utf8')
  const n8nWorkerDockerfile = await readFile(new URL('n8n-plugin-worker/Dockerfile', root), 'utf8')
  const n8nPackageStore = await readFile(new URL('n8n-plugin-worker/app/package-store.mjs', root), 'utf8')

  assert.match(workerDockerfile, /apt-get update\s*\\/)
  assert.match(workerDockerfile, /apt-get upgrade -y\s*\\/)
  assert.match(workerDockerfile, /rm -rf \/var\/lib\/apt\/lists\/\*/)
  assert.match(difyWorkerDockerfile, /pip install --no-cache-dir -r requirements\.txt/)
  assert.doesNotMatch(difyWorkerDockerfile, /apt-get (?:update|upgrade)/)
  assert.match(difyRequirements, /^Werkzeug==3\.1\.6$/m)
  for (const dockerfile of [frontendDockerfile, n8nWorkerDockerfile]) assert.match(dockerfile, /apk upgrade --no-cache\s*\\/)
  assert.match(frontendDockerfile, /rm -rf \/usr\/local\/lib\/node_modules\/npm/)
  assert.match(frontendDockerfile, /rm -f \/usr\/local\/bin\/npm/)
  assert.match(n8nWorkerDockerfile, /npm install --global --ignore-scripts npm@12\.0\.2/)
  assert.match(n8nWorkerDockerfile, /dependencies\.tar = ["']7\.5\.21["']/)
  assert.match(n8nWorkerDockerfile, /["']brace-expansion["']:\s*["']5\.0\.9["']/)
  assert.match(n8nWorkerDockerfile, /["']ip-address["']:\s*["']10\.3\.1["']/)
  assert.doesNotMatch(n8nWorkerDockerfile, /rm -rf[^\n]*node_modules\/npm/)
  assert.match(n8nWorkerDockerfile, /node_modules\/corepack/)
  assert.match(n8nPackageStore, /'--ignore-scripts'/)
  assert.match(n8nPackageStore, /DEPENDENCY_VERSION_NOT_PINNED/)
})

test('全部运行时镜像使用非 root 用户和最小 Linux 权限', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const backendDockerfile = await readFile(new URL('backend/Dockerfile', root), 'utf8')
  const workerDockerfile = await readFile(new URL('python-worker/Dockerfile', root), 'utf8')
  const frontendDockerfile = await readFile(new URL('frontend/Dockerfile', root), 'utf8')

  for (const dockerfile of [backendDockerfile, workerDockerfile, frontendDockerfile]) {
    assert.match(dockerfile, /^USER\s+\S+/m)
  }
  assert.doesNotMatch(backendDockerfile, /caddy-public-ca/)
  for (const [service, nextService] of [
    ['backend', 'python-worker'],
    ['python-worker', 'frontend'],
    ['frontend', null],
  ]) {
    const block = serviceBlock(compose, service, nextService)
    assert.match(block, /cap_drop:\s*\n\s+- ALL/)
    assert.match(block, /no-new-privileges:true/)
  }
})

test('适配器 broker 包含校验来源的 Docker CLI 与 Compose 插件及最小 Worker 凭据', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const dockerfile = await readFile(new URL('adapter-manager/Dockerfile', root), 'utf8')
  const broker = serviceBlock(compose, 'adapter-docker-broker', 'adapter-supervisor')

  assert.match(dockerfile, /^ARG DOCKER_CLI_VERSION=29\.7\.2$/m)
  assert.match(dockerfile, /^ARG DOCKER_CLI_SOURCE_SHA256=[a-f0-9]{64}$/m)
  assert.match(dockerfile, /docker\/cli\/archive\/refs\/tags\/v\$\{DOCKER_CLI_VERSION\}\.tar\.gz/)
  assert.match(dockerfile, /^ARG DOCKER_COMPOSE_VERSION=5\.4\.0$/m)
  assert.match(dockerfile, /^ARG DOCKER_COMPOSE_LINUX_AMD64_SHA256=[a-f0-9]{64}$/m)
  assert.match(dockerfile, /^ARG DOCKER_COMPOSE_LINUX_ARM64_SHA256=[a-f0-9]{64}$/m)
  assert.match(dockerfile, /docker\/compose\/releases\/download\/v\$\{DOCKER_COMPOSE_VERSION\}/)
  assert.match(dockerfile, /sha256sum -c/)
  assert.match(dockerfile, /\/usr\/local\/lib\/docker\/cli-plugins\/docker-compose/)
  assert.match(dockerfile, /^FROM scratch AS broker$/m)
  assert.doesNotMatch(dockerfile, /DOCKER_CLI_IMAGE/)
  assert.doesNotMatch(compose, /DOCKER_CLI_IMAGE/)
  assert.match(broker, /DIFY_PLUGIN_WORKER_INTERNAL_TOKEN: \$\{DIFY_PLUGIN_WORKER_INTERNAL_TOKEN:\?Set DIFY_PLUGIN_WORKER_INTERNAL_TOKEN\}/)
  assert.match(broker, /N8N_PLUGIN_WORKER_INTERNAL_TOKEN: \$\{N8N_PLUGIN_WORKER_INTERNAL_TOKEN:\?Set N8N_PLUGIN_WORKER_INTERNAL_TOKEN\}/)
  for (const platformSecret of ['MYSQL_PASSWORD', 'POSTGRES_PASSWORD', 'REDIS_PASSWORD', 'APP_TOKEN_SECRET', 'APP_CONFIG_ENCRYPTION_KEY']) {
    assert.doesNotMatch(broker, new RegExp(`${platformSecret}:`), platformSecret)
  }
})

test('文档解析器使用无网络只读容器且 Backend 仅只读共享 Unix Socket', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const parser = serviceBlock(compose, 'document-parser', 'backend')
  const backend = serviceBlock(compose, 'backend', 'python-worker')

  assert.match(parser, /target: document-parser/)
  assert.match(parser, /network_mode: none/)
  assert.match(parser, /read_only: true/)
  assert.match(parser, /cap_drop:\s*\n\s+- ALL/)
  assert.match(parser, /no-new-privileges:true/)
  assert.match(parser, /mem_limit:/)
  assert.match(parser, /pids_limit:/)
  assert.doesNotMatch(parser, /MYSQL_|POSTGRES_|REDIS_|INTERNAL_TOKEN/)
  assert.match(parser, /document-parser-control:\/run\/document-parser/)
  assert.match(backend, /document-parser-control:\/run\/document-parser:ro/)
  assert.match(backend, /document-parser:\s*\n\s+condition: service_healthy/)
})

test('全部运行容器具备 CPU、内存和 PID 上限', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const services = [
    'document-parser', 'backend', 'python-worker', 'adapter-docker-broker', 'adapter-supervisor',
    'adapter-manager', 'outbound-gateway', 'dify-plugin-worker', 'n8n-plugin-worker', 'frontend',
  ]
  for (let index = 0; index < services.length; index += 1) {
    const block = serviceBlock(compose, services[index], services[index + 1] ?? null)
    assert.match(block, /\n\s+mem_limit:/, services[index])
    assert.match(block, /\n\s+cpus:/, services[index])
    assert.match(block, /\n\s+pids_limit:/, services[index])
  }
})

test('自建镜像使用必填 Git revision 标签并写入 OCI 元数据', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const adapterCompose = await readFile(new URL('adapter-manager/adapter-compose.yml', root), 'utf8')
  const testWorkflow = await readFile(new URL('.github/workflows/test.yml', root), 'utf8')
  const scanWorkflow = await readFile(new URL('.github/workflows/security-scan.yml', root), 'utf8')
  const dockerfiles = await Promise.all([
    'backend/Dockerfile', 'python-worker/Dockerfile', 'adapter-manager/Dockerfile',
    'outbound-gateway/Dockerfile', 'dify-plugin-worker/Dockerfile', 'n8n-plugin-worker/Dockerfile',
    'frontend/Dockerfile', 'deployment-agent/Dockerfile',
  ].map(path => readFile(new URL(path, root), 'utf8')))
  const imageLines = compose.match(/^\s+image:.*$/gm) ?? []
  const revisionImageLines = imageLines.filter(line => line.includes('${APP_IMAGE_REVISION'))

  assert.equal(revisionImageLines.length, 11)
  revisionImageLines.forEach(line => assert.match(line, /:\$\{APP_IMAGE_REVISION:\?Set APP_IMAGE_REVISION to Git commit\}$/))
  assert.doesNotMatch(`${compose}\n${adapterCompose}`, /image:[^\n]*:latest/)
  assert.equal((compose.match(/^ {8}APP_IMAGE_REVISION: \$\{APP_IMAGE_REVISION:\?Set APP_IMAGE_REVISION to Git commit\}$/gm) ?? []).length, revisionImageLines.length)
  for (const name of ['adapter-manager', 'adapter-docker-broker']) {
    const block = compose.split(`\n  ${name}:\n`)[1].split(/\n {2}[a-z][a-z-]+:\n/)[0]
    assert.match(block, /^ {6}APP_IMAGE_REVISION: \$\{APP_IMAGE_REVISION:\?Set APP_IMAGE_REVISION to Git commit\}$/m)
  }
  dockerfiles.forEach(source => assert.match(source, /LABEL org\.opencontainers\.image\.revision=\$\{APP_IMAGE_REVISION\}/))
  assert.match(testWorkflow, /APP_IMAGE_REVISION: \$\{\{ github\.sha \}\}/)
  assert.match(scanWorkflow, /APP_IMAGE_REVISION: \$\{\{ github\.sha \}\}/)
  assert.match(scanWorkflow, /--build-arg "APP_IMAGE_REVISION=\$APP_IMAGE_REVISION"/)
})

test('后端接收精确 CORS 白名单和敏感回查限流配置', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const backend = serviceBlock(compose, 'backend', 'python-worker')

  assert.match(backend, /APP_CORS_ALLOWED_ORIGINS: \$\{APP_CORS_ALLOWED_ORIGINS:-\}/)
  assert.match(backend, /APP_SECRET_REVEAL_FAILURES:/)
  assert.match(backend, /APP_SECRET_REVEAL_WINDOW_MINUTES:/)
  assert.match(backend, /APP_SECRET_REVEAL_BLOCK_MINUTES:/)
})

test('部署环境模板覆盖 Compose 的全部可配置项', async () => {
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const environmentExample = await readFile(new URL('.env.example', root), 'utf8')
  const variables = new Set([...compose.matchAll(/\$\{([A-Z][A-Z0-9_]*)/g)].map(match => match[1]))
  variables.delete('COMPOSE_PROJECT_NAME')
  for (const variable of variables) {
    assert.match(environmentExample, new RegExp(`^${variable}=`, 'm'), variable)
  }
})

test('供应链扫描固定 Action 提交并覆盖源码与容器镜像', async () => {
  const workflow = await readFile(new URL('.github/workflows/security-scan.yml', root), 'utf8')
  const dependabot = await readFile(new URL('.github/dependabot.yml', root), 'utf8')
  const compose = await readFile(new URL('docker-compose.yml', root), 'utf8')
  const environmentExample = await readFile(new URL('.env.example', root), 'utf8')

  assert.doesNotMatch(workflow, /uses:\s+[^\s]+@v\d/)
  assert.match(workflow, /scanners: vuln,misconfig,secret/)
  assert.match(workflow, /scan-type: image/)
  assert.match(workflow, /severity: HIGH,CRITICAL/)
  assert.match(workflow, /image: document-parser[\s\S]*target: document-parser/)
  const imageArguments = [...compose.matchAll(/^\s+[A-Z_]+_IMAGE:\s+(\S+)$/gm)].map(match => match[1])
  assert.ok(imageArguments.length >= 10)
  imageArguments.forEach(image => assert.match(image, /@sha256:[a-f0-9]{64}$/))
  assert.doesNotMatch(compose, /\$\{(?:MAVEN|JRE|NODE|PYTHON|GO|DOCKER_CLI|CADDY)_IMAGE/)
  assert.doesNotMatch(environmentExample, /^(?:MAVEN|JRE|NODE|PYTHON|GO|DOCKER_CLI|CADDY)_IMAGE=/m)
  for (const ecosystem of ['maven', 'npm', 'pip', 'docker', 'github-actions']) {
    assert.match(dependabot, new RegExp(`package-ecosystem: ${ecosystem}`))
  }
})
