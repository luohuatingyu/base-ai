import assert from 'node:assert/strict'
import { readFileSync } from 'node:fs'
import test from 'node:test'

const globalStyles = readFileSync(new URL('../src/styles.css', import.meta.url), 'utf8')
const automationStyles = readFileSync(new URL('../src/automation.css', import.meta.url), 'utf8')

/** 转义选择器并提取对应的 CSS 声明，确保布局规则可以被自动回归。 */
function declarations(source, selector) {
  const escaped = selector.replace(/[.*+?^${}()|[\]\\]/g, '\\$&')
  const match = source.match(new RegExp(`${escaped}\\s*\\{([^}]*)\\}`))
  assert.ok(match, `缺少布局选择器：${selector}`)
  return match[1]
}

/** 验证指定 CSS 规则包含关键声明。 */
function assertDeclarations(source, selector, expected) {
  const rule = declarations(source, selector)
  for (const declaration of expected) {
    assert.match(rule, declaration, `${selector} 缺少布局声明 ${declaration}`)
  }
}

test('后台骨架和内容容器允许正确收缩且不会截断页面', () => {
  assertDeclarations(globalStyles, '.shell > .el-container', [/min-width:\s*0/])
  assertDeclarations(globalStyles, '.main', [/min-width:\s*0/, /overflow:\s*auto/])
  assertDeclarations(globalStyles, '.panel', [/width:\s*100%/, /min-width:\s*0/])
  assertDeclarations(globalStyles, '.topbar-title', [/flex:\s*1/, /overflow:\s*hidden/])
  assertDeclarations(globalStyles, '.user-chip', [/max-width:\s*100%/, /text-overflow:\s*ellipsis/])
  assertDeclarations(globalStyles, '.section-head > div', [/min-width:\s*0/])
})

test('AI 对话仅滚动消息区域并固定上下操作区域', () => {
  assertDeclarations(globalStyles, '.chat-panel', [/height:\s*calc\(/, /display:\s*flex/, /flex-direction:\s*column/])
  assertDeclarations(globalStyles, '.chat-tabs', [/display:\s*flex/, /flex:\s*1/, /flex-direction:\s*column/, /min-height:\s*0/])
  assertDeclarations(globalStyles, '.chat-tabs .el-tabs__content', [/flex:\s*1/, /min-height:\s*0/, /overflow:\s*hidden/])
  assertDeclarations(globalStyles, '.chat-tabs .el-tab-pane', [/height:\s*100%/, /display:\s*flex/, /flex-direction:\s*column/, /min-height:\s*0/])
  assertDeclarations(globalStyles, '.model-config', [/flex:\s*0\s+0\s+auto/])
  assertDeclarations(globalStyles, '.messages', [/flex:\s*1/, /min-height:\s*0/, /overflow-y:\s*auto/])
  assertDeclarations(
    globalStyles,
    '.chat-tabs .el-tab-pane > .pending-images,\n.chat-tabs .el-tab-pane > .el-textarea,\n.chat-tabs .el-tab-pane > .chat-actions',
    [/flex:\s*0\s+0\s+auto/]
  )
})

test('收缩侧边栏的菜单宽度适配内层可用空间，图标保持居中', () => {
  assertDeclarations(globalStyles, '.sidebar--collapsed .nav-scroll', [/width:\s*100%/])
  assertDeclarations(globalStyles, '.sidebar--collapsed .nav.el-menu--collapse', [/width:\s*100%/])
  assertDeclarations(globalStyles, '.sidebar--collapsed .nav .el-menu-item,\n.sidebar--collapsed .nav .el-sub-menu__title', [/padding:\s*0\s*!important/, /justify-content:\s*center/])
})

test('表格、分页和表单内容遵守统一的对齐与溢出规则', () => {
  assertDeclarations(globalStyles, '.el-table', [/width:\s*100%/, /max-width:\s*100%/])
  assertDeclarations(globalStyles, '.el-table .cell', [/overflow-wrap:\s*anywhere/])
  assertDeclarations(globalStyles, '.el-pagination', [/flex-wrap:\s*wrap/, /gap:\s*8px/])
  assertDeclarations(globalStyles, '.el-form-item__content', [/min-width:\s*0/])
  assertDeclarations(globalStyles, '.el-dialog__footer', [/display:\s*flex/, /justify-content:\s*flex-end/])
  assertDeclarations(globalStyles, '.el-drawer', [/max-width:\s*100vw/])
})

test('表单标签、输入控件和开关文案使用一致的垂直节奏', () => {
  assertDeclarations(globalStyles, '.el-form-item', [/align-items:\s*flex-start/])
  assertDeclarations(globalStyles, '.el-form-item__label', [/min-height:\s*32px/, /align-items:\s*center/, /line-height:\s*20px/])
  assertDeclarations(globalStyles, '.el-form-item__content', [/min-height:\s*32px/, /align-items:\s*center/, /line-height:\s*20px/])
  assertDeclarations(globalStyles, '.el-switch__label', [/display:\s*inline-flex/, /align-items:\s*center/, /height:\s*32px/])
  assertDeclarations(globalStyles, '.el-switch__label *', [/line-height:\s*20px/])
  assertDeclarations(globalStyles, '.el-form > .el-form-item:last-child', [/margin-bottom:\s*0/])
  assert.doesNotMatch(globalStyles, /(?:^|\n)\.el-form-item:last-child\s*\{/)
  assertDeclarations(automationStyles, '.form-help', [/display:\s*inline-flex/, /align-items:\s*center/, /min-height:\s*32px/])
})

test('平板断点将字典双栏切换为单列并保持查询区可收缩', () => {
  assert.match(globalStyles, /@media\s*\(max-width:\s*900px\)[\s\S]*?\.panel\s*>\s*\.el-row\s*>\s*\.el-col\s*\{[^}]*max-width:\s*100%[^}]*flex:\s*0\s+0\s+100%/)
  assert.match(globalStyles, /@media\s*\(max-width:\s*900px\)[\s\S]*?\.panel\s*>\s*\.el-row\s*>\s*\.el-col\s*\+\s*\.el-col\s*\{[^}]*margin-top:/)
  assertDeclarations(automationStyles, '.filter-row > .el-input', [/min-width:\s*0/])
  assertDeclarations(automationStyles, '.filter-row > .el-select', [/min-width:\s*0/])
})

test('手机断点使分页、表单、弹窗和操作区适配窄视口', () => {
  assert.match(globalStyles, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.el-pagination\s*\{[^}]*justify-content:\s*center/)
  assert.match(globalStyles, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.el-dialog\s+\.el-form-item\s*\{[^}]*display:\s*block/)
  assert.match(globalStyles, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.el-dialog\s+\.el-form-item__label\s*\{[^}]*width:\s*auto\s*!important/)
  assert.match(globalStyles, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.el-drawer:not\(\.mobile-nav-drawer\)\s*\{[^}]*width:\s*100%\s*!important/)
  assert.match(automationStyles, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.filter-row\s*>\s*\.el-input,[\s\S]*?flex:\s*1\s+1\s+100%/)
  assert.match(automationStyles, /@media\s*\(max-width:\s*600px\)[\s\S]*?\.progress-toolbar\s*>\s*div\s*\{[^}]*width:\s*100%/)
})
