import { createI18n } from 'vue-i18n'
import zhCN from './zh-CN'
import enUS from './en-US'

// 从 localStorage 获取保存的语言设置，默认中文
const savedLocale = localStorage.getItem('locale') || 'zh-CN'

const i18n = createI18n({
  legacy: false, // 使用 Composition API 模式
  locale: savedLocale,
  fallbackLocale: 'zh-CN',
  messages: {
    'zh-CN': zhCN,
    'en-US': enUS
  }
})

// 导出语言映射函数，供 Element Plus 使用
export function getElementLocale() {
  const locale = i18n.global.locale.value
  return locale === 'en-US' ? 'en' : 'zh-cn'
}

export default i18n
