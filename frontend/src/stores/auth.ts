import { defineStore } from 'pinia'
import { getCurrentUser, login as loginRequest, logout as logoutRequest } from '../api/auth'
import type { LoginCredentials, UserSession } from '../api/auth'
import { ApiError } from '../api/client'

interface AuthState {
  user: UserSession | null
  initialized: boolean
  loading: boolean
}

let sessionCleanupHandler: (() => void) | undefined

export function setSessionCleanupHandler(handler: (() => void) | undefined) {
  sessionCleanupHandler = handler
}

export const useAuthStore = defineStore('auth', {
  state: (): AuthState => ({
    user: null,
    initialized: false,
    loading: false,
  }),
  getters: {
    isAuthenticated: (state) => state.user !== null,
    hasPermission: (state) => (permission: string) =>
      Boolean(state.user?.permissions?.includes('*') || state.user?.permissions?.includes(permission)),
  },
  actions: {
    async login(credentials: LoginCredentials) {
      this.loading = true
      try {
        const session = await loginRequest(credentials)
        sessionCleanupHandler?.()
        this.user = session
        this.initialized = true
      } finally {
        this.loading = false
      }
    },
    async ensureSession() {
      if (this.initialized) return this.user !== null
      try {
        this.user = await getCurrentUser()
      } catch (error) {
        if (!(error instanceof ApiError) || error.status !== 401) throw error
        this.user = null
      } finally {
        this.initialized = true
      }
      return this.user !== null
    },
    expireSession() {
      sessionCleanupHandler?.()
      this.user = null
      this.initialized = true
    },
    async logout() {
      try {
        await logoutRequest()
      } finally {
        this.expireSession()
      }
    },
  },
})
