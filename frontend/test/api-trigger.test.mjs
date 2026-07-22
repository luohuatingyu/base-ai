import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import {
  extractTraceId,
  fetchTaskProgress,
  resolveActiveTab
} from '../src/utils/apiTrigger.js'

const viewSource = readFileSync(new URL('../src/views/ApiTriggerView.vue', import.meta.url), 'utf8')

/**
 * 截取相邻 tab 之间的模板文本，验证字段不会跨页放置。
 */
function tabSection(startMarker, endMarker) {
  const start = viewSource.indexOf(startMarker)
  const end = viewSource.indexOf(endMarker, start + startMarker.length)
  assert.notEqual(start, -1, '缺少 tab 起始标记：' + startMarker)
  assert.notEqual(end, -1, '缺少 tab 结束标记：' + endMarker)
  return viewSource.slice(start, end)
}

const traceIdCases = [
  ['统一响应 camelCase', '{"data":{"traceId":"0123456789abcdef0123456789abcdef"}}', '0123456789abcdef0123456789abcdef'],
  ['直接响应 camelCase', '{"traceId":"root-trace"}', 'root-trace'],
  ['统一响应 snake_case', '{"data":{"trace_id":"nested-trace"}}', 'nested-trace'],
  ['直接响应 snake_case', '{"trace_id":"root-snake"}', 'root-snake'],
  ['对象响应', { data: { traceId: 'object-trace' } }, 'object-trace'],
  ['空响应', '', ''],
  ['非法 JSON', '{"data":', ''],
  ['缺少任务编号', '{"data":{"status":"RUNNING"}}', ''],
  ['超长任务编号', '{"traceId":"' + 'x'.repeat(65) + '"}', '']
]

for (const [name, responseBody, expected] of traceIdCases) {
  test('提取 Trace ID：' + name, () => {
    assert.equal(extractTraceId(responseBody), expected)
  })
}

test('关闭认证时仅从令牌页回退到业务接口页', () => {
  assert.equal(resolveActiveTab(false, 'auth'), 'business')
  assert.equal(resolveActiveTab(true, 'auth'), 'auth')
  assert.equal(resolveActiveTab(false, 'basic'), 'basic')
  assert.equal(resolveActiveTab(false, 'progress'), 'progress')
})

test('按 Trace ID 并行查询详情和日志', async () => {
  const calls = []
  let resolveDetail
  let resolveLogs
  const detailPromise = new Promise(resolve => { resolveDetail = resolve })
  const logsPromise = new Promise(resolve => { resolveLogs = resolve })
  const http = {
    get(path) {
      calls.push(path)
      return path.endsWith('/logs') ? logsPromise : detailPromise
    }
  }

  const pending = fetchTaskProgress(http, 'trace/id')
  assert.deepEqual(calls, ['/system/tasks/trace%2Fid', '/system/tasks/trace%2Fid/logs'])
  resolveDetail({ data: { status: 'RUNNING' } })
  resolveLogs({ data: [{ id: 1, message: 'started' }] })

  assert.deepEqual(await pending, {
    detail: { status: 'RUNNING' },
    logs: [{ id: 1, message: 'started' }]
  })
})

test('任务详情或日志查询失败时向调用方传播异常', async () => {
  const http = {
    async get(path) {
      if (path.endsWith('/logs')) throw new Error('logs unavailable')
      return { data: { status: 'RUNNING' } }
    }
  }
  await assert.rejects(fetchTaskProgress(http, 'trace-1'), /logs unavailable/)
  await assert.rejects(fetchTaskProgress(http, ''), /Trace ID 不能为空/)
})

test('新增和编辑弹窗按四类 tab 分隔字段且进度页没有手工输入框', () => {
  const basic = tabSection('name="basic"', 'name="business"')
  const business = tabSection('name="business"', 'name="auth"')
  const auth = tabSection('name="auth"', 'name="progress"')
  const progress = tabSection('name="progress"', '</el-tabs>')

  for (const field of ['form.name', 'form.enabled', 'form.description', 'form.cronExpression', 'form.authEnabled']) {
    assert.match(basic, new RegExp(field.replace('.', '\\.')))
  }
  for (const field of ['form.httpMethod', 'form.timeoutSeconds', 'form.url', 'form.headers', 'form.queryParams', 'form.contentType', 'form.requestBody']) {
    assert.match(business, new RegExp(field.replace('.', '\\.')))
  }
  for (const field of ['form.authMethod', 'form.authContentType', 'form.authUrl', 'form.authBody', 'form.authTokenPath', 'form.authTokenHeader', 'form.authTokenPrefix']) {
    assert.match(auth, new RegExp(field.replace('.', '\\.')))
  }

  assert.match(viewSource, /v-if="form\.authEnabled" :label="t\('apiTrigger\.auth'\)" name="auth"/)
  assert.match(viewSource, /v-if="auth\.hasPermission\('system:task:view'\)" :label="t\('apiTrigger\.progress'\)" name="progress"/)
  assert.match(viewSource, /const activeTab\s*=\s*ref\('basic'\)/)
  assert.match(progress, /\{\{ progressTraceId \}\}/)
  assert.doesNotMatch(progress, /v-model="progressTraceId"/)
})
