import test from 'node:test'
import assert from 'node:assert/strict'
import { normalizeMailAddressRows, toMailAddressRows } from '../src/utils/mailAddresses.js'

test('多个邮箱输入行保存时过滤空值、规范空白并去重', () => {
  assert.deepEqual(normalizeMailAddressRows([
    ' first@example.com ', '', null, 'second@example.com', 'first@example.com'
  ]), ['first@example.com', 'second@example.com'])
})

test('已有多个邮箱逐行回显且不复用接口数组引用', () => {
  const addresses = ['first@example.com', 'second@example.com']
  const rows = toMailAddressRows(addresses)
  rows.push('third@example.com')

  assert.deepEqual(addresses, ['first@example.com', 'second@example.com'])
  assert.deepEqual(rows, ['first@example.com', 'second@example.com', 'third@example.com'])
})

test('空邮箱列表保留一个可填写输入行', () => {
  assert.deepEqual(toMailAddressRows([]), [''])
  assert.deepEqual(toMailAddressRows(null), [''])
})
