import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const viewSource = readFileSync(new URL('../src/views/ApiKeysView.vue', import.meta.url), 'utf8')
const zhSource = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enSource = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')

test('API Key 支持秒、分钟、小时、天和无限制周期', () => {
  assert.match(viewSource, /\['SECOND', 'MINUTE', 'HOUR', 'DAY', 'UNLIMITED'\]/)
  assert.match(viewSource, /v-model="form\.rateLimitType"/)
  assert.match(viewSource, /form\.rateLimitType !== 'UNLIMITED'/)
  assert.match(zhSource, /SECOND: '每秒'.*MINUTE: '每分钟'.*HOUR: '每小时'.*DAY: '每天'.*UNLIMITED: '无限制'/)
  assert.match(enSource, /SECOND: 'Per Second'.*MINUTE: 'Per Minute'.*HOUR: 'Per Hour'.*DAY: 'Per Day'.*UNLIMITED: 'Unlimited'/)
})

test('无限制提交空次数，受限模式提交配置次数', () => {
  assert.match(viewSource, /rateLimitType: form\.rateLimitType/)
  assert.match(viewSource, /rateLimitCount: form\.rateLimitType === 'UNLIMITED' \? null : form\.rateLimitCount/)
  assert.match(viewSource, /row\.rateLimitCount \?\? 60/)
  assert.doesNotMatch(viewSource, /rateLimitPerMinute/)
})
