const defaults = {
  code: 'ai-platform',
  nameEn: 'AI Platform',
  nameZh: 'AI平台',
  shortName: 'AI',
  routeHealthCheckEnabled: true,
  routeHealthCheckIntervalMs: 3600000
}

export const appConfig = { ...defaults, ...(window.__APP_CONFIG__ || {}) }
