import axios from 'axios'

const http = axios.create({ baseURL: '/api', timeout: 65000 })

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('base-ai-token')
  if (token) config.headers.Authorization = `Bearer ${token}`
  return config
})

http.interceptors.response.use(
  (response) => response,
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('base-ai-token')
      if (location.pathname !== '/login') location.href = '/login'
    }
    return Promise.reject(error)
  }
)

export default http
