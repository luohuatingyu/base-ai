import { defineStore } from 'pinia'
import http from '../api/http'
import { appConfig } from '../config'

/** 清理旧版本遗留的可被脚本读取的 JWT，存储不可用时不阻断页面启动。 */
function clearLegacyToken() {
  try { localStorage.removeItem(`${appConfig.code}-token`) } catch { /* 浏览器禁用存储时无需迁移。 */ }
}

clearLegacyToken()

export const useAuthStore = defineStore('auth', {
  state: () => ({ user: null, initialized: false }),
  getters: {
    isLoggedIn: (state) => Boolean(state.user),
    isAdmin: (state) => state.user?.roles?.includes('ADMIN') || false
  },
  actions: {
    /** 登录后保存令牌和用户权限快照。 */
    async login(username, password) {
      const { data } = await http.post('/auth/login', { username, password })
      this.user = data.user
      this.initialized = true
    },
    /** 从后端刷新当前用户权限。 */
    async fetchMe(silent = false) {
      try {
        const { data } = await http.get('/auth/me', { skipAuthRedirect: silent, silentError: silent })
        this.user = data
      } catch (error) {
        this.user = null
        throw error
      } finally {
        this.initialized = true
      }
    },
    /** 撤销服务端令牌并清理本地状态。 */
    async logout() {
      try { await http.post('/auth/logout') } finally {
        this.user = null
        this.initialized = true
      }
    },
    /** 判断当前用户是否拥有页面权限。 */
    hasPermission(permission) {
      if (!permission || this.isAdmin || this.user?.permissions?.includes(permission)) return true
      const separator = permission.lastIndexOf(':')
      return separator > 0 && this.user?.permissions?.includes(`${permission.slice(0, separator)}:manage`) || false
    }
  }
})
