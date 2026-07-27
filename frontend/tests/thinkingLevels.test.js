import test from 'node:test'
import assert from 'node:assert/strict'
import {
  parseThinkingMappings,
  serializeThinkingMappings,
  thinkingMappingEntries
} from '../src/utils/thinkingLevels.js'

test('解析逗号和换行分隔的映射并规范化等级', () => {
  assert.deepEqual(
    parseThinkingMappings(' high = high\nLOW=low,unknown=value,MAX= '),
    { HIGH: 'high', LOW: 'low' }
  )
})

test('解析时保留供应商值中的等号', () => {
  assert.deepEqual(parseThinkingMappings('MEDIUM=reasoning=medium'), {
    MEDIUM: 'reasoning=medium'
  })
})

test('序列化时过滤空值并使用标准等级顺序', () => {
  assert.equal(
    serializeThinkingMappings({ ULTRA: ' ultra ', LOW: 'low', HIGH: '  ' }),
    'LOW=low,ULTRA=ultra'
  )
})

test('展示条目按标准等级排序且空映射返回空列表', () => {
  assert.deepEqual(thinkingMappingEntries('EXTRA_HIGH=xhigh,LOW=low'), [
    { level: 'LOW', value: 'low' },
    { level: 'EXTRA_HIGH', value: 'xhigh' }
  ])
  assert.deepEqual(thinkingMappingEntries(''), [])
})
