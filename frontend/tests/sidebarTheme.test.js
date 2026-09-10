import assert from 'node:assert/strict'
import test from 'node:test'
import {
  DEFAULT_SIDEBAR_THEME,
  SIDEBAR_THEMES,
  loadSidebarTheme,
  persistSidebarTheme,
  resolveSidebarTheme
} from '../src/utils/sidebarTheme.js'

test('侧边栏主题注册表提供三套稳定主题', () => {
  assert.deepEqual(SIDEBAR_THEMES.map(theme => theme.id), ['midnight', 'cloud', 'aurora'])
  assert.equal(DEFAULT_SIDEBAR_THEME, 'midnight')
})

test('有效主题保持不变，空值和非法主题回退暗夜蓝', () => {
  assert.equal(resolveSidebarTheme('cloud'), 'cloud')
  assert.equal(resolveSidebarTheme('aurora'), 'aurora')
  assert.equal(resolveSidebarTheme(''), 'midnight')
  assert.equal(resolveSidebarTheme(null), 'midnight')
  assert.equal(resolveSidebarTheme('unknown'), 'midnight')
})

test('读取已保存主题并在无效值或存储异常时安全回退', () => {
  const validRuntime = { localStorage: { getItem: () => 'cloud' } }
  const invalidRuntime = { localStorage: { getItem: () => 'legacy-theme' } }
  const blockedRuntime = { get localStorage() { throw new Error('storage blocked') } }

  assert.equal(loadSidebarTheme('base-ai-sidebar-theme', validRuntime), 'cloud')
  assert.equal(loadSidebarTheme('base-ai-sidebar-theme', invalidRuntime), 'midnight')
  assert.equal(loadSidebarTheme('base-ai-sidebar-theme', blockedRuntime), 'midnight')
})

test('持久化规范化主题且写入失败不影响当前页面切换', () => {
  const writes = []
  const writableRuntime = { localStorage: { setItem: (key, value) => writes.push([key, value]) } }
  const blockedRuntime = { localStorage: { setItem: () => { throw new Error('storage blocked') } } }

  assert.equal(persistSidebarTheme('base-ai-sidebar-theme', 'aurora', writableRuntime), 'aurora')
  assert.deepEqual(writes, [['base-ai-sidebar-theme', 'aurora']])
  assert.equal(persistSidebarTheme('base-ai-sidebar-theme', 'unknown', writableRuntime), 'midnight')
  assert.deepEqual(writes.at(-1), ['base-ai-sidebar-theme', 'midnight'])
  assert.equal(persistSidebarTheme('base-ai-sidebar-theme', 'cloud', blockedRuntime), 'cloud')
})
