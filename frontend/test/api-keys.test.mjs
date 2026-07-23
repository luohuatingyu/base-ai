import assert from 'node:assert/strict'
import test from 'node:test'
import { copyApiKey, normalizeApiKeys, serializeApiKeyRows, splitApiKeys, toApiKeyRows } from '../src/utils/apiKeys.js'

test('将逗号和换行分隔的 API Key 规范为每行一个密钥', () => {
  assert.equal(normalizeApiKeys(' key-one, key-two\n\n key-three '), 'key-one\nkey-two\nkey-three')
})

test('空值不产生空行', () => {
  assert.equal(normalizeApiKeys(null), '')
  assert.equal(normalizeApiKeys(' , \n '), '')
})

test('查看列表将每个 API Key 拆分为独立行', () => {
  assert.deepEqual(splitApiKeys('key-one, key-two\nkey-three'), ['key-one', 'key-two', 'key-three'])
})

test('编辑表单为已保存 Key 创建逐行输入，并为空值保留一行', () => {
  assert.deepEqual(toApiKeyRows('key-one\nkey-two'), ['key-one', 'key-two'])
  assert.deepEqual(toApiKeyRows(''), [''])
})

test('保存编辑行时过滤删除后遗留的空白行', () => {
  assert.equal(serializeApiKeyRows(['key-one', '', ' key-two ']), 'key-one\nkey-two')
})

test('复制单个 API Key 时写入浏览器剪贴板', async () => {
  const copied = []
  await copyApiKey('key-one', { writeText: async value => copied.push(value) })

  assert.deepEqual(copied, ['key-one'])
})

test('浏览器剪贴板不可用时复制失败', async () => {
  await assert.rejects(() => copyApiKey('key-one', undefined), /Clipboard is unavailable/)
})
