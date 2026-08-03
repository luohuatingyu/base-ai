import axios from 'axios'
import { ElMessage } from 'element-plus'
import { appConfig } from '../config'
import i18n from '../locales'
import { unwrapApiResponse } from '../utils/apiResponse'
import {
  classifyHttpError,
  isHttpErrorNotified,
  markHttpErrorNotified,
  normalizeHttpErrorResponse,
  silenceHttpError
} from '../utils/httpError'

const tokenKey = `${appConfig.code}-token`

const http = axios.create({ baseURL: '/api', timeout: 65000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(tokenKey)
  if (token) config.headers.Authorization = `Bearer ${token}`
  // 每次请求读取当前界面语言，切换语言后无需刷新页面。
  config.headers['Accept-Language'] = i18n.global.locale.value
  return config
})

http.interceptors.response.use(
  unwrapApiResponse,
  async (error) => {
    await normalizeHttpErrorResponse(error)
    if (error.response?.status === 401) {
      localStorage.removeItem(tokenKey)
      if (location.pathname !== '/login') {
        silenceHttpError(error)
        const redirect = `${location.pathname}${location.search}${location.hash}`
        location.href = `/login?redirect=${encodeURIComponent(redirect)}`
      }
    }
    if (!error.config?.silentError) showHttpError(error)
    return Promise.reject(error)
  }
)

/** 展示一次按响应状态分类的请求错误，页面可复用且不会重复弹窗。 */
export function showHttpError(error, fallbackKey = 'common.failed') {
  if (isHttpErrorNotified(error)) return false
  const result = classifyHttpError(error, i18n.global.t, fallbackKey)
  if (result.silent) return false
  markHttpErrorNotified(error)
  ElMessage({ message: result.message, type: result.type, grouping: true })
  return true
}

export default http
