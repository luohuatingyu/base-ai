import test from 'node:test'
import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import zhCN from '../src/locales/zh-CN.js'
import enUS from '../src/locales/en-US.js'
import { localizeMenuName } from '../src/utils/navigation.js'

const routerSource = readFileSync(new URL('../src/router/index.js', import.meta.url), 'utf8')
const accountSource = readFileSync(new URL('../src/views/MailAccountsView.vue', import.meta.url), 'utf8')
const routeSource = readFileSync(new URL('../src/views/MailRoutesView.vue', import.meta.url), 'utf8')

/** 创建与 vue-i18n 一致的测试翻译器。 */
function translator(messages) {
  return key => key.split('.').reduce((value, segment) => value?.[segment], messages) || key
}

test('邮件账户和路由使用独立页面及列表权限', () => {
  assert.match(routerSource, /path: 'mail\/accounts'.*permission: 'mail:account:list'/)
  assert.match(routerSource, /path: 'mail\/routes'.*permission: 'mail:route:list'/)
  assert.match(accountSource, /mail:account:create/)
  assert.match(accountSource, /mail:account:update/)
  assert.match(accountSource, /mail:account:delete/)
  assert.match(routeSource, /mail:route:create/)
  assert.match(routeSource, /mail:route:update/)
  assert.match(routeSource, /mail:route:delete/)
})

test('邮箱密码仅由管理员单账户读取并在弹窗关闭后清除', () => {
  assert.match(accountSource, /\/mail\/accounts\/\$\{id\}\/password/)
  assert.match(accountSource, /row && auth\.isAdmin \? await loadPassword\(row\.id\) : ''/)
  assert.match(accountSource, /autocomplete="new-password"/)
  assert.match(accountSource, /@closed="clearPassword"/)
  assert.match(accountSource, /function clearPassword\(\) \{ form\.password = '' \}/)
})

test('邮件菜单和页面名称支持中英文权限本地化', () => {
  const zh = translator(zhCN)
  const en = translator(enUS)
  const entries = [
    [{ permission: 'mail:catalog' }, '邮件管理', 'Mail Management'],
    [{ permission: 'mail:account:list' }, '邮箱配置', 'Mail Accounts'],
    [{ permission: 'mail:route:list' }, '邮件路由', 'Mail Routes']
  ]

  for (const [menu, zhName, enName] of entries) {
    assert.equal(localizeMenuName(menu, zh), zhName)
    assert.equal(localizeMenuName(menu, en), enName)
  }
})
