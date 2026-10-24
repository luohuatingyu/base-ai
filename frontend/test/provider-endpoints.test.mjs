import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
const source = readFileSync(new URL('../src/views/ModelProvidersView.vue', import.meta.url), 'utf8')
const iconSource = readFileSync(new URL('../src/components/ModelStrategyIcon.vue', import.meta.url), 'utf8')
const zhSource = readFileSync(new URL('../src/locales/zh-CN.js', import.meta.url), 'utf8')
const enSource = readFileSync(new URL('../src/locales/en-US.js', import.meta.url), 'utf8')
test('供应商表单包含三种地址和两个同步开关', () => {
  for (const field of ['baseUrl', 'imageUrl', 'videoUrl', 'imageSameAsBase', 'videoSameAsImage']) assert.ok(source.includes('form.' + field))
})
test('供应商表单显式绑定接入协议并加载协议目录', () => {
  assert.match(source, /form\.strategyCode/)
  assert.match(source, /\/models\/provider-strategies/)
  assert.match(source, /providers\.strategies/)
})
test('协议下拉框、已选值和列表标签展示对应图标', () => {
  assert.match(source, /ModelStrategyIcon/)
  assert.match(source, /#label="\{ label \}"/)
  assert.match(source, /strategy-option/)
  assert.match(source, /strategy-label/)
})
test('内置供应商协议全部有准确的中英文展示名称', () => {
  for (const code of ['GENERIC', 'OPENAI', 'ARK', 'GEMINI', 'MINIMAX', 'VIDU']) {
    assert.match(zhSource, new RegExp(`${code}:\\s*'[^']+'`))
    assert.match(enSource, new RegExp(`${code}:\\s*'[^']+'`))
  }
})
test('接入协议名称保持准确且选项语义简洁', () => {
  assert.match(zhSource, /strategy: '接入协议'/)
  assert.match(enSource, /strategy: 'Integration Protocol'/)
  assert.match(zhSource, /GENERIC: 'OpenAI 兼容'/)
  assert.match(enSource, /GENERIC: 'OpenAI-compatible'/)
  assert.doesNotMatch(zhSource, /strategy: '模型策略'/)
  assert.doesNotMatch(enSource, /strategy: 'Model Strategy'/)
})
test('接入协议使用有层次的品牌图标且未知编码安全回退', () => {
  for (const code of ['GENERIC', 'OPENAI', 'ARK', 'GEMINI', 'MINIMAX', 'VIDU']) assert.match(iconSource, new RegExp(`const ${code} =`))
  assert.match(iconSource, /STRATEGY_ICONS = \{ GENERIC, OPENAI, ARK, GEMINI, MINIMAX, VIDU \}/)
  assert.match(iconSource, /\|\| GENERIC/)
  assert.match(iconSource, /strokeWidth: 1\.25/)
  assert.match(iconSource, /opacity: 0\.42/)
  assert.match(iconSource, /var\(--el-bg-color, #fff\)/)
})
test('保存前校验至少一个地址及视频同步来源', () => {
  assert.match(source, /hasProviderEndpoint/)
  assert.match(source, /providers\.endpointRequired/)
  assert.match(source, /providers\.syncSourceRequired/)
})
test('编辑旧供应商默认沿用两级同步并实时计算视频地址', () => {
  assert.match(source, /imageSameAsBase: row\?\.\w+ \?\? true/)
  assert.match(source, /videoSameAsImage: row\?\.\w+ \?\? true/)
  assert.match(source, /const imageEndpoint = computed/)
  assert.match(source, /const videoEndpoint = computed/)
})
