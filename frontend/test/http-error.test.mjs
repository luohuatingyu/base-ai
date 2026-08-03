import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  classifyHttpError,
  isCancelledRequest,
  isHttpErrorNotified,
  markHttpErrorNotified,
  normalizeHttpErrorResponse,
  silenceHttpError
} from '../src/utils/httpError.js'

const t = key => `i18n:${key}`
const httpSource = readFileSync(new URL('../src/api/http.js', import.meta.url), 'utf8')
const mailAccountSource = readFileSync(new URL('../src/views/MailAccountsView.vue', import.meta.url), 'utf8')
const mailRouteSource = readFileSync(new URL('../src/views/MailRoutesView.vue', import.meta.url), 'utf8')

test('后端业务消息优先于状态码兜底文案', () => {
  const result = classifyHttpError({ response: { status: 400, data: { message: '编码格式错误' } } }, t)

  assert.deepEqual(result, { silent: false, type: 'warning', message: '编码格式错误' })
})

test('客户端业务状态使用警告提示和对应兜底文案', () => {
  const cases = [
    [400, 'httpErrors.badRequest'],
    [401, 'httpErrors.unauthorized'],
    [404, 'httpErrors.notFound'],
    [409, 'httpErrors.conflict'],
    [422, 'httpErrors.unprocessable'],
    [429, 'httpErrors.tooManyRequests']
  ]

  for (const [status, key] of cases) {
    assert.deepEqual(classifyHttpError({ response: { status, data: {} } }, t), {
      silent: false,
      type: 'warning',
      message: `i18n:${key}`
    })
  }
})

test('权限和服务端状态使用错误提示', () => {
  const cases = [[403, 'httpErrors.forbidden'], [500, 'httpErrors.server'], [502, 'httpErrors.badGateway'],
    [503, 'httpErrors.serviceUnavailable'], [504, 'httpErrors.gatewayTimeout']]

  for (const [status, key] of cases) {
    assert.deepEqual(classifyHttpError({ response: { status, data: {} } }, t), {
      silent: false,
      type: 'error',
      message: `i18n:${key}`
    })
  }
})

test('超时与网络错误使用不同提示', () => {
  assert.equal(classifyHttpError({ code: 'ECONNABORTED' }, t).message, 'i18n:httpErrors.timeout')
  assert.equal(classifyHttpError({ code: 'ERR_NETWORK' }, t).message, 'i18n:httpErrors.network')
})

test('页面本地异常保留具体消息而不误判为网络错误', () => {
  assert.equal(classifyHttpError(new Error('表单格式错误'), t).message, '表单格式错误')
})

test('取消和认证跳转标记的异常保持静默', () => {
  const cancelled = { code: 'ERR_CANCELED' }
  const redirected = silenceHttpError({ response: { status: 401, data: {} } })

  assert.equal(isCancelledRequest(cancelled), true)
  assert.deepEqual(classifyHttpError(cancelled, t), { silent: true })
  assert.deepEqual(classifyHttpError(redirected, t), { silent: true })
})

test('已提示标记可防止页面和拦截器重复展示', () => {
  const error = markHttpErrorNotified({})

  assert.equal(isHttpErrorNotified(error), true)
})

test('下载接口的 JSON Blob 错误恢复为统一响应对象', async () => {
  const error = { response: { data: new Blob([JSON.stringify({ message: '文件生成失败' })], { type: 'application/json' }) } }

  await normalizeHttpErrorResponse(error)

  assert.deepEqual(error.response.data, { message: '文件生成失败' })
})

test('响应拦截器统一提示并允许后台请求静默', () => {
  assert.match(httpSource, /if \(!error\.config\?\.silentError\) showHttpError\(error\)/)
  assert.match(httpSource, /silenceHttpError\(error\)/)
  assert.match(httpSource, /location\.href = `\/login\?redirect=/)
})

test('邮件账户和邮件路由保存失败时保留弹窗并消费异常', () => {
  for (const source of [mailAccountSource, mailRouteSource]) {
    assert.match(source, /catch \(error\) \{ showHttpError\(error, 'common\.saveFailed'\) \}/)
  }
})
