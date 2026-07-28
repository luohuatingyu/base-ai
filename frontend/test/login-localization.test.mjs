import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { getLocalizedPlatformName, resolveLocale } from '../src/config.js'
import enUS from '../src/locales/en-US.js'
import zhCN from '../src/locales/zh-CN.js'

const loginViewSource = readFileSync(new URL('../src/views/LoginView.vue', import.meta.url), 'utf8')
const localeSource = readFileSync(new URL('../src/locales/index.js', import.meta.url), 'utf8')
const serverSource = readFileSync(new URL('../server.mjs', import.meta.url), 'utf8')
const composeSource = readFileSync(new URL('../../docker-compose.yml', import.meta.url), 'utf8')

test('有效的浏览器语言优先，否则依次回退环境默认语言和系统默认语言', () => {
  assert.equal(resolveLocale('zh-CN', 'en-US'), 'zh-CN')
  assert.equal(resolveLocale(null, 'zh-CN'), 'zh-CN')
  assert.equal(resolveLocale('fr-FR', 'en-US'), 'en-US')
  assert.equal(resolveLocale('fr-FR', 'de-DE'), 'en-US')
})

test('平台名称严格跟随当前语言，不同时展示中英文名称', () => {
  const config = { nameZh: '智能平台', nameEn: 'Intelligence Platform' }

  assert.equal(getLocalizedPlatformName('zh-CN', config), '智能平台')
  assert.equal(getLocalizedPlatformName('en-US', config), 'Intelligence Platform')
  assert.match(loginViewSource, /getLocalizedPlatformName\(locale\.value\)/)
  assert.doesNotMatch(loginViewSource, /appConfig\.nameEn/)
  assert.doesNotMatch(loginViewSource, /appConfig\.nameZh/)
})

test('登录页提供可复用的语言切换入口', () => {
  assert.match(loginViewSource, /import LanguageSwitcher from ['"]\.\.\/components\/LanguageSwitcher\.vue['"]?/)
  assert.match(loginViewSource, /<LanguageSwitcher \/>/)
})

test('登录页右上角先展示语言切换，再展示开放平台', () => {
  const actionsStart = loginViewSource.indexOf('class="login-page-actions"')
  const openPlatformEntry = loginViewSource.indexOf('class="login-open-platform-entry"')
  const languageEntry = loginViewSource.indexOf('class="login-language-entry"')
  const loginCard = loginViewSource.indexOf('class="login-card"')
  const pageStyle = loginViewSource.match(/\.login-page \{([^}]*)\}/)?.[1] || ''

  assert.ok(actionsStart >= 0)
  assert.ok(languageEntry > actionsStart)
  assert.ok(openPlatformEntry > languageEntry)
  assert.ok(openPlatformEntry >= 0)
  assert.ok(loginCard > openPlatformEntry)
  assert.doesNotMatch(pageStyle, /padding/)
  assert.match(loginViewSource, /\.login-page-actions \{[^}]*right: 24px[^}]*display: flex/)
  assert.match(loginViewSource, /@media \(max-width: 640px\)[\s\S]*\.login-page-actions[\s\S]*right: 16px/)
})

test('登录页开放平台入口使用简洁名称', () => {
  assert.equal(zhCN.login.openPlatform, '开放平台')
  assert.equal(enUS.login.openPlatform, 'Open Platform')
})

test('未保存语言时前端使用 APP_DEFAULT_LOCALE 运行时配置', () => {
  assert.match(serverSource, /defaultLocale:\s*process\.env\.APP_DEFAULT_LOCALE/)
  assert.match(composeSource, /frontend:[\s\S]*?APP_DEFAULT_LOCALE:\s*\$\{APP_DEFAULT_LOCALE:-en-US\}/)
  assert.match(localeSource, /resolveLocale\(savedLocale,\s*appConfig\.defaultLocale\)/)
})
