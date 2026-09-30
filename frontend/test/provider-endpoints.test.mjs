import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
const source = readFileSync(new URL('../src/views/ModelProvidersView.vue', import.meta.url), 'utf8')
test('供应商表单包含三种地址和两个同步开关', () => {
  for (const field of ['baseUrl', 'imageUrl', 'videoUrl', 'imageSameAsBase', 'videoSameAsImage']) assert.ok(source.includes('form.' + field))
})
test('供应商表单显式绑定模型策略并加载策略目录', () => {
  assert.match(source, /form\.strategyCode/)
  assert.match(source, /\/models\/provider-strategies/)
  assert.match(source, /providers\.strategies/)
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
