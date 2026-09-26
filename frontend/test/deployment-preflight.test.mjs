import assert from 'node:assert/strict'
import { spawnSync } from 'node:child_process'
import { mkdtempSync, writeFileSync, readFileSync, rmSync } from 'node:fs'
import { tmpdir } from 'node:os'
import { join } from 'node:path'
import { fileURLToPath } from 'node:url'
import test from 'node:test'

const script = fileURLToPath(new URL('../../scripts/deploy-preflight.sh', import.meta.url))

/** 只隔离外部 Docker 命令，运行真实脚本并记录是否错误地继续部署。 */
function run(args, overrides = {}) {
  const directory = mkdtempSync(join(tmpdir(), 'baseai-preflight-test-'))
  try {
    writeFileSync(join(directory, 'docker'), `#!/bin/sh
printf '%s\\n' "$*" >> "$CALLS"
case "$*" in
  'info --format {{json .SecurityOptions}}')
    [ "$DAEMON_DOWN" = 1 ] && exit 1
    printf '%s\\n' "$SECURITY"
    ;;
  'compose config --quiet') exit "$CONFIG_EXIT" ;;
  'compose run '*) exit "$PROBE_EXIT" ;;
esac
`, { mode: 0o700 })
    const result = spawnSync('/bin/sh', [script, ...args], {
      encoding: 'utf8',
      env: { ...process.env, PATH: `${directory}:${process.env.PATH}`, CALLS: join(directory, 'calls'),
        SECURITY: '["name=rootless"]', CONFIG_EXIT: '0', PROBE_EXIT: '0', ...overrides },
    })
    let calls = ''
    try { calls = readFileSync(join(directory, 'calls'), 'utf8') } catch {}
    return { ...result, calls }
  } finally { rmSync(directory, { recursive: true, force: true }) }
}

test('host-only check validates Docker and Compose without starting containers', () => {
  const result = run(['--host-only'])
  assert.equal(result.status, 0, result.stderr)
  assert.match(result.calls, /compose config --quiet/)
  assert.doesNotMatch(result.calls, /compose run/)
})

for (const [name, env] of Object.entries({
  daemon: { DAEMON_DOWN: '1' }, rootful: { SECURITY: '["name=seccomp"]' }, configuration: { CONFIG_EXIT: '1' },
})) {
  test(`${name} failure blocks the dependency probe`, () => {
    const result = run([], env)
    assert.notEqual(result.status, 0)
    assert.doesNotMatch(result.calls, /compose run/)
    assert.match(result.stderr, /preflight/)
  })
}

test('dependency probe runs in the backend service network and propagates failure', () => {
  const result = run([], { PROBE_EXIT: '1' })
  assert.equal(result.status, 1)
  assert.match(result.calls, /compose run --rm --no-deps --entrypoint java backend -Dloader.main=com.baseai.platform.deployment.DeploymentPreflight/)
  assert.doesNotMatch(result.calls, /compose up/)
})

test('unknown options fail before interacting with Docker', () => {
  const result = run(['--typo'])
  assert.notEqual(result.status, 0)
  assert.equal(result.calls, '')
})
