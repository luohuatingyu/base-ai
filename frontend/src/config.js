const defaults = {
  code: 'ai-platform',
  nameEn: 'AI Platform',
  nameZh: 'AI平台',
  shortName: 'AI',
  defaultLocale: 'en-US',
  routeHealthCheckEnabled: true,
  routeHealthCheckIntervalMs: 3600000
}

const supportedLocales = Object.freeze(['zh-CN', 'en-US'])

export const appConfig = { ...defaults, ...(globalThis.window?.__APP_CONFIG__ || {}) }

/** 按优先级返回首个受支持语言，候选项均无效时回退英文。 */
export function resolveLocale(...candidates) {
  return candidates.find(candidate => supportedLocales.includes(candidate)) || defaults.defaultLocale
}

/** 根据当前语言返回唯一的平台名称，避免登录页同时展示中英文品牌。 */
export function getLocalizedPlatformName(locale, config = appConfig) {
  return resolveLocale(locale) === 'zh-CN' ? config.nameZh : config.nameEn
}
