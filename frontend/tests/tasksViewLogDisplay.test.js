import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseTaskLogFields } from '../src/utils/taskLogDisplay.js'

const tasksViewSource = readFileSync(new URL('../src/views/TasksView.vue', import.meta.url), 'utf8')

/** 提取指定选择器的 CSS 声明，验证任务页面布局规则。 */
function declarations(selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = tasksViewSource.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(match, `缺少布局选择器：${selector}`)
  return match[1]
}

test('任务筛选首行第一个控件为 Trace ID', () => {
  const firstFilterRow = tasksViewSource.match(/<div class="filter-row">([\s\S]*?)<\/div>/)?.[1]

  assert.ok(firstFilterRow)
  assert.match(firstFilterRow, /^\s*<el-input\s+v-model="query\.traceId"/)
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
