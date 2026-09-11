import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { runInNewContext } from 'node:vm'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const viewSource = readFileSync(new URL('../src/views/DataSyncView.vue', import.meta.url), 'utf8')

test('数据同步计划必须选择启用的执行服务器', () => {
  assert.match(viewSource, /v-model="form\.serverId"/)
  assert.match(viewSource, /http\.get\('\/data-sync\/servers'\)/)
  assert.match(viewSource, /:disabled="!item\.enabled"/)
  assert.match(viewSource, /!form\.serverId \|\| !form\.sourceConnectionId/)
  assert.equal(zhCN.dataSync.server, '执行服务器')
  assert.equal(enUS.dataSync.server, 'Execution Server')
})

test('表查询和预检均携带所选服务器', () => {
  assert.match(viewSource, /params: \{ serverId: form\.serverId \}/)
  assert.match(viewSource, /serverId: form\.serverId, strategy: form\.strategy/)
  assert.match(viewSource, /form = reactive\(\{ id: null, name: '', serverId: null/)
})

test('计划和运行明细展示实际服务器并兼容历史计划', () => {
  assert.match(viewSource, /scope\.row\.serverName \|\| t\('dataSync\.platformLocal'\)/)
  assert.match(viewSource, /runDetail\.serverName \|\| t\('dataSync\.platformLocal'\)/)
  assert.match(viewSource, /serverId: row\.serverId/)
  assert.match(zhCN.dataSync.platformLocal, /历史计划/)
  assert.match(enUS.dataSync.platformLocal, /legacy plan/)
})

// 执行页面中的实际状态格式化方法，校验所有展示分支及语言回退。
function statusFormatter(locale) {
  const source = ['statusType', 'statusLabel'].map(name => viewSource.match(new RegExp(`function ${name}\\(status\\) \\{[\\s\\S]*?\\n\\}`))[0]).join('\n')
  return runInNewContext(`${source}\n({ statusType, statusLabel })`, {
    t: key => key.split('.').reduce((value, segment) => value?.[segment], locale),
  })
}

for (const [status, color] of [['PENDING', 'info'], ['RUNNING', 'primary'], ['SUCCEEDED', 'success'], ['SUCCESS', 'success'], ['FAILED', 'danger'], ['CANCEL_REQUESTED', 'warning'], ['CANCELLED', 'info'], ['SKIPPED', 'info']]) {
  test(`同步状态 ${status} 在中英文中有文案及语义颜色`, () => {
    for (const locale of [zhCN, enUS]) {
      const formatter = statusFormatter(locale)
      assert.equal(formatter.statusType(status), color)
      assert.equal(formatter.statusLabel(status), locale.dataSync.statuses[status])
      assert.ok(formatter.statusLabel(status))
      assert.notEqual(formatter.statusLabel(status), status)
    }
  })
}

test('空状态和后端扩展状态不产生缺失翻译', () => {
  for (const locale of [zhCN, enUS]) {
    const formatter = statusFormatter(locale)
    for (const status of [null, undefined, '']) {
      assert.equal(formatter.statusLabel(status), locale.dataSync.notRun)
      assert.equal(formatter.statusType(status), 'info')
    }
    for (const status of ['FUTURE_STATUS', '__proto__', 'toString', '<script>alert(1)</script>']) {
      assert.equal(formatter.statusLabel(status), status)
      assert.equal(formatter.statusType(status), 'info')
    }
  }
})

test('重排配置区保留权限、全量替换确认及表选择绑定', () => {
  assert.match(viewSource, /auth\.hasPermission\(form\.id \? 'operations:data-sync:update' : 'operations:data-sync:create'\)/)
  for (const permission of ['preview', 'update', 'run', 'cancel', 'logs', 'delete']) {
    assert.ok(viewSource.includes(`auth.hasPermission('operations:data-sync:${permission}')`))
  }
  assert.match(viewSource, /v-if="form\.strategy === 'FULL_REPLACE'"[\s\S]*?v-model="form\.confirmDestructive"/)
  assert.match(viewSource, /v-if="sourceTables\.length" v-model="selectedTables"/)
  assert.match(viewSource, /v-else class="sync-empty-tables"/)
  assert.match(viewSource, /:disabled="running\(scope\.row\)"/)
  assert.match(viewSource, /:disabled="!running\(scope\.row\)"/)
})
