export const DEFAULT_SIDEBAR_THEME = 'midnight'

export const SIDEBAR_THEMES = Object.freeze([
  Object.freeze({ id: 'midnight', labelKey: 'nav.themes.midnight' }),
  Object.freeze({ id: 'cloud', labelKey: 'nav.themes.cloud' }),
  Object.freeze({ id: 'aurora', labelKey: 'nav.themes.aurora' })
])

const supportedThemes = new Set(SIDEBAR_THEMES.map(theme => theme.id))

/** 校验侧边栏主题标识，无效值统一回退默认主题。 */
export function resolveSidebarTheme(theme) {
  return supportedThemes.has(theme) ? theme : DEFAULT_SIDEBAR_THEME
}

/** 从浏览器存储读取侧边栏主题，隐私模式或存储异常时安全回退。 */
export function loadSidebarTheme(storageKey, runtime = globalThis) {
  try {
    return resolveSidebarTheme(runtime.localStorage.getItem(storageKey))
  } catch {
    return DEFAULT_SIDEBAR_THEME
  }
}

/** 持久化有效侧边栏主题，写入失败时仍返回可立即应用的主题。 */
export function persistSidebarTheme(storageKey, theme, runtime = globalThis) {
  const resolvedTheme = resolveSidebarTheme(theme)
  try {
    runtime.localStorage.setItem(storageKey, resolvedTheme)
  } catch {
    return resolvedTheme
  }
  return resolvedTheme
}
