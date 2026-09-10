const defaults = {
  code: 'ai-platform',
  nameEn: 'AI Platform',
  nameZh: 'AI平台',
  shortName: 'AI',
  defaultLocale: 'en-US',
  baseUrl: '',
  basePath: '/',
  routeHealthCheckEnabled: true,
  routeHealthCheckIntervalMs: 3600000
}

const supportedLocales = Object.freeze(['zh-CN', 'en-US'])
const runtimeConfig = globalThis.window?.__APP_CONFIG__ || {}

/** 规范同源部署前缀，拒绝协议、查询串、路径穿越和非安全路径字符。 */
export function normalizeBasePath(value) {
  const candidate = typeof value === 'string' ? value.trim() : ''
  if (!candidate || candidate === '/') return '/'
  if (!candidate.startsWith('/') || candidate.startsWith('//') || /[\\?#]/.test(candidate)) return '/'
  const segments = candidate.split('/').filter(Boolean)
  if (!segments.length || segments.some(segment => segment === '.' || segment === '..' || !/^[A-Za-z0-9._~-]+$/.test(segment))) return '/'
  return `/${segments.join('/')}`
}

/** 规范平台公开基础地址，避免畸形配置进入 Agent 一键安装 Shell 命令。 */
export function normalizePlatformBaseUrl(value) {
  const candidate = typeof value === 'string' ? value.trim() : ''
  if (!candidate) return ''
  let url
  try {
    url = new URL(candidate)
  } catch {
    return ''
  }
  if (!['http:', 'https:'].includes(url.protocol) || !url.hostname
      || url.username || url.password || url.search || url.hash) return ''
  const segments = url.pathname.split('/').filter(Boolean)
  if (segments.some(segment => segment === '.' || segment === '..'
      || !/^[A-Za-z0-9._~%-]+$/.test(segment))) return ''
  return `${url.origin}${segments.length ? `/${segments.join('/')}` : ''}`
}

export const appConfig = {
  ...defaults,
  ...runtimeConfig,
  baseUrl: normalizePlatformBaseUrl(runtimeConfig.baseUrl),
  basePath: normalizeBasePath(runtimeConfig.basePath)
}

/** 解析平台对外可达的基础地址：公开配置优先，缺省回退控制台同源地址加部署前缀。 */
export function resolvePlatformBaseUrl(origin, config = appConfig) {
  const configured = normalizePlatformBaseUrl(config?.baseUrl)
  if (configured) return configured
  const normalizedOrigin = normalizePlatformBaseUrl(origin)
  if (!normalizedOrigin) return ''
  const basePath = normalizeBasePath(config?.basePath)
  return basePath === '/' ? normalizedOrigin : `${normalizedOrigin}${basePath}`
}

/** 为应用内绝对路径添加当前部署前缀。 */
export function withBasePath(pathname, basePath = appConfig.basePath) {
  const normalizedBasePath = normalizeBasePath(basePath)
  const normalizedPath = String(pathname || '/').startsWith('/') ? String(pathname || '/') : `/${pathname}`
  return normalizedBasePath === '/' ? normalizedPath : `${normalizedBasePath}${normalizedPath === '/' ? '' : normalizedPath}`
}

/** 按优先级返回首个受支持语言，候选项均无效时回退英文。 */
export function resolveLocale(...candidates) {
  return candidates.find(candidate => supportedLocales.includes(candidate)) || defaults.defaultLocale
}

/** 根据当前语言返回唯一的平台名称，避免登录页同时展示中英文品牌。 */
export function getLocalizedPlatformName(locale, config = appConfig) {
  return resolveLocale(locale) === 'zh-CN' ? config.nameZh : config.nameEn
}
