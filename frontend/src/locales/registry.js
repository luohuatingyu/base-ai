import zhCN from './zh-CN'
import enUS from './en-US'
import elementZhCn from 'element-plus/dist/locale/zh-cn.mjs'
import elementEn from 'element-plus/dist/locale/en.mjs'

/**
 * 支持语言注册表：新增语言只需在此追加一项，无需改动切换器、布局或 Element Plus 映射逻辑。
 *
 * 字段说明：
 * - code：vue-i18n 语言编码，同时作为 localStorage 持久化标识。
 * - labelKey：语言显示名的 i18n key（在各语言词条的 language.* 下定义）。
 * - flag：切换器展示的旗帜图标。
 * - messages：该语言的前端词条。
 * - element：对应的 Element Plus 语言包。
 * - sidebarExpandedWidth：侧栏展开宽度，长名称语言（如英文）需要更宽空间。
 */
export const LOCALES = Object.freeze([
  {
    code: 'zh-CN',
    labelKey: 'language.zhCN',
    flag: '🇨🇳',
    messages: zhCN,
    element: elementZhCn,
    sidebarExpandedWidth: '272px'
  },
  {
    code: 'en-US',
    labelKey: 'language.enUS',
    flag: '🇺🇸',
    messages: enUS,
    element: elementEn,
    sidebarExpandedWidth: '296px'
  }
])

/** 默认语言编码，作为未持久化选择和回退语言的统一来源。 */
export const DEFAULT_LOCALE = 'zh-CN'

/** 按编码查找语言配置，未命中时回退默认语言，保证调用方始终拿到有效配置。 */
export function findLocale(code) {
  return LOCALES.find(locale => locale.code === code)
    || LOCALES.find(locale => locale.code === DEFAULT_LOCALE)
    || LOCALES[0]
}

/** 汇总各语言词条，供 createI18n 的 messages 使用。 */
export function buildMessages() {
  return Object.fromEntries(LOCALES.map(locale => [locale.code, locale.messages]))
}
