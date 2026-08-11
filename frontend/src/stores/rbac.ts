import { defineStore } from 'pinia'
import {
  createRole as createRoleRequest,
  createUser as createUserRequest,
  deleteRole as deleteRoleRequest,
  deleteUser as deleteUserRequest,
  fetchPermissions,
  fetchRoleOptions,
  fetchRoles,
  fetchUsers,
  updateRole as updateRoleRequest,
  updateUser as updateUserRequest,
} from '../api/rbac'
import type { PageQuery, Permission, Role, RoleInput, RoleOption, UserAccount, UserInput } from '../api/rbac'

interface RbacState {
  users: UserAccount[]
  userTotal: number
  roles: Role[]
  roleTotal: number
  roleOptions: RoleOption[]
  permissions: Permission[]
  loading: boolean
  saving: boolean
  error: string | null
}

export const useRbacStore = defineStore('rbac', {
  state: (): RbacState => ({
    users: [],
    userTotal: 0,
    roles: [],
    roleTotal: 0,
    roleOptions: [],
    permissions: [],
    loading: false,
    saving: false,
    error: null,
  }),
  actions: {
    async loadUsers(query: PageQuery = {}) {
      this.loading = true
      this.error = null
      try {
        const page = await fetchUsers(query)
        this.users = page.records
        this.userTotal = page.total
      } catch (error) {
        this.error = error instanceof Error ? error.message : '用户数据加载失败'
        throw error
      } finally {
        this.loading = false
      }
    },
    async loadRoles(query: PageQuery = {}) {
      this.loading = true
      this.error = null
      try {
        const [page, options, permissions] = await Promise.all([
          fetchRoles(query),
          fetchRoleOptions(),
          fetchPermissions(),
        ])
        this.roles = page.records
        this.roleTotal = page.total
        this.roleOptions = options
        this.permissions = permissions
      } catch (error) {
        this.error = error instanceof Error ? error.message : '角色权限加载失败'
        throw error
      } finally {
        this.loading = false
      }
    },
    async loadRoleOptions() {
      this.roleOptions = await fetchRoleOptions()
    },
    async saveUser(id: number | null, input: UserInput, query: PageQuery) {
      this.saving = true
      try {
        if (id) await updateUserRequest(id, input)
        else await createUserRequest(input)
        await this.loadUsers(query)
      } finally {
        this.saving = false
      }
    },
    async removeUser(id: number, query: PageQuery) {
      await deleteUserRequest(id)
      await this.loadUsers(query)
    },
    async saveRole(id: number | null, input: RoleInput, query: PageQuery) {
      this.saving = true
      try {
        if (id) await updateRoleRequest(id, input)
        else await createRoleRequest(input)
        await this.loadRoles(query)
      } finally {
        this.saving = false
      }
    },
    async removeRole(id: number, query: PageQuery) {
      await deleteRoleRequest(id)
      await this.loadRoles(query)
    },
  },
})
