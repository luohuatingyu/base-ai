import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import test from 'node:test'
import { readPrivateKeyFile } from '../src/utils/serverCredentials.js'

const byteLength = value => new TextEncoder().encode(value || '').length

/** 创建文件导入上下文，使用真实读取函数，只隔离组件响应式容器。 */
function importContext() {
  return { fileReadVersion: 0, form: { type: 'KEY', privateKey: 'existing' },
    editing: { value: true }, readingFile: { value: false }, fileError: { value: '' },
    fileName: { value: '' }, fileBytes: { value: 0 }, expandedField: { value: 'privateKey' },
    readPrivateKeyFile, byteLength, t: key => key }
}

// 拖拽和文件选择保留完整原文，读取结果可见，错误不能覆盖已有内容。
test('本地文件导入保留原文并区分空文件超限及读取失败', async () => {
  const contents = ['  BEGIN\nprivate\nEND\n', 'x'.repeat(32768), '', ' ', 'x'.repeat(32769), '密'.repeat(10923), null]
  for (const content of contents) {
    for (const drop of [false, true]) {
      const context = importContext()
      const file = { name: 'test.pem', size: content === null ? 1 : byteLength(content),
        async text() { if (content === null) throw new Error('read'); return content } }
      const event = drop ? { dataTransfer: { files: [file] } } : { target: { files: [file], value: 'selected' } }
      await method('readKey', context)(event)
      const valid = content !== null && !!content.trim() && byteLength(content) <= 32768
      assert.equal(context.form.privateKey, valid ? content : 'existing')
      assert.equal(context.readingFile.value, false)
      assert.equal(context.fileName.value, valid ? 'test.pem' : '')
      if (valid) assert.equal(context.fileBytes.value, byteLength(content))
      else assert.equal(context.fileError.value, content === null ? 'serverCredentials.readFailed'
        : byteLength(content) > 32768 ? 'serverCredentials.tooLarge' : 'serverCredentials.emptyFile')
      if (!drop) assert.equal(event.target.value, '')
    }
  }
})

// 关闭、切换或手动修改后，异步文件结果不能重新填入已清理的秘密。
test('过期文件读取不会覆盖关闭或修改后的表单', async () => {
  for (const action of ['close', 'edit', 'switch']) {
    const context = importContext()
    let finish
    const file = { name: 'slow.pem', size: 7, text: () => new Promise(resolve => { finish = resolve }) }
    const pending = method('readKey', context)({ target: { files: [file], value: '' } })
    assert.equal(context.readingFile.value, true)
    if (action === 'edit') method('invalidateFileRead', context)('privateKey')
    else method('resetMaterialState', context)()
    context.form.privateKey = action === 'edit' ? 'manual edit' : ''
    finish('private')
    await pending
    assert.equal(context.form.privateKey, action === 'edit' ? 'manual edit' : '')
    assert.equal(context.readingFile.value, false)
    assert.equal(context.fileName.value, '')
  }
})

// 字节上限按 UTF-8 计算，超限时保留完整输入并阻止请求；边界和多行原样保存。
test('长文本不截断且超限阻止保存', async () => {
  for (const field of ['privateKey', 'publicKey', 'certificate']) {
    for (const content of ['line 1\nline 2\n', 'x'.repeat(32768), 'x'.repeat(32769), '密'.repeat(10923)]) {
      const form = { label: 'key', type: 'KEY', username: '', privateKey: 'private', publicKey: '', certificate: '', password: '', passphrase: '', [field]: content }
      const calls = []
      await method('save', { form, byteLength, readingFile: { value: false },
        saving: { value: false }, editing: { value: true }, http: { post: async (url, body) => calls.push(body) },
        clearForm() {}, async load() {}, emit() {}, ElMessage: { warning() {} }, t: key => key,
        showHttpError(error) { throw error },
      })()
      assert.equal(calls.length, byteLength(content) > 32768 ? 0 : 1)
      assert.equal(form[field], content)
      if (calls.length) assert.equal(calls[0][field], content)
    }
  }
  assert.doesNotMatch(source, /maxlength="32768"/)
  assert.match(source, /v-model="form\[expandedField\]"/)
  assert.match(source, /v-model="form\[field.name\]"/)
  assert.match(source, /wrap="soft"/)
})

const source = readFileSync(new URL('../src/components/ServerCredentialManager.vue', import.meta.url), 'utf8')

// 搜索与类型过滤组合执行，历史凭据和空账号均保持可检索。
test('凭据搜索和类型筛选返回匹配记录', () => {
  const expression = source.slice(source.indexOf('const filteredRows ='), source.indexOf('const loading ='))
  const rows = { value: [
    { id: 1, label: 'Production', username: 'deploy', type: 'KEY' },
    { id: 2, label: 'Legacy', username: '', type: 'RSA' },
    { id: 3, label: 'Account', username: 'root', type: 'PASSWORD' },
  ] }
  for (const [query, type, expected] of [[' PROD ', '', [1]], ['root', 'PASSWORD', [3]], ['', 'RSA', [2]], ['missing', '', []], ['', '', [1, 2, 3]]]) {
    const result = runInNewContext(expression + '; filteredRows', {
      rows, query: { value: query }, typeFilter: { value: type }, computed: callback => callback(),
    })
    assert.deepEqual(Array.from(result, row => row.id), expected)
  }
})

/** 执行组件真实表单函数，隔离 HTTP 和消息框外部依赖。 */
function method(name, context) {
  const body = source.match(new RegExp(`(?:async )?function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n\\}`))[0]
  return runInNewContext(`(${body})`, context)
}

// 创建与编辑提交完整材料，成功清空输入并刷新选项，失败保留输入便于重试。
test('凭据创建编辑保留材料并在成功后清除敏感输入', async () => {
  for (const [id, fails] of [[null, false], [7, false], [7, true]]) {
    const calls = []
    const form = { id, label: 'credential', username: 'deploy', password: 'secret', privateKey: 'private', publicKey: 'public', certificate: 'certificate', type: 'PASSWORD' }
    const send = async (url, body) => { calls.push([url, body]); if (fails) throw new Error('failed') }
    const saving = { value: false }
    const editing = { value: true }
    const context = {
      form, saving, editing, readingFile: { value: false }, byteLength, http: { post: send, put: send },
      clearForm: () => calls.push('clear'), load: async () => calls.push('load'), emit: name => calls.push(name),
      showHttpError: () => calls.push('error'), ElMessage: { warning: () => calls.push('warning') }, t: key => key,
    }
    await method('save', context)()
    assert.equal(calls[0][0], id ? '/server-credentials/7' : '/server-credentials')
    assert.equal(calls[0][1].certificate, '')
    assert.equal(calls[0][1].privateKey, '')
    assert.equal(calls[0][1].password, 'secret')
    assert.deepEqual(calls.slice(1), fails ? ['error'] : ['clear', 'load', 'changed'])
    assert.equal(saving.value, false)
    assert.equal(editing.value, fails)
  }
})

// 私钥和密码各自验证必填、保留秘密和掩码边界，历史类型必须显式转换。
test('两类凭据的必填校验与秘密保留', async () => {
  for (const [type, secret, saved, username, allowed] of [
    ['KEY', 'private', false, '', true], ['KEY', 'private', false, 'old-user', true], ['KEY', '', false, '', false],
    ['KEY', '', true, '', true], ['KEY', '******', false, '', false],
    ['PASSWORD', 'secret', false, 'deploy', true], ['PASSWORD', '', false, 'deploy', false],
    ['PASSWORD', '', true, 'deploy', true], ['PASSWORD', '******', false, 'deploy', false],
    ['PASSWORD', 'secret', false, '', false], ['RSA', 'secret', true, 'deploy', false],
  ]) {
    const calls = []
    const form = { id: saved ? 7 : null, label: 'valid', type, username,
      privateKey: type === 'KEY' ? secret : '', password: type === 'PASSWORD' ? secret : '',
      publicKey: '', certificate: '', passphrase: '', hasPrivateKey: saved, hasPassword: saved }
    const send = async (url, body) => calls.push(body)
    await method('save', { form, saving: { value: false }, editing: { value: true }, readingFile: { value: false }, byteLength,
      http: { post: send, put: send }, clearForm() {}, async load() {}, emit() {},
      t: key => key, ElMessage: { warning() {} }, showHttpError(error) { throw error },
    })()
    assert.equal(calls.length, allowed ? 1 : 0, JSON.stringify([type, secret, saved, username]))
    if (allowed) assert.equal(type === 'KEY' ? calls[0].password : calls[0].privateKey, '')
    if (allowed) assert.equal(calls[0].username, type === 'KEY' ? '' : username)
  }
})

// 类型切换清理不适用的输入，不把隐藏的秘密提交到另一种类型。
test('切换私钥类型清理账号与不适用的临时输入', () => {
  const form = { type: 'PASSWORD', username: 'deploy', password: 'secret', privateKey: 'private', publicKey: 'public', certificate: 'certificate', passphrase: 'phrase' }
  method('changeType', { form, resetMaterialState() {} })('KEY')
  assert.equal(form.password, '')
  assert.equal(form.username, '')
  assert.equal(form.privateKey, 'private')
  method('changeType', { form, resetMaterialState() {} })('PASSWORD')
  for (const field of ['privateKey', 'publicKey', 'certificate', 'passphrase']) assert.equal(form[field], '')
  assert.equal(form.username, '')
})

// 私钥界面不维护 SSH 用户，只有账号密码类型显示必填账号。
test('私钥表单和列表不展示凭据账号', () => {
  assert.match(source, /<el-form-item v-if="form.type === 'PASSWORD'" :label="t\('servers.username'\)" required>/)
  assert.match(source, /row.type === 'KEY' \? '—' : row.username/)
})

// 校验空标签、缺失账号以及重复点击不发送写请求。
test('凭据无效输入和重复保存不写入接口', async () => {
  for (const [label, username, saving] of [[' ', 'deploy', false], ['label', '', false], ['label', 'deploy', true]]) {
    let warnings = 0
    await method('save', { form: { label, username, password: 'secret' }, saving: { value: saving }, readingFile: { value: false },
      ElMessage: { warning: () => warnings++ }, t: key => key })()
    assert.equal(warnings, saving ? 0 : 1)
  }
})

// 取消删除不调用接口，引用冲突展示错误而不伪造删除成功。
test('凭据删除处理取消和引用冲突', async () => {
  for (const scenario of ['cancel', 'conflict', 'success']) {
    const calls = []
    await method('remove', {
      t: key => key,
      ElMessageBox: { confirm: async () => { if (scenario === 'cancel') throw new Error('cancel') } },
      http: { delete: async url => { calls.push(url); if (scenario === 'conflict') throw new Error('in-use') } },
      load: async () => calls.push('load'), emit: () => calls.push('changed'), showHttpError: () => calls.push('error'),
    })({ id: 7, label: 'shared' })
    assert.deepEqual(calls, scenario === 'cancel' ? [] : scenario === 'conflict' ? ['/server-credentials/7', 'error'] : ['/server-credentials/7', 'load', 'changed'])
  }
})

// 管理员查看秘密使用显式接口且列表与浏览器存储不保存返回内容。
test('凭据秘密按需读取并提供关闭清理', async () => {
  const secrets = { value: null }
  const secretVisible = { value: false }
  await method('reveal', { secrets, secretVisible,
    http: { post: async url => { assert.equal(url, '/server-credentials/7/secret'); return { data: { password: 'secret' } } } },
  })({ id: 7 })
  assert.equal(secrets.value.password, 'secret')
  assert.equal(secretVisible.value, true)
  assert.match(source, /@closed="secrets = null"/)
  assert.match(source, /v-if="isAdmin"/)
  assert.doesNotMatch(source, /localStorage|sessionStorage|v-html/)
})
