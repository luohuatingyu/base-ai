import { createI18n } from 'vue-i18n'
import { appConfig, resolveLocale } from '../config'
import { buildMessages, findLocale } from './registry'

// 优先使用浏览器保存语言，未命中时使用环境变量配置的系统默认语言
const savedLocale = localStorage.getItem('locale')
const configuredDefaultLocale = resolveLocale(appConfig.defaultLocale)
const initialLocale = resolveLocale(savedLocale, appConfig.defaultLocale)

const i18n = createI18n({
  legacy: false, // 使用 Composition API 模式
  locale: initialLocale,
  fallbackLocale: configuredDefaultLocale,
  messages: buildMessages()
})

// 导出当前语言对应的 Element Plus 语言包，供 Element Plus 组件使用
export function getElementLocale() {
  return findLocale(i18n.global.locale.value).element
}

export default i18n
