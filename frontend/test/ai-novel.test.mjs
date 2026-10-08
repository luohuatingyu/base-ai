import assert from 'node:assert/strict'
import test from 'node:test'
import { readFileSync } from 'node:fs'
import { parse, compileScript } from '@vue/compiler-sfc'
import * as vue from 'vue'
import { createI18n } from 'vue-i18n'
import zh from '../src/locales/zh-CN.js'
import en from '../src/locales/en-US.js'
const compiled = compileScript(parse(readFileSync(new URL('../src/views/AiScriptView.vue', import.meta.url), 'utf8')).descriptor, { id: 'novel-test' })
/** 使用真实页面状态，仅替换网络、路由和确认弹窗。 */
function workspace(t, options = {}) {
  const requests = [], messages = []
  const novel = { id: 1, title: '夜航', outline: '', genre: '', tone: '', language: 'zh-CN', status: 'DRAFT', chapters: [{ id: 2, chapterNo: 1, title: '灯塔', synopsis: '', content: '原文', status: 'DRAFT' }], references: [] }
  const api = new Proxy({}, { get: (_target, name) => async (...args) => { requests.push([name, ...structuredClone(args)]); if (options.fail === name) throw Error('network'); if (name === 'runAiNovelWriter') { await options.wait?.(); return { data: { content: 'AI结果' } } } if (name === 'getAiNovel') return { data: structuredClone(novel) }; if (name === 'listAiNovels') return { data: [structuredClone(novel)] }; return { data: { id: 3, ...args.at(-1) } } } })
  const i18n = createI18n({ legacy: false, locale: options.locale || 'zh', messages: { zh, en } }).global
  const modules = { vue: { ...vue, onMounted() {}, onBeforeUnmount() {} }, 'vue-i18n': { useI18n: () => i18n }, 'vue-router': { useRoute: () => ({ query: {} }), useRouter: () => ({ push() {} }), onBeforeRouteLeave() {} }, 'element-plus': { ElMessage: { success: m => messages.push(m), warning: m => messages.push(m) }, ElMessageBox: { confirm: async () => { if (options.cancel) throw 'cancel' } } }, '../api/aiNovel': api, '../api/aiVideo': { listAiVideoResource: async () => ({ data: [] }) }, '../api/http': { showHttpError: e => messages.push(e.message) }, '../stores/auth': { useAuthStore: () => ({ hasPermission: () => true }) } }
  const bindings = {}
  for (const node of compiled.scriptSetupAst.filter(n => n.type === 'ImportDeclaration')) for (const spec of node.specifiers) bindings[spec.local.name] = modules[node.source.value]?.[spec.imported?.name || 'default']
  const code = compiled.content.replace(/^import[\s\S]*?from ['"][^'"]+['"];?$/gm, '').replace('export default', 'return')
  const definition = new Function(...Object.keys(bindings), code)(...Object.values(bindings))
  const scope = vue.effectScope(); const state = scope.run(() => definition.setup({}, { expose() {} })); t.after(() => scope.stop())
  return { state, requests, messages, novel }
}
test('作品可独立于视频项目加载并编辑章节', async t => { const { state } = workspace(t); assert.equal(typeof state.loadNovels, 'function'); await state.loadNovels(); assert.equal(state.activeNovel.value.title, '夜航'); assert.equal(state.activeChapter.value.content, '原文') })
test('AI 写作前保存当前正文，续写追加而非覆盖', async t => { const { state, requests } = workspace(t); await state.loadNovels(); state.activeChapter.value.content = '修改后的原文'; await state.runWriter('CONTINUE'); assert.equal(requests.find(r => r[0] === 'updateAiNovelChapter')[3].content, '修改后的原文'); state.applyAiResult(); assert.equal(state.activeChapter.value.content, '修改后的原文\n\nAI结果') })
test('摘要应用到摘要且不覆盖正文', async t => { const { state } = workspace(t); await state.loadNovels(); await state.runWriter('SUMMARY'); state.applyAiResult(); assert.equal(state.activeChapter.value.synopsis, 'AI结果'); assert.equal(state.activeChapter.value.content, '原文') })
test('大纲无章节也可生成，应用到作品大纲', async t => { const { state } = workspace(t); await state.loadNovels(); state.activeChapterId.value = null; await state.runWriter('OUTLINE'); state.applyAiResult(); assert.equal(state.activeNovel.value.outline, 'AI结果') })
test('保存失败保留草稿且不调用模型', async t => { const { state, requests } = workspace(t, { fail: 'updateAiNovelChapter' }); await state.loadNovels(); state.activeChapter.value.content = '未保存'; await state.runWriter('REWRITE'); assert.equal(state.activeChapter.value.content, '未保存'); assert.equal(requests.filter(r => r[0] === 'runAiNovelWriter').length, 0) })
test('切换章节确认取消保留未保存内容', async t => { const { state } = workspace(t, { cancel: true }); await state.loadNovels(); state.activeChapter.value.content = '草稿'; await state.selectChapter(99); assert.equal(state.activeChapterId.value, 2); assert.equal(state.activeChapter.value.content, '草稿') })
test('AI 结果绑定原章节，切换目标后不能误覆盖', async t => { const { state } = workspace(t); await state.loadNovels(); await state.runWriter('REWRITE'); state.activeChapterId.value = 99; state.applyAiResult(); assert.equal(state.activeNovel.value.chapters[0].content, '原文') })
test('生成中禁止重复请求和切换作品', async t => { let done; const { state, requests } = workspace(t, { wait: () => new Promise(r => { done = r }) }); await state.loadNovels(); const run = state.runWriter('CHAPTER'); while (!done) await new Promise(r => setTimeout(r, 0)); await state.runWriter('CHAPTER'); await state.selectNovel(99); assert.equal(state.activeNovel.value.id, 1); assert.equal(requests.filter(r => r[0] === 'runAiNovelWriter').length, 1); done(); await run })
test('保存作品不能丢失章节编辑草稿', async t => { const { state } = workspace(t); await state.loadNovels(); state.activeChapter.value.content = '局部草稿'; await state.saveNovel(); assert.equal(state.activeChapter.value.content, '局部草稿') })
test('人物参考资料保存包含正文与类型', async t => { const { state, requests } = workspace(t); await state.loadNovels(); state.openReference(); Object.assign(state.referenceForm, { name: '船长', content: '害怕深海', referenceType: 'CHARACTER' }); await state.saveReference(); assert.equal(requests.find(r => r[0] === 'createAiNovelReference')[2].content, '害怕深海'); assert.equal(state.activeNovel.value.references[0].name, '船长') })
test('提取前保存选中章节并传递作品、项目与章节来源', async t => { const { state, requests } = workspace(t); await state.loadNovels(); Object.assign(state.extractForm, { projectId: 8, chapterIds: [2], sceneCount: 6 }); await state.extractToVideo(); const r = requests.find(r => r[0] === 'extractAiNovelToVideo'); assert.equal(r[1], 1); assert.deepEqual(r[2], { projectId: 8, chapterIds: [2], sceneCount: 6 }); assert.ok(requests.findIndex(r => r[0] === 'updateAiNovelChapter') < requests.findIndex(r => r[0] === 'extractAiNovelToVideo')) })
