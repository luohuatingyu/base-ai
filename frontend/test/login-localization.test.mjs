import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { getLocalizedPlatformName, resolveLocale } from '../src/config.js'

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

test('未保存语言时前端使用 APP_DEFAULT_LOCALE 运行时配置', () => {
  assert.match(serverSource, /defaultLocale:\s*process\.env\.APP_DEFAULT_LOCALE/)
  assert.match(composeSource, /frontend:[\s\S]*?APP_DEFAULT_LOCALE:\s*\$\{APP_DEFAULT_LOCALE:-en-US\}/)
  assert.match(localeSource, /resolveLocale\(savedLocale,\s*appConfig\.defaultLocale\)/)
})
