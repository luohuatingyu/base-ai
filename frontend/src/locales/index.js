import { createI18n } from 'vue-i18n'
import { LOCALES, DEFAULT_LOCALE, buildMessages, findLocale } from './registry'

// 从 localStorage 获取保存的语言设置，未命中或不支持时回退默认语言
const savedLocale = localStorage.getItem('locale')
const initialLocale = LOCALES.some(locale => locale.code === savedLocale) ? savedLocale : DEFAULT_LOCALE

const i18n = createI18n({
  legacy: false, // 使用 Composition API 模式
  locale: initialLocale,
  fallbackLocale: DEFAULT_LOCALE,
  messages: buildMessages()
})

// 导出当前语言对应的 Element Plus 语言包，供 Element Plus 组件使用
export function getElementLocale() {
  return findLocale(i18n.global.locale.value).element
}

export default i18n
