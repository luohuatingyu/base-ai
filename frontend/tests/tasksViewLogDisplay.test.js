import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseTaskLogFields } from '../src/utils/taskLogDisplay.js'

const tasksViewSource = readFileSync(new URL('../src/views/TasksView.vue', import.meta.url), 'utf8')
const automationStyles = readFileSync(new URL('../src/automation.css', import.meta.url), 'utf8')
const zhLocaleSource = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enLocaleSource = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')

/** 提取指定选择器的 CSS 声明，验证任务页面布局规则。 */
function declarations(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = tasksViewSource.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(match, `缺少布局选择器：${selector}`)
  return match[1]
}

/** 提取公共自动化布局中的 CSS 声明，验证高优先级宽度约束。 */
function automationDeclarations(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = automationStyles.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(match, `缺少公共布局选择器：${selector}`)
  return match[1]
}

test('任务筛选首行第一个控件为 Trace ID', () => {
  const firstFilterRow = tasksViewSource.match(/<div class="filter-row">([\s\S]*?)<\/div>/)?.[1]

  assert.ok(firstFilterRow)
  assert.match(firstFilterRow, /^\s*<el-input\s+v-model="query\.traceId"/)
})

test('任务详情桌面端按双列边框表格展示，长字段跨整行', () => {
  assert.match(tasksViewSource, /<el-descriptions :column="2" border size="default" class="task-detail-table">/)
  assert.match(tasksViewSource, /:span="field\.wide \? 2 : 1"/)
  assert.match(tasksViewSource, /const wideDetailFields = new Set\(\[[\s\S]*?'request_params_json'[\s\S]*?'request_headers_json'[\s\S]*?'error_message'[\s\S]*?'cancellation_reason'/)
  assert.match(tasksViewSource, /@media\s*\(max-width:\s*900px\)[\s\S]*?\.task-detail-table :deep\(\.el-descriptions__cell\)\s*\{[^}]*display:\s*block[^}]*width:\s*100% !important/)
})

test('任务详情按固定顺序展示父任务字段并兼容未知字段', () => {
  assert.match(tasksViewSource, /const detailFieldOrder = \[[\s\S]*?'trace_id'[\s\S]*?'owner_user_id'[\s\S]*?'task_type'[\s\S]*?'request_params_json'/)
  assert.match(tasksViewSource, /Object\.keys\(detail\.value\)\.filter\(key => key !== 'pythonTraces' && !detailFieldOrder\.includes\(key\)\)/)
  assert.match(tasksViewSource, /t\('tasks\.unknownField', \{ field: key \}\)/)
})

test('任务详情将多条 Python 子任务展示为独立表格', () => {
  assert.match(tasksViewSource, /<section v-if="pythonTraces\.length" class="python-traces-section">/)
  assert.match(tasksViewSource, /<el-table :data="pythonTraces" border size="small" table-layout="auto" class="python-traces-table">/)
  assert.match(tasksViewSource, /const pythonTraces = computed\(\(\) => Array\.isArray\(detail\.value\.pythonTraces\) \? detail\.value\.pythonTraces : \[\]\)/)
  assert.match(tasksViewSource, /const pythonTraceColumns = \[[\s\S]*?'python_trace_id'[\s\S]*?'parent_trace_id'[\s\S]*?'worker_endpoint'[\s\S]*?'status'/)
})

test('任务详情字段在中文和英文环境使用对应语言描述', () => {
  const localeKeys = [
    'ownerUserId', 'requestPath', 'requestParams', 'pythonTraceCount', 'finishedReason',
    'forceTerminateReasonLabel', 'pythonTraces', 'pythonTraceId', 'workerEndpoint', 'unknownField'
  ]

  for (const key of localeKeys) {
    assert.match(zhLocaleSource, new RegExp(`\\b${key}:`), `中文缺少字段：${key}`)
    assert.match(enLocaleSource, new RegExp(`\\b${key}:`), `英文缺少字段：${key}`)
  }
  assert.match(zhLocaleSource, /ownerUserId: '所属用户 ID'/)
  assert.match(enLocaleSource, /ownerUserId: 'Owner User ID'/)
})

test('任务详情本地化任务类型和状态并格式化 JSON', () => {
  assert.match(tasksViewSource, /if \(key === 'task_type'\) return localizeTaskType\(value, t\)/)
  assert.match(tasksViewSource, /if \(key === 'status' && statuses\.includes\(value\)\) return t\(`tasks\.statuses\.\$\{value\}`\)/)
  assert.match(tasksViewSource, /if \(key\.endsWith\('_json'\)\) \{[\s\S]*?JSON\.parse\(value\)/)
  assert.match(tasksViewSource, /if \(value === null \|\| value === undefined\) return '-'/)
})

test('任务调度时间范围在桌面端保持紧凑并在窄屏占满整行', () => {
  assert.match(declarations('.filter-item-date'), /width:\s*320px/)
  assert.match(declarations('.filter-item-date'), /flex:\s*0\s+1\s+320px/)
  assert.match(declarations('.filter-item-date'), /min-width:\s*0/)
  assert.match(declarations('.log-filter-date'), /width:\s*320px/)
  assert.match(declarations('.log-filter-date'), /flex:\s*0\s+1\s+320px/)
  assert.match(declarations('.log-filter-date'), /min-width:\s*0/)
  assert.match(tasksViewSource, /@media\s*\(max-width:\s*900px\)[\s\S]*?\.filter-item-date\s*\{[^}]*width:\s*100%[^}]*min-width:\s*100%/)
  assert.match(tasksViewSource, /@media\s*\(max-width:\s*900px\)[\s\S]*?\.log-filter-date,[\s\S]*?width:\s*100%/)

  const compactRule = automationDeclarations('.filter-row > .filter-item-date.el-date-editor')
  assert.match(compactRule, /width:\s*320px/)
  assert.match(compactRule, /min-width:\s*0/)
  assert.match(compactRule, /max-width:\s*320px/)
  assert.match(compactRule, /flex:\s*0\s+0\s+320px/)
  const compactLogRule = automationDeclarations('.log-filters > .log-filter-date.el-date-editor')
  assert.match(compactLogRule, /width:\s*320px/)
  assert.match(compactLogRule, /min-width:\s*0/)
  assert.match(compactLogRule, /max-width:\s*320px/)
  assert.match(compactLogRule, /flex:\s*0\s+0\s+320px/)
  assert.match(automationStyles, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.filter-row\s*>\s*\.filter-item-date\.el-date-editor,[\s\S]*?\.log-filters\s*>\s*\.log-filter-date\.el-date-editor\s*\{[^}]*width:\s*100%\s*!important[^}]*min-width:\s*100%\s*!important[^}]*max-width:\s*none\s*!important[^}]*flex:\s*1\s+1\s+100%\s*!important/)
})

test('任务日志按级别展示卡片状态和记录上下文', () => {
  assert.match(tasksViewSource, /:class="`log-entry--\$\{String\(item\.level \|\| 'info'\)\.toLowerCase\(\)\}`"/)
  assert.match(tasksViewSource, /class="log-context"/)
  assert.match(tasksViewSource, /item\.logger_name/)
  assert.match(tasksViewSource, /item\.thread_name/)
  assert.match(tasksViewSource, /\.log-entry--error\s*\{[^}]*border-left-color:\s*#f56c6c/s)
  assert.match(tasksViewSource, /\.log-entry--warn\s*\{[^}]*border-left-color:\s*#e6a23c/s)
})

test('任务异常堆栈默认折叠并支持展开查看完整内容', () => {
  assert.match(tasksViewSource, /<details v-if="item\.throwable" class="log-throwable">/)
  assert.match(tasksViewSource, /<summary>\{\{ firstThrowableLine\(item\.throwable\) \}\}<\/summary>/)
  assert.match(tasksViewSource, /<pre>\{\{ item\.throwable \}\}<\/pre>/)
  assert.match(tasksViewSource, /function firstThrowableLine\(throwable\)/)
})

test('任务日志保持最新在前和五秒自动刷新行为', () => {
  assert.match(tasksViewSource, /logs\.value = \(response\.data \|\| \[\]\)\.reverse\(\)/)
  assert.match(tasksViewSource, /}, 5000\)/)
  assert.match(tasksViewSource, /watch\(logVisible, stopLogRefreshOnClose\)/)
})

test('任务日志为空时继续展示空状态', () => {
  assert.match(tasksViewSource, /<el-empty v-if="!filteredLogs\.length" :description="t\('tasks\.log\.noLogs'\)"\/>/)
})

test('结构化日志拆分全部字段并保留带空格内容', () => {
  assert.deepEqual(parseTaskLogFields('event=llm_user_prompt content=Hello Base AI duration_ms=12.5'), [
    { name: 'event', rawValue: 'llm_user_prompt', compactValue: 'llm_user_prompt', displayValue: 'llm_user_prompt', isJson: false, isCompact: true, isCopyable: false },
    { name: 'content', rawValue: 'Hello Base AI', compactValue: 'Hello Base AI', displayValue: 'Hello Base AI', isJson: false, isCompact: true, isCopyable: false },
    { name: 'duration_ms', rawValue: '12.5', compactValue: '12.5', displayValue: '12.5', isJson: false, isCompact: true, isCopyable: false }
  ])
})

test('少于 40 字符的日志字段紧凑展示，40 字符及以上、换行和 JSON 字段独占整行', () => {
  const compactBoundaryValue = 'x'.repeat(39)
  const wideBoundaryValue = 'x'.repeat(40)
  const fields = parseTaskLogFields(`event=http_response_body method=POST path=/api/ai/chat compact=${compactBoundaryValue} boundary=${wideBoundaryValue} long=${wideBoundaryValue}x multiline=line1\nline2 json={"ok":true}`)

  assert.deepEqual(fields.map(field => field.isCompact), [true, true, true, true, false, false, false, false])
  assert.equal(fields.at(-1).isJson, true)
})

test('少于 40 个字符不显示复制入口，40 个字符及以上显示复制入口', () => {
  const fields = parseTaskLogFields(`short=${'x'.repeat(39)} exact=${'x'.repeat(40)} long=${'x'.repeat(41)} json_short={"ok":true} json_long={"value":"${'x'.repeat(34)}"}`)

  assert.deepEqual(fields.map(field => field.isCopyable), [false, true, true, false, true])
})

test('JSON 日志字段默认提供折叠摘要和格式化内容', () => {
  const fields = parseTaskLogFields('event=result content={"name": "demo", "items": [1, 2]}')

  assert.equal(fields[1].isJson, true)
  assert.equal(fields[1].compactValue, '{"name":"demo","items":[1,2]}')
  assert.equal(fields[1].displayValue, '{\n  "name": "demo",\n  "items": [\n    1,\n    2\n  ]\n}')
})

test('空字段可复制且非结构化日志不被错误拆分', () => {
  assert.deepEqual(parseTaskLogFields('event=empty content=').map(field => field.displayValue), ['empty', ''])
  assert.deepEqual(parseTaskLogFields('plain log message'), [])
})

test('任务日志字段和原始消息均提供复制入口且 JSON 默认折叠', () => {
  assert.match(tasksViewSource, /:class="\{ 'log-field--wide': !field\.isCompact \}"/)
  assert.match(tasksViewSource, /\.log-fields\s*\{[^}]*grid-template-columns:\s*repeat\(auto-fit,\s*minmax\(220px,\s*320px\)\)/s)
  assert.match(tasksViewSource, /\.log-field--wide\s*\{[^}]*grid-column:\s*1\s*\/\s*-1/s)
  assert.match(tasksViewSource, /<el-button\s+v-if="field\.isCopyable"[\s\S]*class="log-field-copy"/)
  assert.match(tasksViewSource, /class="log-field-copy"/)
  assert.match(tasksViewSource, /copyLogValue\(logFieldCopyValue\(field, logFieldKey\(item, fieldIndex\)\)\)/)
  assert.match(tasksViewSource, /<details\s+v-if="field\.isJson"\s+class="log-field-json"/)
  assert.match(tasksViewSource, /\{\{ field\.compactValue \}\}/)
  assert.match(tasksViewSource, /<pre>\{\{ field\.displayValue \}\}<\/pre>/)
  assert.match(tasksViewSource, /setJsonFieldExpanded\(logFieldKey\(item, fieldIndex\), \$event\.target\.open\)/)
  assert.match(tasksViewSource, /copyLogValue\(item\.message\)/)
})
