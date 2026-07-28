import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'
import { isPromptFile, readPromptFile, withSystemPrompt } from '../src/utils/prompt.js'

const chatViewSource = readFileSync(new URL('../src/views/AiChatView.vue', import.meta.url), 'utf8')

test('识别 txt 和 md 提示词文件扩展名', () => {
  assert.equal(isPromptFile({ name: 'system.txt' }), true)
  assert.equal(isPromptFile({ name: 'system.MD' }), true)
  assert.equal(isPromptFile({ name: 'system.pdf' }), false)
  assert.equal(isPromptFile({ name: '' }), false)
})

test('读取非空提示词文件并保留 Markdown 内容', async () => {
  const content = await readPromptFile({ text: async () => '# 角色\n\n你是专业助手。' })

  assert.equal(content, '# 角色\n\n你是专业助手。')
})

test('拒绝空白提示词文件', async () => {
  await assert.rejects(readPromptFile({ text: async () => ' \n\t ' }), /Prompt file is empty/)
})

test('非空提示词作为首条系统消息发送，空提示词保持原消息不变', () => {
  const messages = [{ role: 'user', content: '你好' }]

  assert.deepEqual(withSystemPrompt(messages, '# 角色\n你是专业助手'), [
    { role: 'system', content: '# 角色\n你是专业助手' },
    { role: 'user', content: '你好' }
  ])
  assert.equal(withSystemPrompt(messages, '  '), messages)
})

test('提示词设置区域铺满 Tab 可用宽度', () => {
  const declarations = chatViewSource.match(/\.prompt-settings\s*\{([^}]*)\}/)?.[1]

  assert.ok(declarations, '缺少提示词设置区域样式')
  assert.match(declarations, /width:\s*100%/)
  assert.match(declarations, /min-width:\s*0/)
  assert.doesNotMatch(declarations, /max-width\s*:/)
})
