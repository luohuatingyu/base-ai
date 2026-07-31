import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { parseTaskLogFields } from '../src/utils/taskLogDisplay.js'

const tasksViewSource = readFileSync(new URL('../src/views/TasksView.vue', import.meta.url), 'utf8')

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
    { name: 'event', rawValue: 'llm_user_prompt', compactValue: 'llm_user_prompt', displayValue: 'llm_user_prompt', isJson: false },
    { name: 'content', rawValue: 'Hello Base AI', compactValue: 'Hello Base AI', displayValue: 'Hello Base AI', isJson: false },
    { name: 'duration_ms', rawValue: '12.5', compactValue: '12.5', displayValue: '12.5', isJson: false }
  ])
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
  assert.match(tasksViewSource, /class="log-field-copy"/)
  assert.match(tasksViewSource, /copyLogValue\(logFieldCopyValue\(field, logFieldKey\(item, fieldIndex\)\)\)/)
  assert.match(tasksViewSource, /<details\s+v-if="field\.isJson"\s+class="log-field-json"/)
  assert.match(tasksViewSource, /\{\{ field\.compactValue \}\}/)
  assert.match(tasksViewSource, /<pre>\{\{ field\.displayValue \}\}<\/pre>/)
  assert.match(tasksViewSource, /setJsonFieldExpanded\(logFieldKey\(item, fieldIndex\), \$event\.target\.open\)/)
  assert.match(tasksViewSource, /copyLogValue\(item\.message\)/)
})
