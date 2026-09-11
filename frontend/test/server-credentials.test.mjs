import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import test from 'node:test'

const source = readFileSync(new URL('../src/components/ServerCredentialManager.vue', import.meta.url), 'utf8')

/** 执行组件真实表单函数，隔离 HTTP 和消息框外部依赖。 */
function method(name, context) {
  const body = source.match(new RegExp(`(?:async )?function ${name}\\([^)]*\\) \\{[\\s\\S]*?\\n\\}`))[0]
  return runInNewContext(`(${body})`, context)
}

// 创建与编辑提交完整材料，成功清空输入并刷新选项，失败保留输入便于重试。
test('凭据创建编辑保留材料并在成功后清除敏感输入', async () => {
  for (const [id, fails] of [[null, false], [7, false], [7, true]]) {
    const calls = []
    const form = { id, label: 'credential', username: 'deploy', password: 'secret', privateKey: 'private', publicKey: 'public', certificate: 'certificate', type: 'RSA' }
    const send = async (url, body) => { calls.push([url, body]); if (fails) throw new Error('failed') }
    const saving = { value: false }
    const editing = { value: true }
    const context = {
      form, saving, editing, http: { post: send, put: send },
      clearForm: () => calls.push('clear'), load: async () => calls.push('load'), emit: name => calls.push(name),
      showHttpError: () => calls.push('error'), ElMessage: { warning: () => calls.push('warning') }, t: key => key,
    }
    await method('save', context)()
    assert.equal(calls[0][0], id ? '/server-credentials/7' : '/server-credentials')
    assert.equal(calls[0][1].certificate, 'certificate')
    assert.equal(calls[0][1].password, 'secret')
    assert.deepEqual(calls.slice(1), fails ? ['error'] : ['clear', 'load', 'changed'])
    assert.equal(saving.value, false)
    assert.equal(editing.value, fails)
  }
})

// 校验空标签、缺失账号以及重复点击不发送写请求。
test('凭据无效输入和重复保存不写入接口', async () => {
  for (const [label, username, saving] of [[' ', 'deploy', false], ['label', '', false], ['label', 'deploy', true]]) {
    let warnings = 0
    await method('save', { form: { label, username, password: 'secret' }, saving: { value: saving },
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
