import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { createInboxReader } from '../src/utils/mailInbox.js'

/** 创建可控制完成顺序的请求。 */
function deferred() {
  let resolve, reject
  const promise = new Promise((success, failure) => { resolve = success; reject = failure })
  return { promise, resolve, reject }
}

test('收件分页结果和正文使用当前邮箱版本，刷新立即清除旧正文', async () => {
  const state = {}, calls = [], errors = []
  const http = { async get(url, options) {
    calls.push([url, options])
    return { data: url.endsWith('/messages') ? { items: [{ uid: '4' }], total: 21, uidValidity: '9' } : { body: '正文' } }
  } }
  const reader = createInboxReader(http, state, error => errors.push(error))
  await reader.load(7, 2)
  assert.equal(state.page, 2)
  assert.equal(state.total, 21)
  assert.deepEqual(state.items, [{ uid: '4' }])
  await reader.open(state.items[0])
  assert.equal(state.detail.body, '正文')
  assert.deepEqual(calls[1], ['/mail/inbox/7/messages/4', { params: { uidValidity: '9' } }])
  await reader.load(null)
  assert.deepEqual(state.items, [])
  assert.equal(state.detail, null)
  assert.equal(state.loading, false)
  assert.deepEqual(errors, [])
})

test('切换邮箱和快速选择邮件时旧响应不能覆盖新数据', async () => {
  const state = {}, pending = [], errors = []
  const reader = createInboxReader({ get() { const request = deferred(); pending.push(request); return request.promise } }, state, error => errors.push(error))
  const oldList = reader.load(1)
  const newList = reader.load(2)
  pending[1].resolve({ data: { items: [{ uid: '2' }], total: 1, uidValidity: '8' } })
  await newList
  pending[0].resolve({ data: { items: [{ uid: '1' }], total: 9, uidValidity: '1' } })
  await oldList
  assert.equal(state.accountId, 2)
  assert.deepEqual(state.items, [{ uid: '2' }])
  const first = reader.open({ uid: '2' }), second = reader.open({ uid: '3' })
  pending[3].resolve({ data: { body: '最新正文' } }); await second
  pending[2].resolve({ data: { body: '过时正文' } }); await first
  assert.equal(state.detail.body, '最新正文')
  const stale = reader.open({ uid: '4' })
  await reader.load(null)
  pending[4].reject(new Error('stale')); await stale
  assert.equal(state.detail, null)
  assert.deepEqual(errors, [])
})

test('当前请求错误清理加载状态，卸载后错误与结果均被丢弃', async () => {
  const state = {}, pending = [], errors = []
  const reader = createInboxReader({ get() { const request = deferred(); pending.push(request); return request.promise } }, state, error => errors.push(error))
  const listing = reader.load(1)
  pending[0].reject(new Error('list failed')); await listing
  assert.equal(state.loading, false)
  const detail = reader.open({ uid: '1' })
  pending[1].reject(new Error('detail failed')); await detail
  assert.equal(state.detailLoading, false)
  assert.equal(errors.length, 2)
  const disposed = reader.load(2)
  reader.dispose()
  pending[2].reject(new Error('disposed')); await disposed
  assert.equal(errors.length, 2)
})

test('正文使用文本插值，页面和菜单受收件权限保护', () => {
  const source = readFileSync(new URL('../src/views/MailInboxView.vue', import.meta.url), 'utf8')
  const router = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
  assert.match(source, /\{\{ state\.detail\.body/)
  assert.doesNotMatch(source, /v-html|<iframe|<img/)
  assert.match(router, /path: 'mail\/inbox'.*system:mail:inbox:list/)
})
