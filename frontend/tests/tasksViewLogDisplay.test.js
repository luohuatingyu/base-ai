import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'

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
