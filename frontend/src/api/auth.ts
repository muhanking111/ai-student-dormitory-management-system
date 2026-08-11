import { apiRequest } from './client'

export interface UserSession {
  id?: number
  username?: string
  userName: string
  roleCode: string
  roleCodes: string[]
  permissions: string[]
}

export interface LoginCredentials {
  username: string
  password: string
}

export function login(credentials: LoginCredentials) {
  return apiRequest<UserSession>('/api/auth/login', {
    method: 'POST',
    body: JSON.stringify(credentials),
  })
}

export function getCurrentUser() {
  return apiRequest<UserSession>('/api/auth/me')
}

export function logout() {
  return apiRequest<null>('/api/auth/logout', { method: 'POST' })
}
