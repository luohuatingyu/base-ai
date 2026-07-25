import assert from 'node:assert/strict'
import test from 'node:test'
import { createHostRule, HOST_RULE_TYPES, normalizeHostRules, toHostRuleRows } from '../src/utils/hostRules.js'

test('Host 规则提供五种匹配类型', () => {
  assert.deepEqual(HOST_RULE_TYPES, ['EXACT', 'PREFIX', 'SUFFIX', 'CONTAINS', 'ANY'])
})

test('空配置创建一条可编辑的精确规则', () => {
  assert.deepEqual(createHostRule(), { type: 'EXACT', value: '' })
  assert.deepEqual(toHostRuleRows([]), [{ type: 'EXACT', value: '' }])
})

test('规则规范化大小写、去重并清除任意 Host 的值', () => {
  assert.deepEqual(normalizeHostRules([
    { type: ' suffix ', value: ' Factory.AI ' },
    { type: 'SUFFIX', value: 'factory.ai' },
    { type: 'any', value: 'ignored' },
    { type: 'contains', value: 'Factory' },
    { type: 'unknown', value: 'ignored' },
    { type: 'PREFIX', value: '' }
  ]), [
    { type: 'SUFFIX', value: 'factory.ai' },
    { type: 'ANY', value: null },
    { type: 'CONTAINS', value: 'factory' }
  ])
})
