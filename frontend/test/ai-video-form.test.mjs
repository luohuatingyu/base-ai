import assert from 'node:assert/strict'
import test from 'node:test'
import { assetAttributes, configurationKey, parseConfiguration, updateConfiguration, safeMediaUrl, prepareVideoPayload } from '../src/utils/ai-video-form.js'

for (const [section, attributes] of Object.entries(assetAttributes)) {
  test(`${section}: 分类字段保存回显且保留旧扩展属性`, () => {
    let json = '{"legacy":{"seed":0}}'
    for (const key of attributes) json = updateConfiguration(json, key, '测试属性')
    const payload = prepareVideoPayload({ name: '资产', metadataJson: json }, section)
    assert.equal(configurationKey(section), 'metadataJson')
    const saved = parseConfiguration(payload.metadataJson)
    assert.deepEqual(saved.legacy, { seed: 0 })
    for (const key of attributes) assert.equal(saved[key], '测试属性')
    assert.deepEqual(Object.keys(payload), ['name', 'metadataJson'])
  })
}
for (const invalid of ['{', '[]', 'null', '1', '"text"']) {
  test(`非法配置阻止保存且不覆盖旧值: ${invalid}`, () => {
    assert.throws(() => prepareVideoPayload({ inputJson: invalid }, 'tasks'))
    assert.throws(() => updateConfiguration(invalid, 'mode', 'TEMPLATE'))
  })
}
test('配置正常、空值、清空属性和长度边界', () => {
  assert.deepEqual(parseConfiguration('  '), {})
  assert.deepEqual(parseConfiguration(null), {})
  for (const value of ['', null, undefined]) assert.deepEqual(parseConfiguration(updateConfiguration('{"a":1,"b":2}', 'a', value)), { b: 2 })
  assert.deepEqual(parseConfiguration(updateConfiguration('', 'seed', 0)), { seed: 0 })
  assert.deepEqual(prepareVideoPayload({ name: '项目' }, 'projects'), { name: '项目' })
  assert.equal(configurationKey('renders'), 'configJson')
  assert.equal(configurationKey('tasks'), 'inputJson')
  const boundary = JSON.stringify({ a: 'a'.repeat(19992) })
  assert.equal(boundary.length, 20000)
  assert.equal(prepareVideoPayload({ configJson: boundary }, 'renders').configJson, boundary)
  assert.throws(() => prepareVideoPayload({ configJson: `${boundary} ` }, 'renders'), /configTooLong/)
})
test('预览限制与扩展键安全', () => {
  for (const url of ['javascript:alert(1)', 'data:text/html,test', '//evil.test', '/\\evil', 'https://a b', '', null, 'bad']) assert.equal(safeMediaUrl(url), '')
  for (const url of ['https://example.com/a.png', 'http://localhost/a.mp4', '/media/a.mp3']) assert.equal(safeMediaUrl(url), url)
  const result = parseConfiguration(updateConfiguration('{}', '__proto__', { injected: true }))
  assert.equal(result.__proto__.injected, true)
  assert.equal({}.injected, undefined)
})

// 使用真实组件 setup 与 Vue 响应式状态，只隔离 API、权限会话和弹窗边界。
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import * as formHelpers from '../src/utils/ai-video-form.js'
import zh from '../src/locales/zh-CN.js'
import SchemaModule from 'async-validator'
const Schema = SchemaModule.default || SchemaModule
const component = compileScript(parse(readFileSync(new URL('../src/views/AiVideoView.vue', import.meta.url), 'utf8')).descriptor, { id: 'video-form-test' })

/** 创建业务表单并绑定真实校验规则，不替代保存和状态管理逻辑。 */
function createEditor(t, section, options = {}) {
  const requests = []
  const notices = []
  const route = vue.reactive({ path: `/ai-video/${section}`, fullPath: `/ai-video/${section}`, query: {} })
  const modules = {
    vue: { ...vue, onMounted() {} },
    'element-plus': { ElMessage: { success: value => notices.push(value), warning() {} }, ElMessageBox: { confirm: options.confirm || (async () => {}) } },
    '@element-plus/icons-vue': {},
    'vue-i18n': { useI18n: () => ({ t: key => key.split('.').reduce((value, part) => value?.[part], zh) || key }) },
    'vue-router': { useRoute: () => route, useRouter: () => ({ replace() {}, push() {} }) },
    '../stores/auth': { useAuthStore: () => ({ isAdmin: options.admin !== false, hasPermission: () => false }) },
    '../utils/ai-video-form': formHelpers,
    '../api/aiVideo': {
      listAiVideoResource: async () => [], getAiVideoOverview: async () => ({}),
      createAiVideoResource: async (...args) => { requests.push(args); await options.submit?.() },
      updateAiVideoResource: async (...args) => { requests.push(args); await options.submit?.() }
    }
  }
  const bindings = { window: { setTimeout, clearTimeout } }
  for (const node of component.scriptSetupAst.filter(node => node.type === 'ImportDeclaration')) {
    for (const spec of node.specifiers) bindings[spec.local.name] = modules[node.source.value][spec.imported.name]
  }
  const code = component.content.replace(/^import[\s\S]*?from ['"][^'"]+['"];?$/gm, '').replace('export default', 'return')
  const compiled = new Function(...Object.keys(bindings), code)(...Object.values(bindings))
  const scope = vue.effectScope()
  const state = scope.run(() => compiled.setup({}, { expose() {} }))
  t.after(() => scope.stop())
  state.formRef.value = { validate: async () => {
    const rules = Object.fromEntries(state.formGroups.value.flatMap(group => group.fields).filter(field => !field.config).map(field => [field.key, state.fieldRules(field)]))
    await new Schema(rules).validate(state.form)
  } }
  return { state, requests, notices, route }
}

for (const section of Object.keys(assetAttributes)) {
  test(`${section}: 组件编辑、同步高级配置并发送真实接口字段`, async t => {
    const { state, requests } = createEditor(t, section)
    state.openEdit({ id: 12, projectId: 1, name: '旧资产', metadataJson: '{"custom":true}', status: 'DRAFT' })
    const field = state.formGroups.value.flatMap(group => group.fields).find(field => field.config)
    state.setFieldValue(field, '新属性')
    assert.equal(state.fieldValue(field), '新属性')
    await state.save()
    assert.equal(requests.length, 1)
    assert.equal(requests[0][0], 'assets')
    assert.equal(requests[0][1], 12)
    assert.deepEqual(JSON.parse(requests[0][2].metadataJson), { custom: true, [field.key]: '新属性' })
    assert.equal(state.dialogVisible.value, false)
  })
}

test('项目默认参数和分组；空必填及恶意 URL 不产生请求', async t => {
  const { state, requests } = createEditor(t, 'projects')
  state.openCreate()
  assert.equal(state.projectCreateGuide.value.length, 3)
  assert.match(state.projectCreateGuide.value[0].description, /主题|theme/i)
  assert.deepEqual(state.formGroups.value.map(group => group.key), ['basic', 'content', 'advanced'])
  assert.equal(state.form.fps, 25)
  assert.equal(state.form.aspectRatio, '16:9')
  await state.save()
  assert.equal(requests.length, 0)
  state.form.code = 'demo'; state.form.name = '测试'
  state.form.coverUrl = 'javascript:alert(1)'
  await state.save()
  assert.equal(requests.length, 0)
  state.form.coverUrl = 'https://example.com/image.png'
  await state.save()
  assert.equal(requests.length, 1)
  state.openEdit({ id: 9, code: 'demo', name: '测试项目' })
  assert.equal(state.isProjectCreate.value, false)
})

test('非法 JSON 展开高级区且保留输入，修正后可保存', async t => {
  const { state, requests } = createEditor(t, 'renders')
  state.openCreate('TEMPLATE')
  assert.equal(JSON.parse(state.form.configJson).mode, 'TEMPLATE')
  state.form.projectId = 1; state.form.name = '合成'; state.form.configJson = '['
  await state.save()
  assert.equal(requests.length, 0)
  assert.equal(state.advancedOpen.value, true)
  assert.equal(state.configurationValid.value, false)
  assert.equal(state.form.configJson, '[')
  assert.match(state.saveError.value, /JSON/)
  state.form.configJson = '{}'
  await state.save()
  assert.equal(requests.length, 1)
})

test('依赖失败保留表单并可重试；双击只提交一次', async t => {
  let reject
  const { state, requests } = createEditor(t, 'tasks', { submit: () => new Promise((_resolve, fail) => { reject = fail }) })
  state.openCreate('VIDEO'); state.form.projectId = 1; state.form.name = '视频'
  assert.equal(state.form.providerRouteId, null)
  const saving = state.save()
  await state.save()
  await new Promise(resolve => setImmediate(resolve))
  assert.equal(requests.length, 1)
  await state.closeEditor()
  assert.equal(state.dialogVisible.value, true)
  reject(new Error('offline')); await saving
  assert.equal(state.saving.value, false)
  assert.equal(state.dialogVisible.value, true)
  assert.equal(state.form.name, '视频')
  assert.match(state.saveError.value, /保存失败/)
})

test('无权限不能打开或提交表单；关闭提醒允许取消', async t => {
  const denied = createEditor(t, 'items', { admin: false })
  denied.state.openCreate(); denied.state.openEdit({ id: 1 }); await denied.state.save()
  assert.equal(denied.state.dialogVisible.value, false)
  assert.equal(denied.requests.length, 0)
  const { state } = createEditor(t, 'items', { confirm: async () => { throw new Error('cancel') } })
  state.openCreate(); state.form.name = '未保存'
  await state.closeEditor()
  assert.equal(state.dialogVisible.value, true)
})

test('新增物品传递正确分类，直接编辑 JSON 同步回可视字段', async t => {
  const { state, requests } = createEditor(t, 'items')
  state.openCreate(); state.form.projectId = 3; state.form.name = '铜铃'
  state.form.metadataJson = '{"material":"铜","unknown":42}'
  const field = state.formGroups.value.flatMap(group => group.fields).find(field => field.key === 'material')
  assert.equal(state.fieldValue(field), '铜')
  await state.save()
  assert.equal(requests[0][0], 'assets')
  assert.equal(requests[0][2], 'ITEM')
  assert.deepEqual(JSON.parse(requests[0][1].metadataJson), { material: '铜', unknown: 42 })
})

test('校验期间导航不会把物品提交到其他模块', async t => {
  const { state, requests, route } = createEditor(t, 'items')
  state.openCreate(); state.form.projectId = 3; state.form.name = '铜铃'
  const saving = state.save()
  route.path = '/ai-video/projects'
  await saving
  assert.equal(requests.length, 1)
  assert.equal(requests[0][0], 'assets')
  assert.equal(requests[0][2], 'ITEM')
})

test('无修改可直接关闭，确认放弃后关闭，旧配置异常自动展开', async t => {
  const { state } = createEditor(t, 'items')
  state.openCreate()
  let closed = false
  await state.closeEditor(() => { closed = true })
  assert.equal(closed, true)
  state.openEdit({ id: 1, projectId: 2, name: '旧数据', metadataJson: '{' })
  assert.equal(state.advancedOpen.value, true)
  assert.equal(state.fieldValue({ config: true, key: 'material' }), '')
  state.form.name = '修改'
  await state.closeEditor()
  assert.equal(state.dialogVisible.value, false)
})
