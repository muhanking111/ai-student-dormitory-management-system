import { apiRequest } from './client'

export interface PageResponse<T> {
  records: T[]
  total: number
  page: number
  pageSize: number
}

export interface RoleOption {
  id: number
  code: string
  name: string
  enabled: boolean
  builtIn: boolean
}

export interface UserAccount {
  id: number
  username: string
  displayName: string
  enabled: boolean
  roles: RoleOption[]
}

export interface Permission {
  id: number
  code: string
  name: string
  module: string
}

export interface Role extends RoleOption {
  description: string
  permissionIds: number[]
  permissionCodes: string[]
}

export interface UserInput {
  username?: string
  displayName: string
  password?: string
  enabled: boolean
  roleIds: number[]
}

export interface RoleInput {
  code?: string
  name: string
  description: string
  enabled: boolean
  permissionIds: number[]
}

export interface PageQuery {
  page?: number
  pageSize?: number
  keyword?: string
  enabled?: boolean
}

function toQueryString(query: PageQuery) {
  const params = new URLSearchParams()
  params.set('page', String(query.page ?? 1))
  params.set('pageSize', String(query.pageSize ?? 10))
  if (query.keyword) params.set('keyword', query.keyword)
  if (query.enabled !== undefined) params.set('enabled', String(query.enabled))
  return params.toString()
}

export function fetchUsers(query: PageQuery = {}) {
  return apiRequest<PageResponse<UserAccount>>(`/api/users?${toQueryString(query)}`)
}

export function createUser(input: UserInput) {
  return apiRequest<UserAccount>('/api/users', { method: 'POST', body: JSON.stringify(input) })
}

export function updateUser(id: number, input: UserInput) {
  return apiRequest<UserAccount>(`/api/users/${id}`, { method: 'PATCH', body: JSON.stringify(input) })
}

export function deleteUser(id: number) {
  return apiRequest<void>(`/api/users/${id}`, { method: 'DELETE' })
}

export function fetchRoles(query: PageQuery = {}) {
  return apiRequest<PageResponse<Role>>(`/api/roles?${toQueryString(query)}`)
}

export function fetchRoleOptions() {
  return apiRequest<RoleOption[]>('/api/roles/options')
}

export function fetchPermissions() {
  return apiRequest<Permission[]>('/api/permissions')
}

export function createRole(input: RoleInput) {
  return apiRequest<Role>('/api/roles', { method: 'POST', body: JSON.stringify(input) })
}

export function updateRole(id: number, input: RoleInput) {
  return apiRequest<Role>(`/api/roles/${id}`, { method: 'PATCH', body: JSON.stringify(input) })
}

export function deleteRole(id: number) {
  return apiRequest<void>(`/api/roles/${id}`, { method: 'DELETE' })
}
