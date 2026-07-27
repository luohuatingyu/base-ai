import assert from 'node:assert/strict'
import test from 'node:test'
import {
  createHostRule,
  createLatestAutoSaver,
  HOST_RULE_TYPES,
  normalizeHostRules,
  toHostRuleRows
} from '../src/utils/hostRules.js'

/** 等待指定毫秒，供自动保存防抖行为测试使用。 */
function wait(milliseconds) {
  return new Promise(resolve => setTimeout(resolve, milliseconds))
}

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

test('自动保存对连续文本修改执行防抖且只提交最终值', async () => {
  const saved = []
  const autoSaver = createLatestAutoSaver(async value => saved.push(value), 20)

  autoSaver.schedule('first')
  await wait(5)
  autoSaver.schedule('latest')
  await wait(30)

  assert.deepEqual(saved, ['latest'])
  autoSaver.dispose()
})

test('自动保存立即提交离散修改并串行合并在途期间的最新值', async () => {
  const saved = []
  let finishFirst
  const firstPending = new Promise(resolve => { finishFirst = resolve })
  const autoSaver = createLatestAutoSaver(async value => {
    saved.push(value)
    if (value === 'first') await firstPending
  }, 20)

  autoSaver.schedule('first', true)
  await wait(0)
  autoSaver.schedule('obsolete', true)
  autoSaver.schedule('latest', true)
  finishFirst()
  await wait(10)

  assert.deepEqual(saved, ['first', 'latest'])
  autoSaver.dispose()
})

test('自动保存清除待提交值后不会继续应用已撤销修改', async () => {
  const saved = []
  const autoSaver = createLatestAutoSaver(async value => saved.push(value), 10)

  autoSaver.schedule('cancelled')
  autoSaver.clear()
  await wait(20)

  assert.deepEqual(saved, [])
  autoSaver.dispose()
})
