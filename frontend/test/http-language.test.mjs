import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const httpSource = readFileSync(new URL('../src/api/http.js', import.meta.url), 'utf8')

test('接口请求携带当前界面语言状态', () => {
  assert.match(httpSource, /import i18n from '\.\.\/locales'/)
  assert.match(httpSource, /config\.headers\['Accept-Language'\] = i18n\.global\.locale\.value/)
})
