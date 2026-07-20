import { defineStore } from 'pinia'
import http from '../api/http'
import { appConfig } from '../config'

const tokenKey = `${appConfig.code}-token`

export const useAuthStore = defineStore('auth', {
  state: () => ({ token: localStorage.getItem(tokenKey) || '', user: null }),
  getters: {
    isLoggedIn: (state) => Boolean(state.token),
    isAdmin: (state) => state.user?.roles?.includes('ADMIN') || false
  },
  actions: {
    /** 登录后保存令牌和用户权限快照。 */
    async login(username, password) {
      const { data } = await http.post('/auth/login', { username, password })
      this.token = data.token
      this.user = data.user
      localStorage.setItem(tokenKey, data.token)
    },
    /** 从后端刷新当前用户权限。 */
    async fetchMe() {
      const { data } = await http.get('/auth/me')
      this.user = data
    },
    /** 撤销服务端令牌并清理本地状态。 */
    async logout() {
      try { await http.post('/auth/logout') } finally {
        this.token = ''
        this.user = null
        localStorage.removeItem(tokenKey)
      }
    },
    /** 判断当前用户是否拥有页面权限。 */
    hasPermission(permission) {
      return !permission || this.isAdmin || this.user?.permissions?.includes(permission) || false
    }
  }
})
