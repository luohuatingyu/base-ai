import axios from 'axios'
import { appConfig } from '../config'
import i18n from '../locales'

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
  (response) => {
    if (response.data && typeof response.data.success === 'boolean' && 'data' in response.data) {
      response.api = response.data
      response.data = response.data.data
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(tokenKey)
      if (location.pathname !== '/login') location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default http
