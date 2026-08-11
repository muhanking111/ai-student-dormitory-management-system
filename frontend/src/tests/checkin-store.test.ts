import { createPinia, setActivePinia } from 'pinia'
import { beforeEach, describe, expect, it, vi } from 'vitest'
import {
  approveCheckInApplication,
  createCheckInApplication,
  createCheckInRecord,
  createStudent,
  deleteStudent,
  checkoutRecord,
  fetchCheckInApplications,
  fetchCheckInRecords,
  fetchStudents,
  rejectCheckInApplication,
  updateStudent,
} from '../api/checkin'
import { useCheckInStore } from '../stores/checkin'

vi.mock('../api/checkin', () => ({
  fetchStudents: vi.fn(),
  createStudent: vi.fn(),
  updateStudent: vi.fn(),
  deleteStudent: vi.fn(),
  fetchCheckInApplications: vi.fn(),
  createCheckInApplication: vi.fn(),
  approveCheckInApplication: vi.fn(),
  rejectCheckInApplication: vi.fn(),
  fetchCheckInRecords: vi.fn(),
  createCheckInRecord: vi.fn(),
  checkoutRecord: vi.fn(),
}))

describe('check-in store', () => {
  beforeEach(() => {
    setActivePinia(createPinia())
    vi.clearAllMocks()
  })

  it('保留学生服务端分页总数', async () => {
    vi.mocked(fetchStudents).mockResolvedValue({
      records: [{ id: 1, studentNo: '20261001', name: '测试学生', gender: '男', college: '计算机学院', grade: '2026', phone: '13800000001', checkInStatus: '未入住' }],
      total: 12,
      page: 2,
      pageSize: 5,
    })
    const store = useCheckInStore()

    await store.loadStudents({ page: 2, pageSize: 5, keyword: '测试' })

    expect(fetchStudents).toHaveBeenCalledWith({ page: 2, pageSize: 5, keyword: '测试' })
    expect(store.students).toHaveLength(1)
    expect(store.studentTotal).toBe(12)
  })

  it('审核入住申请后按当前筛选刷新列表', async () => {
    vi.mocked(approveCheckInApplication).mockResolvedValue({ id: 8, status: '已通过' } as never)
    vi.mocked(fetchCheckInApplications).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useCheckInStore()

    await store.approveApplication(8, 21, '审核通过', { page: 1, pageSize: 10, status: '待审核' })

    expect(approveCheckInApplication).toHaveBeenCalledWith(8, { bedId: 21, remark: '审核通过' })
    expect(fetchCheckInApplications).toHaveBeenCalledWith({ page: 1, pageSize: 10, status: '待审核' })
  })

  it('办理退宿后刷新入住记录', async () => {
    vi.mocked(checkoutRecord).mockResolvedValue({ id: 6, status: '已退宿' } as never)
    vi.mocked(fetchCheckInRecords).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useCheckInStore()

    await store.checkout(6, '正常退宿', { page: 1, pageSize: 10, status: '在住' })

    expect(checkoutRecord).toHaveBeenCalledWith(6, { remark: '正常退宿' })
    expect(fetchCheckInRecords).toHaveBeenCalledWith({ page: 1, pageSize: 10, status: '在住' })
  })

  it('支持新增、编辑和删除学生后刷新列表', async () => {
    vi.mocked(createStudent).mockResolvedValue({ id: 2 } as never)
    vi.mocked(updateStudent).mockResolvedValue({ id: 2 } as never)
    vi.mocked(deleteStudent).mockResolvedValue(undefined)
    vi.mocked(fetchStudents).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useCheckInStore()
    const input = { studentNo: '20261011', name: '写操作学生', gender: '男', college: '计算机学院', grade: '2026', phone: '13800001011' }
    const query = { keyword: '写操作' }

    await store.saveStudent(null, input, query)
    await store.saveStudent(2, input, query)
    await store.removeStudent(2, query)

    expect(createStudent).toHaveBeenCalledWith(input)
    expect(updateStudent).toHaveBeenCalledWith(2, input)
    expect(deleteStudent).toHaveBeenCalledWith(2)
    expect(fetchStudents).toHaveBeenCalledTimes(3)
  })

  it('支持提交、拒绝入住申请并刷新列表', async () => {
    vi.mocked(createCheckInApplication).mockResolvedValue({ id: 9 } as never)
    vi.mocked(rejectCheckInApplication).mockResolvedValue({ id: 9, status: '已拒绝' } as never)
    vi.mocked(fetchCheckInApplications).mockResolvedValue({ records: [], total: 0, page: 1, pageSize: 10 })
    const store = useCheckInStore()
    const query = { status: '待审核' }

    await store.submitApplication(1, 2, '申请备注', query)
    await store.rejectApplication(9, '拒绝备注', query)

    expect(createCheckInApplication).toHaveBeenCalledWith({ studentId: 1, dormitoryId: 2, remark: '申请备注' })
    expect(rejectCheckInApplication).toHaveBeenCalledWith(9, { remark: '拒绝备注' })
    expect(fetchCheckInApplications).toHaveBeenCalledTimes(2)
  })

  it('支持手工分配并记录加载错误', async () => {
    vi.mocked(createCheckInRecord).mockResolvedValue({ id: 3 } as never)
    vi.mocked(fetchCheckInRecords)
      .mockResolvedValueOnce({ records: [], total: 0, page: 1, pageSize: 10 })
      .mockRejectedValueOnce(new Error('记录接口失败'))
    const store = useCheckInStore()

    await store.assign(1, 2, '手工分配', { status: '在住' })
    await expect(store.loadRecords()).rejects.toThrow('记录接口失败')

    expect(createCheckInRecord).toHaveBeenCalledWith({ studentId: 1, bedId: 2, remark: '手工分配' })
    expect(store.error).toBe('记录接口失败')
    expect(store.loading).toBe(false)
  })
})
