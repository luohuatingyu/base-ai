import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const panelSource = readFileSync(new URL('../src/components/ModelUsagePanel.vue', import.meta.url), 'utf8')

test('模型使用面板展示标题并为无数据区域提供明确空状态', () => {
  assert.match(panelSource, /<div class="model-usage-heading">/)
  assert.match(panelSource, /<el-table v-if="models\.length"/)
  assert.match(panelSource, /<el-empty v-else-if="!loading" :description="t\('modelUsage\.empty'\)" \/>/)
  assert.match(panelSource, /<el-table v-if="errors\.length"/)
  assert.match(panelSource, /<el-empty v-else-if="!loading" :description="t\('modelUsage\.noErrors'\)" \/>/)
})

test('模型使用面板不会把无效或负延迟渲染成误导性数值', () => {
  assert.match(panelSource, /!Number\.isFinite\(numeric\) \|\| numeric < 0 \? '-' :/)
  assert.match(panelSource, /Math\.round\(numeric\)\.toLocaleString\(\)/)
})

test('模型使用筛选和双列区域在窄屏下能够收缩换行', () => {
  assert.match(panelSource, /grid-template-columns: repeat\(2, minmax\(0, 1fr\)/)
  assert.match(panelSource, /\.model-usage-columns > div \{ min-width: 0; \}/)
  assert.match(panelSource, /@media \(max-width: 600px\)[\s\S]*?\.model-usage-filters \.el-date-editor, \.model-usage-filters \.el-input, \.model-usage-filters \.el-select \{ flex-basis: 100%; min-width: 100%; \}/)
})
