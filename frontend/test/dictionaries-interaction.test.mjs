import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const source = readFileSync(new URL('../src/views/DictionariesView.vue', import.meta.url), 'utf8')

test('未选择字典类型时显示左侧选择提示并隐藏右侧数据区', () => {
  assert.match(source, /<el-empty v-else :description="t\('dictionaries\.selectTypeHint'\)"\s*\/>/)
  assert.match(source, /<template v-if="current"><el-table :data="pagedDataRows">/)
})

test('删除当前字典类型后清理右侧选择和数据', () => {
  assert.match(source, /if\(current\.value\?\.id===row\.id\)\{current\.value=null;allDataRows\.value=\[\];dataQuery\.page=1\}/)
})

test('右侧新增数据按钮在未选择类型时保持禁用', () => {
  assert.match(source, /<el-button :disabled="!current" @click="openData\(\)">\{\{ t\('dictionaries\.addData'\) \}\}<\/el-button>/)
})
