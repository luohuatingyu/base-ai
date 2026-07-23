import assert from 'node:assert/strict'
import test from 'node:test'
import { normalizeApiKeys } from '../src/utils/apiKeys.js'

test('将逗号和换行分隔的 API Key 规范为每行一个密钥', () => {
  assert.equal(normalizeApiKeys(' key-one, key-two\n\n key-three '), 'key-one\nkey-two\nkey-three')
})

test('空值不产生空行', () => {
  assert.equal(normalizeApiKeys(null), '')
  assert.equal(normalizeApiKeys(' , \n '), '')
})
