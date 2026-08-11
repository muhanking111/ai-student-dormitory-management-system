import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import { getCurrentUser, login, logout } from '../api/auth'
import { ApiError } from '../api/client'
import { createDormitory, deleteDormitory, fetchDormitoryPage, updateDormitory } from '../api/dormitory'
import {
  createRole,
  createUser,
  deleteRole,
  deleteUser,
  fetchPermissions,
  fetchRoleOptions,
  fetchRoles,
  fetchUsers,
  updateRole,
  updateUser,
} from '../api/rbac'
import {
  createBuilding,
  deleteBuilding,
  fetchBeds,
  fetchBuildingOptions,
  fetchBuildings,
  updateBedStatus,
  updateBuilding,
} from '../api/resource'
import { useAuthStore } from '../stores/auth'
import { useRbacStore } from '../stores/rbac'
import { useResourceStore } from '../stores/resource'

vi.mock('../api/auth', () => ({ login: vi.fn(), getCurrentUser: vi.fn(), logout: vi.fn() }))
vi.mock('../api/dormitory', () => ({
  fetchDormitoryPage: vi.fn(), createDormitory: vi.fn(), updateDormitory: vi.fn(), deleteDormitory: vi.fn(),
}))
vi.mock('../api/rbac', () => ({
  fetchUsers: vi.fn(), createUser: vi.fn(), updateUser: vi.fn(), deleteUser: vi.fn(),
  fetchRoles: vi.fn(), fetchRoleOptions: vi.fn(), fetchPermissions: vi.fn(),
  createRole: vi.fn(), updateRole: vi.fn(), deleteRole: vi.fn(),
}))
vi.mock('../api/resource', () => ({
  fetchBuildings: vi.fn(), fetchBuildingOptions: vi.fn(), createBuilding: vi.fn(), updateBuilding: vi.fn(), deleteBuilding: vi.fn(),
  fetchBeds: vi.fn(), updateBedStatus: vi.fn(),
}))

const session = { id: 1, username: 'admin', userName: '管理员', roleCode: 'ADMIN', roleCodes: ['ADMIN'], permissions: ['dashboard:read'] }
const emptyPage = { records: [], total: 0, page: 1, pageSize: 10 }
const roleOption = { id: 1, code: 'ADMIN', name: '系统管理员', enabled: true, builtIn: true }
const permission = { id: 1, code: 'dashboard:read', name: '查看数据驾驶舱', module: 'dashboard' }
const building = { id: 1, code: 'B01', name: '1号楼', genderType: '男生宿舍', floors: 6, manager: '管理员', status: '启用' as const }
const room = { id: 1, name: '101宿舍', type: '男生宿舍', buildingId: 1, building: '1号楼', beds: 4, occupied: 1, vacant: 3, status: '入住中' as const }
const bed = { id: 1, bedNo: '01', status: '空闲' as const, dormitoryId: 1, dormitoryName: '101宿舍', buildingId: 1, buildingName: '1号楼' }

describe('认证 Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('登录、权限判断、缓存会话和退出形成闭环', async () => {
    vi.mocked(login).mockResolvedValue(session)
    vi.mocked(getCurrentUser).mockResolvedValue(session)
    vi.mocked(logout).mockResolvedValue(null)
    const store = useAuthStore()

    await store.login({ username: 'admin', password: 'local-test-password' })
    expect(store.isAuthenticated).toBe(true)
    expect(store.hasPermission('dashboard:read')).toBe(true)
    expect(store.hasPermission('student:write')).toBe(false)
    expect(store.loading).toBe(false)
    expect(await store.ensureSession()).toBe(true)
    expect(getCurrentUser).not.toHaveBeenCalled()

    store.$reset()
    expect(await store.ensureSession()).toBe(true)
    expect(getCurrentUser).toHaveBeenCalledOnce()
    await store.logout()
    expect(store.user).toBeNull()
    expect(store.initialized).toBe(true)
  })

  it('将 401 视为会话失效并透传其他服务错误', async () => {
    vi.mocked(getCurrentUser).mockRejectedValueOnce(new ApiError(401, 401, '登录已过期'))
    const store = useAuthStore()
    await expect(store.ensureSession()).resolves.toBe(false)
    expect(store.initialized).toBe(true)

    store.$reset()
    vi.mocked(getCurrentUser).mockRejectedValueOnce(new Error('服务不可用'))
    await expect(store.ensureSession()).rejects.toThrow('服务不可用')
    expect(store.initialized).toBe(true)
  })

  it('退出请求失败时仍清空本地会话', async () => {
    vi.mocked(logout).mockRejectedValue(new Error('网络异常'))
    const store = useAuthStore()
    store.user = session
    await expect(store.logout()).rejects.toThrow('网络异常')
    expect(store.user).toBeNull()
  })
})

describe('RBAC Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(fetchUsers).mockResolvedValue({ ...emptyPage, records: [{ id: 1, username: 'admin', displayName: '管理员', enabled: true, roles: [roleOption] }], total: 1 })
    vi.mocked(fetchRoles).mockResolvedValue({ ...emptyPage, records: [{ ...roleOption, description: '全部权限', permissionIds: [1], permissionCodes: ['dashboard:read'] }], total: 1 })
    vi.mocked(fetchRoleOptions).mockResolvedValue([roleOption])
    vi.mocked(fetchPermissions).mockResolvedValue([permission])
  })

  it('加载用户、角色、角色选项和权限', async () => {
    const store = useRbacStore()
    await store.loadUsers({ keyword: 'admin' })
    await store.loadRoles({ enabled: true })
    await store.loadRoleOptions()
    expect(store.userTotal).toBe(1)
    expect(store.roleTotal).toBe(1)
    expect(store.roleOptions).toEqual([roleOption])
    expect(store.permissions).toEqual([permission])
    expect(store.loading).toBe(false)
  })

  it('创建、更新、删除用户和角色后刷新原查询', async () => {
    const store = useRbacStore()
    const userInput = { username: 'tester', displayName: '测试员', enabled: true, roleIds: [1] }
    const roleInput = { code: 'TEST_ROLE', name: '测试角色', description: '测试', enabled: true, permissionIds: [1] }

    await store.saveUser(null, userInput, { page: 2 })
    await store.saveUser(2, userInput, { page: 2 })
    await store.removeUser(2, { page: 2 })
    await store.saveRole(null, roleInput, { page: 3 })
    await store.saveRole(3, roleInput, { page: 3 })
    await store.removeRole(3, { page: 3 })

    expect(createUser).toHaveBeenCalledWith(userInput)
    expect(updateUser).toHaveBeenCalledWith(2, userInput)
    expect(deleteUser).toHaveBeenCalledWith(2)
    expect(createRole).toHaveBeenCalledWith(roleInput)
    expect(updateRole).toHaveBeenCalledWith(3, roleInput)
    expect(deleteRole).toHaveBeenCalledWith(3)
    expect(store.saving).toBe(false)
  })

  it('加载失败记录错误并恢复 loading', async () => {
    vi.mocked(fetchUsers).mockRejectedValueOnce(new Error('用户接口失败'))
    const store = useRbacStore()
    await expect(store.loadUsers()).rejects.toThrow('用户接口失败')
    expect(store.error).toBe('用户接口失败')
    expect(store.loading).toBe(false)

    vi.mocked(fetchRoles).mockRejectedValueOnce('未知错误')
    await expect(store.loadRoles()).rejects.toBe('未知错误')
    expect(store.error).toBe('角色权限加载失败')
  })
})

describe('住宿资源 Store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
    vi.mocked(fetchBuildings).mockResolvedValue({ ...emptyPage, records: [building], total: 1 })
    vi.mocked(fetchBuildingOptions).mockResolvedValue([building])
    vi.mocked(fetchDormitoryPage).mockResolvedValue({ ...emptyPage, records: [room], total: 1 })
    vi.mocked(fetchBeds).mockResolvedValue({ ...emptyPage, records: [bed], total: 1 })
  })

  it('加载楼栋、宿舍和床位分页及选项', async () => {
    const store = useResourceStore()
    await store.loadBuildings({ keyword: '1号' })
    await store.loadDormitories({ buildingId: 1 })
    await store.loadBeds({ buildingId: 1, dormitoryId: 1 })
    expect(store.buildingTotal).toBe(1)
    expect(store.dormitoryTotal).toBe(1)
    expect(store.bedTotal).toBe(1)
    expect(store.buildingOptions).toEqual([building])
    expect(store.dormitories).toEqual([room])
    expect(store.beds).toEqual([bed])
  })

  it('完成楼栋、宿舍、床位的创建更新删除并刷新', async () => {
    const store = useResourceStore()
    const buildingInput = { code: 'B01', name: '1号楼', genderType: '男生宿舍', floors: 6, manager: '管理员', status: '启用' as const }
    const roomInput = { name: '101宿舍', type: '男生宿舍', buildingId: 1, beds: 4, occupied: 0 }
    await store.saveBuilding(null, buildingInput, {})
    await store.saveBuilding(1, buildingInput, {})
    await store.removeBuilding(1, {})
    await store.saveDormitory(null, roomInput, {})
    await store.saveDormitory(1, roomInput, {})
    await store.removeDormitory(1, {})
    await store.setBedStatus(1, '维修中', {})

    expect(createBuilding).toHaveBeenCalledWith(buildingInput)
    expect(updateBuilding).toHaveBeenCalledWith(1, buildingInput)
    expect(deleteBuilding).toHaveBeenCalledWith(1)
    expect(createDormitory).toHaveBeenCalledWith(roomInput)
    expect(updateDormitory).toHaveBeenCalledWith(1, roomInput)
    expect(deleteDormitory).toHaveBeenCalledWith(1)
    expect(updateBedStatus).toHaveBeenCalledWith(1, '维修中')
    expect(store.saving).toBe(false)
  })

  it('三类加载失败均给出明确错误并恢复 loading', async () => {
    const store = useResourceStore()
    vi.mocked(fetchBuildings).mockRejectedValueOnce(new Error('楼栋失败'))
    await expect(store.loadBuildings()).rejects.toThrow('楼栋失败')
    expect(store.error).toBe('楼栋失败')

    vi.mocked(fetchDormitoryPage).mockRejectedValueOnce('宿舍失败')
    await expect(store.loadDormitories()).rejects.toBe('宿舍失败')
    expect(store.error).toBe('宿舍数据加载失败')

    vi.mocked(fetchBeds).mockRejectedValueOnce(new Error('床位失败'))
    await expect(store.loadBeds()).rejects.toThrow('床位失败')
    expect(store.error).toBe('床位失败')
    expect(store.loading).toBe(false)
  })
})
