import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import * as auth from './auth'
import * as checkin from './checkin'
import * as dormitory from './dormitory'
import * as operations from './operations'
import * as rbac from './rbac'
import * as resource from './resource'
import { clearCsrfToken } from './client'

function successResponse() {
  return {
    ok: true,
    status: 200,
    json: async () => ({ code: 0, message: 'success', data: { records: [], total: 0, page: 1, pageSize: 10 } }),
  }
}

describe('前端 API 契约', () => {
  const fetchMock = vi.fn()

  beforeEach(() => {
    clearCsrfToken()
    fetchMock.mockReset()
    fetchMock.mockImplementation(async (input: string | URL | Request) => String(input) === '/api/security/csrf'
      ? {
          ok: true,
          status: 200,
          json: async () => ({ code: 0, message: 'success', data: { token: 'csrf-contract' } }),
        }
      : successResponse())
    vi.stubGlobal('fetch', fetchMock)
  })

  afterEach(() => {
    clearCsrfToken()
    vi.unstubAllGlobals()
  })

  it('认证接口使用正确的方法与请求体', async () => {
    await auth.login({ username: 'admin', password: 'local-test-password' })
    await auth.getCurrentUser()
    await auth.logout()

    expect(fetchMock).toHaveBeenNthCalledWith(1, '/api/auth/login', expect.objectContaining({
      method: 'POST',
      body: JSON.stringify({ username: 'admin', password: 'local-test-password' }),
    }))
    expect(fetchMock).toHaveBeenNthCalledWith(2, '/api/auth/me', expect.any(Object))
    expect(fetchMock).toHaveBeenCalledWith('/api/security/csrf', expect.any(Object))
    expect(fetchMock).toHaveBeenCalledWith('/api/auth/logout', expect.objectContaining({ method: 'POST' }))
  })

  it('学生与入住接口覆盖分页筛选和完整生命周期路径', async () => {
    const student = { studentNo: '20260001', name: '测试学生', gender: '男', college: '计算机学院', grade: '2026', phone: '13800000000' }
    await checkin.fetchStudents()
    await checkin.fetchStudents({ page: 2, pageSize: 20, keyword: '2026', college: '计算机学院', grade: '2026', checkInStatus: '未入住' })
    await checkin.createStudent(student)
    await checkin.updateStudent(3, student)
    await checkin.deleteStudent(3)
    await checkin.fetchCheckInApplications({ page: 2, pageSize: 5, keyword: '测试', status: '待审核', studentId: 3, dormitoryId: 2 })
    await checkin.createCheckInApplication({ studentId: 3, dormitoryId: 2, remark: '申请' })
    await checkin.approveCheckInApplication(4, { bedId: 6, remark: '通过' })
    await checkin.rejectCheckInApplication(5, { remark: '拒绝' })
    await checkin.fetchCheckInRecords({ page: 1, pageSize: 20, keyword: '测试', status: '在住', dormitoryId: 2 })
    await checkin.createCheckInRecord({ studentId: 3, bedId: 6, remark: '分配' })
    await checkin.checkoutRecord(7, { remark: '毕业' })

    const urls = fetchMock.mock.calls.map(([url]) => String(url))
    expect(urls).toContain('/api/students?page=1&pageSize=10')
    expect(urls).toContain('/api/students?page=2&pageSize=20&keyword=2026&college=%E8%AE%A1%E7%AE%97%E6%9C%BA%E5%AD%A6%E9%99%A2&grade=2026&checkInStatus=%E6%9C%AA%E5%85%A5%E4%BD%8F')
    expect(urls).toContain('/api/check-in-applications/4/approve')
    expect(urls).toContain('/api/check-in-records/7/checkout')
  })

  it('运营接口覆盖维修、收费、卫生和公告读写路径', async () => {
    await operations.fetchRepairOrders({ page: 2, pageSize: 20, keyword: '101', type: '水电维修', status: '待处理' })
    await operations.createRepairOrder({ reporter: '张同学', location: '1号楼-101', type: '水电维修', description: '漏水', assigneeUserId: 8 })
    await operations.addRepairRecord(1, { content: '已处理', cost: 20, status: '处理中' })
    await operations.assignRepairOrder(1, 8)
    await operations.fetchRepairRecords({ repairOrderId: 1, keyword: '处理' })
    await operations.fetchPaymentBills({ status: '部分缴' })
    await operations.createPaymentBill({ studentNo: '20260001', name: '测试学生', type: '住宿费', amountDue: 800, deadline: '2026-08-31' })
    await operations.payPaymentBill(2, { amount: 300, method: '现金' })
    await operations.fetchPaymentRecords({ paymentId: 2, keyword: '现金' })
    const hygiene = { dormitory: '101宿舍', building: '1号楼', inspector: '管理员', score: 86, remark: '保持整洁' }
    await operations.fetchHygieneChecks({ result: '优秀' })
    await operations.createHygieneCheck(hygiene)
    await operations.updateHygieneCheck(3, hygiene)
    await operations.deleteHygieneCheck(3)
    const notice = { title: '安全通知', type: '安全卫生', publisher: '管理员', status: '已发布' as const }
    await operations.fetchNotices({ keyword: '安全', type: '安全卫生', status: '已发布' })
    await operations.createNotice(notice)
    await operations.updateNotice(4, notice)
    await operations.deleteNotice(4)

    const calls = fetchMock.mock.calls.map(([url, init]) => ({ url: String(url), init: init as RequestInit }))
    expect(calls.some(({ url }) => url.startsWith('/api/repair-records?'))).toBe(true)
    expect(calls.some(({ url }) => url.startsWith('/api/payment-records?'))).toBe(true)
    expect(calls).toContainEqual(expect.objectContaining({ url: '/api/repair-orders/1/assignee', init: expect.objectContaining({ method: 'PATCH' }) }))
    expect(calls).toContainEqual(expect.objectContaining({ url: '/api/notices/4', init: expect.objectContaining({ method: 'DELETE' }) }))
  })

  it('用户、角色、楼栋、床位和宿舍接口覆盖筛选与写操作', async () => {
    const user = { username: 'tester', displayName: '测试员', password: 'local-test-password', enabled: true, roleIds: [2] }
    await rbac.fetchUsers()
    await rbac.fetchUsers({ page: 2, pageSize: 20, keyword: '测试', enabled: false })
    await rbac.createUser(user)
    await rbac.updateUser(2, user)
    await rbac.deleteUser(2)
    await rbac.fetchRoles({ keyword: '宿舍', enabled: true })
    await rbac.fetchRoleOptions()
    await rbac.fetchPermissions()
    const role = { code: 'TEST_ROLE', name: '测试角色', description: '测试', enabled: true, permissionIds: [1] }
    await rbac.createRole(role)
    await rbac.updateRole(3, role)
    await rbac.deleteRole(3)

    const building = { code: 'B03', name: '3号楼', genderType: '男生宿舍', floors: 6, manager: '管理员', status: '启用' as const }
    await resource.fetchBuildings()
    await resource.fetchBuildings({ page: 2, pageSize: 20, keyword: '3号', status: '启用', buildingId: 3, dormitoryId: 4 })
    await resource.fetchBuildingOptions()
    await resource.createBuilding(building)
    await resource.updateBuilding(3, building)
    await resource.deleteBuilding(3)
    await resource.fetchBeds({ keyword: '01', status: '空闲', buildingId: 3, dormitoryId: 4 })
    await resource.updateBedStatus(9, '维修中')

    const room = { name: '301宿舍', type: '男生宿舍', buildingId: 3, beds: 6, occupied: 0 }
    await dormitory.fetchDormitoryPage()
    await dormitory.fetchDormitoryPage({ page: 2, pageSize: 20, keyword: '301', buildingId: 3, type: '男生宿舍', status: '入住中' })
    await dormitory.createDormitory(room)
    await dormitory.updateDormitory(6, room)
    await dormitory.deleteDormitory(6)

    const urls = fetchMock.mock.calls.map(([url]) => String(url))
    expect(urls).toContain('/api/users?page=2&pageSize=20&keyword=%E6%B5%8B%E8%AF%95&enabled=false')
    expect(urls).toContain('/api/buildings?page=1&pageSize=10')
    expect(urls).toContain('/api/buildings/options')
    expect(urls).toContain('/api/beds/9/status')
    expect(urls).toContain('/api/dormitories?page=1&pageSize=10')
    expect(urls).toContain('/api/dormitories/6')
  })
})
