import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { compile, createSSRApp, h } from 'vue'
import { renderToString } from 'vue/server-renderer'
import { createUploadQueue, documentErrorTranslationKey, documentStatusType, formatFileSize,
  runUploadQueue, uploadQueueSummary, uploadStatusType } from '../src/utils/knowledgeBaseManagement.js'

const file = (name, size, lastModified = 1) => ({ name, size, lastModified })
const viewSource = readFileSync(new URL('../src/views/KnowledgeBasesView.vue', import.meta.url), 'utf8')

/** 使用真实页头与目录模板验证各状态下的新增入口及点击行为。 */
for (const state of [
  { name: 'empty', rows: [], hasBaseFilters: false },
  { name: 'populated', rows: [{ id: 1, name: 'Knowledge', enabled: true }], hasBaseFilters: false },
  { name: 'filtered empty', rows: [], hasBaseFilters: true },
  { name: 'loading', rows: [], loading: true },
  { name: 'load error', rows: [], loadError: true }
]) {
  for (const permitted of [true, false]) {
    test(`knowledge base create entry is unique for ${state.name}, permission=${permitted}`, async () => {
      const header = viewSource.match(/<header class="knowledge-hero">[\s\S]*?<\/header>/)[0]
      const directory = viewSource.match(/<aside class="knowledge-directory panel">[\s\S]*?<\/aside>/)[0]
      const template = `<div>${header}${directory}</div>`
      const render = compile(template)
      const calls = []
      const context = {
        t: key => key, auth: { hasPermission: permission => permitted && permission === 'ai:model:knowledge-base:create' },
        total: state.rows.length, query: { size: 20 }, storageTypes: [], loading: false, loadError: false,
        activeBase: null, storageLabel: value => value, loadBases() {}, searchBases() {}, selectBase() {},
        openForm: (...args) => calls.push(args), ...state
      }
      const buttons = []
      const app = createSSRApp({ render, setup: () => context })
      for (const tag of new Set(template.match(/el-[a-z-]+/g))) {
        app.component(tag, {
          /** 保留组件插槽与事件，隔离 Element Plus 的浏览器依赖。 */
          setup(props, { attrs, slots }) {
            return () => {
              const children = slots.default?.() || []
              if (tag === 'el-button' && children.some(child => child.children === 'knowledgeBases.add')) buttons.push(attrs)
              return h(tag, attrs, children)
            }
          }
        })
      }
      await renderToString(app)
      assert.equal(buttons.length, permitted ? 1 : 0)
      if (permitted) {
        buttons[0].onClick()
        assert.deepEqual(calls, [[]])
      }
    })
  }
}

test('upload queue enforces file count, empty file, and size boundaries', () => {
  const files = Array.from({ length: 21 }, (_, index) => file(`file-${index}.txt`, index === 0 ? 0 : index === 1 ? 10 * 1024 * 1024 + 1 : 10))
  const queue = createUploadQueue(files)
  assert.equal(queue[0].rejection, 'EMPTY')
  assert.equal(queue[1].rejection, 'TOO_LARGE')
  assert.equal(queue[19].status, 'PENDING')
  assert.equal(queue[20].rejection, 'TOO_MANY')
  assert.deepEqual(uploadQueueSummary(queue), { total: 21, pending: 18, uploading: 0, success: 0, failed: 0, rejected: 3, completed: 3, percentage: 14 })
})

test('upload queue runs sequentially and retries only failed files', async () => {
  const queue = createUploadQueue([file('first.txt', 10), file('second.txt', 10)])
  const events = [], attempts = new Map()
  const uploader = async item => {
    events.push(`start:${item.name}`); attempts.set(item.name, (attempts.get(item.name) || 0) + 1)
    if (item.name === 'second.txt' && attempts.get(item.name) === 1) throw new Error('temporary')
    events.push(`done:${item.name}`)
  }
  let summary = await runUploadQueue(queue, uploader)
  assert.deepEqual(events, ['start:first.txt', 'done:first.txt', 'start:second.txt'])
  assert.equal(summary.success, 1); assert.equal(summary.failed, 1)
  events.length = 0; summary = await runUploadQueue(queue, uploader)
  assert.deepEqual(events, ['start:second.txt', 'done:second.txt'])
  assert.equal(summary.success, 2); assert.equal(summary.failed, 0)
})

test('document and upload states map to stable presentation values', () => {
  assert.equal(documentStatusType('READY'), 'success')
  assert.equal(documentStatusType('FAILED'), 'danger')
  assert.equal(documentStatusType('INDEXING'), 'warning')
  assert.equal(uploadStatusType('SUCCESS'), 'success')
  assert.equal(uploadStatusType('REJECTED'), 'danger')
  assert.equal(documentErrorTranslationKey('knowledge.vectorWriteFailed'), 'knowledgeBases.documentErrors.vectorWriteFailed')
  assert.equal(documentErrorTranslationKey('external.secret'), 'knowledgeBases.documentErrors.unknown')
})

test('file size formatting covers bytes, kilobytes, megabytes, and invalid values', () => {
  assert.equal(formatFileSize(10), '10 B')
  assert.equal(formatFileSize(1536), '1.5 KB')
  assert.equal(formatFileSize(2 * 1024 * 1024), '2.0 MB')
  assert.equal(formatFileSize(-1), '-')
})

test('knowledge base view uses paged master-detail and owner-scoped maintenance contracts', () => {
  assert.match(viewSource, /knowledge-bases\/management/)
  assert.match(viewSource, /documents\/page/)
  assert.match(viewSource, /type="file" multiple/)
  assert.match(viewSource, /documents\/batch-delete/)
  assert.match(viewSource, /Number\(row\.ownerUserId\) === Number\(auth\.user\?\.id\)/)
  assert.match(viewSource, /immutableLocked/)
  assert.match(viewSource, /documentErrorTranslationKey/)
})
