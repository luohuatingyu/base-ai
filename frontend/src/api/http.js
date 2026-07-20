import axios from 'axios'
import { appConfig } from '../config'

const tokenKey = `${appConfig.code}-token`

const http = axios.create({ baseURL: '/api', timeout: 65000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem(tokenKey)
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem(tokenKey)
      if (location.pathname !== '/login') location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default http
