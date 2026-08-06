import assert from 'node:assert/strict'
import { readFile } from 'node:fs/promises'
import test from 'node:test'

test('管理端使用 Cookie 会话且不再持久化 Bearer Token', async () => {
  const [http, auth] = await Promise.all([
    readFile(new URL('../src/api/http.js', import.meta.url), 'utf8'),
    readFile(new URL('../src/stores/auth.js', import.meta.url), 'utf8')
  ])

  assert.doesNotMatch(http, /localStorage/)
  assert.doesNotMatch(auth, /localStorage\.(?:getItem|setItem)/)
  assert.match(auth, /localStorage\.removeItem/)
  assert.doesNotMatch(http, /Authorization\s*=/)
  assert.match(http, /X-CSRF-Token/)
  assert.match(http, /appConfig\.code\.replace/)
  assert.match(auth, /initialized/)
  assert.match(auth, /fetchMe/)
})

test('路由初始化时恢复服务端 Cookie 会话', async () => {
  const router = await readFile(new URL('../src/router/index.js', import.meta.url), 'utf8')

  assert.match(router, /auth\.initialized/)
  assert.match(router, /auth\.fetchMe/)
})
