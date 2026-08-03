import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

const source = readFileSync(new URL('../src/views/MailRoutesView.vue', import.meta.url), 'utf8')

test('邮件收件人和抄送人使用可新增删除的独立输入行', () => {
  assert.match(source, /v-for="\(_, index\) in toRows"/)
  assert.match(source, /v-for="\(_, index\) in ccRows"/)
  assert.match(source, /@click="addAddress\(toRows\)"/)
  assert.match(source, /@click="deleteAddress\(ccRows, index\)"/)
  assert.doesNotMatch(source, /type="textarea"/)
})

test('DEFAULT 路由固定业务编码、名称和启用状态且不提供删除入口', () => {
  assert.match(source, /:disabled="form\.businessCode === 'DEFAULT'"/)
  assert.match(source, /v-model="form\.name" :disabled="form\.businessCode === 'DEFAULT'"/)
  assert.match(source, /scope\.row\.businessCode !== 'DEFAULT'/)
  assert.match(source, /if \(payload\.businessCode === 'DEFAULT'\) payload\.enabled = true/)
})

test('邮件路由测试受更新权限保护并阻止待配置路由发送', () => {
  assert.match(source, /v-if="auth\.hasPermission\('mail:route:update'\)"[^>]+:disabled="!scope\.row\.configured"[^>]+@click="testRoute\(scope\.row\)"/)
  assert.match(source, /http\.post\(`\/mail\/routes\/\$\{row\.id\}\/test`, null, \{ silentError: true \}\)/)
  assert.match(source, /showHttpError\(error, 'mailRoutes\.testFailed'\)/)
  assert.match(source, /:loading="testingId === scope\.row\.id"/)
})
