import { defineStore } from 'pinia'
import http from '../api/http'

/** 登录态：token + 用户信息，持久化到 localStorage */
export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('token') || '',
    user: JSON.parse(localStorage.getItem('user') || 'null')
  }),
  getters: {
    isLoggedIn: (s) => !!s.token,
    isAdmin: (s) => s.user?.role === 'ADMIN'
  },
  actions: {
    save(auth) {
      this.token = auth.token
      this.user = { userId: auth.userId, email: auth.email, nickname: auth.nickname, role: auth.role }
      localStorage.setItem('token', this.token)
      localStorage.setItem('user', JSON.stringify(this.user))
    },
    async logout() {
      try { await http.post('/auth/logout') } catch (e) { /* 忽略 */ }
      this.token = ''
      this.user = null
      localStorage.removeItem('token')
      localStorage.removeItem('user')
    },
    async refreshMe() {
      const { data } = await http.get('/auth/me')
      if (data?.success) {
        this.user = data.data
        localStorage.setItem('user', JSON.stringify(this.user))
      }
    }
  }
})
