import { defineStore } from 'pinia'
import { http, authToken, setUnauthorizedHandler } from '../api/http'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    user: null,
    ready: false
  }),
  getters: {
    isAuthenticated: (state) => !!state.user && !!authToken.current,
    isAdmin: (state) => !!state.user && state.user.role === 'ADMIN',
    pages: (state) => (state.user ? state.user.pages : [])
  },
  actions: {
    hasPage(code) {
      return this.isAdmin || this.pages.indexOf(code) !== -1
    },
    homeRoute() {
      if (this.isAdmin) {
        return '/admin/users'
      }
      return this.pages.length > 0 ? '/pages/' + this.pages[0] : '/forbidden'
    },
    async login(email, password) {
      const resp = await http.post('/auth/login', { email, password })
      authToken.current = resp.data.accessToken
      this.user = resp.data.user
    },
    // Попытка восстановить сессию по refresh-cookie при загрузке страницы.
    async restore() {
      try {
        const resp = await http.post('/auth/refresh')
        authToken.current = resp.data.accessToken
        const me = await http.get('/auth/me')
        this.user = me.data
        return true
      } catch (e) {
        authToken.current = null
        this.user = null
        return false
      }
    },
    async logout() {
      try {
        await http.post('/auth/logout')
      } catch (e) {
        // даже если сервер недоступен, сессию на клиенте гасим
      }
      authToken.current = null
      this.user = null
    },
    clearSession() {
      authToken.current = null
      this.user = null
    },
    installUnauthorizedHandler(router) {
      setUnauthorizedHandler(() => {
        this.clearSession()
        router.push('/login')
      })
    }
  }
})